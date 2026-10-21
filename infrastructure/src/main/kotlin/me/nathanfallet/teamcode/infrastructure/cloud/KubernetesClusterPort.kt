package me.nathanfallet.teamcode.infrastructure.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.ReconcileCloudSessionsUseCase.Companion.CLUSTER_LABEL
import me.nathanfallet.teamcode.domain.cloud.ReconcileCloudSessionsUseCase.Companion.EPOCH_LABEL
import me.nathanfallet.teamcode.domain.cloud.ReconcileCloudSessionsUseCase.Companion.MANAGED_LABEL
import me.nathanfallet.teamcode.infrastructure.database.ClusterConnection

/** A non-success Kubernetes API status. The response body is never kept: it may echo request content. */
class ClusterApiException(val status: Int) : Exception("The Kubernetes API returned HTTP $status.")

/** The cluster API host resolves to a link-local, unspecified or cloud metadata address, so the service-account token is not sent. */
class ClusterHostNotAllowedException : Exception("The Kubernetes API host resolves to an address that is not allowed.")

/**
 * How session pods are built.
 *
 * @param serverUrl the TeamCode origin the runner calls back, reachable from the cluster.
 * @param runnerImage an image holding the `teamcode` Linux binary at [runnerBinary]; an init container copies
 *   it into the pod, so session images need no TeamCode installation.
 * @param runAsUser the runner container's UID, the owner of the pod's group (`fsGroup`); [agentRunAsUser] runs the agent container.
 * @param workspaceAccessMode `ReadWriteOncePod` where the CSI driver supports it (one pod per workspace,
 *   enforced by the cluster); `ReadWriteOnce` otherwise, for example on a local `kind` cluster.
 */
data class KubernetesPodOptions(
    val serverUrl: String,
    val runnerImage: String,
    val runnerBinary: String = "/usr/local/bin/teamcode",
    val workspaceAccessMode: String = "ReadWriteOncePod",
    val runAsUser: Long = 1000,
    /** The agent's UID: it must not be the runner's, whose files and projected token it must never read. */
    val agentRunAsUser: Long = 1001,
) {
    init {
        // The runner refuses plain HTTP except on loopback, and a pod's loopback is not the server.
        require(CloudRuntimeService.usableOrigin(serverUrl)) {
            "Set the server URL for pods (Dashboard → Cloud sessions) to the HTTPS origin session pods reach."
        }
        require(runAsUser > 0 && agentRunAsUser > 0 && runAsUser != agentRunAsUser) { "The runner and the agent run as different non-root UIDs." }
        require(workspaceAccessMode in CloudRuntime.ACCESS_MODES) { "Use ReadWriteOncePod or ReadWriteOnce." }
    }
}

/**
 * [ClusterPort] over the Kubernetes REST API with the server's Ktor client. Each cluster's API URL, CA bundle
 * and service-account token come from the database ([connections]); a client is built per cluster with a trust
 * store holding only that CA bundle, and rebuilt when the connection changes. No call holds a transaction.
 *
 * Creating an existing object and deleting a missing one succeed, so every call is idempotent.
 */
class KubernetesClusterPort(
    private val connections: suspend (clusterId: String) -> ClusterConnection?,
    /** Read for every pod, so a runtime saved on the dashboard applies to the next pod without a restart. */
    private val options: () -> KubernetesPodOptions,
    /** Tests pass a mock engine; production builds one CIO client per cluster. */
    private val engine: HttpClientEngine? = null,
    /** Tests pass a fixed resolver; production resolves the API host with the system resolver. */
    private val resolver: (String) -> List<java.net.InetAddress> = { host -> java.net.InetAddress.getAllByName(host).toList() },
    /** Read for every claim; a claim needs neither the runner image nor the callback origin. */
    private val workspaceAccessMode: () -> String = { options().workspaceAccessMode },
) : ClusterPort, AutoCloseable {

    private class Client(val fingerprint: String, val connection: ClusterConnection, val http: HttpClient, val loadedAt: Long)
    private val clients = ConcurrentHashMap<String, Client>()
    /** Clients replaced after a rotation stay open until [close]: requests in flight may still use them. */
    private val retired = java.util.concurrent.ConcurrentLinkedQueue<HttpClient>()
    private val creation = kotlinx.coroutines.sync.Mutex()
    private val shared by lazy { engine?.let { HttpClient(it) { expectSuccess = false } } }

    override suspend fun storageClass(clusterId: String, name: String?): StorageClassObservation? {
        if (name != null) return get(clusterId, "/apis/storage.k8s.io/v1/storageclasses/${segment(name)}")?.let(::storageClass)
        // No class named: the cluster default, as the API server would pick it for the claim.
        return list(clusterId, "/apis/storage.k8s.io/v1/storageclasses", null).firstOrNull {
            it.obj("metadata")?.obj("annotations")?.str("storageclass.kubernetes.io/is-default-class") == "true"
        }?.let(::storageClass)
    }

    override suspend fun ensureClaim(clusterId: String, request: WorkspaceRequest) {
        val accessMode = workspaceAccessMode()
        require(accessMode in CloudRuntime.ACCESS_MODES) { "Use ReadWriteOncePod or ReadWriteOnce." }
        val body = buildJsonObject {
            put("apiVersion", "v1"); put("kind", "PersistentVolumeClaim")
            putJsonObject("metadata") { put("name", request.claimName); putLabels(request.labels) }
            putJsonObject("spec") {
                putJsonArray("accessModes") { add(accessMode) }
                request.storageClass?.let { put("storageClassName", it) }
                putJsonObject("resources") { putJsonObject("requests") { put("storage", "${request.sizeGibibytes}Gi") } }
            }
        }
        create(clusterId, "/api/v1/namespaces/${segment(request.namespace)}/persistentvolumeclaims", body)
    }

    override suspend fun claim(clusterId: String, namespace: String, name: String): ClaimObservation? =
        get(clusterId, "/api/v1/namespaces/${segment(namespace)}/persistentvolumeclaims/${segment(name)}")?.let { claim ->
            val phase = when (claim.obj("status")?.str("phase")) { "Bound" -> ClaimPhase.BOUND; "Lost" -> ClaimPhase.LOST; else -> ClaimPhase.PENDING }
            ClaimObservation(phase, claim.obj("spec")?.str("volumeName"), claim.obj("spec")?.str("storageClassName"))
        }

    override suspend fun deleteClaim(clusterId: String, namespace: String, name: String) =
        delete(clusterId, "/api/v1/namespaces/${segment(namespace)}/persistentvolumeclaims/${segment(name)}", null)

    override suspend fun volume(clusterId: String, name: String): VolumeObservation? =
        get(clusterId, "/api/v1/persistentvolumes/${segment(name)}")?.let(::volume)

    override suspend fun orphanedVolumes(clusterId: String): List<VolumeObservation> =
        // PersistentVolumes are cluster-wide and carry no TeamCode label: read them all, with a larger page budget.
        list(clusterId, "/api/v1/persistentvolumes", null, maxPages = 200).map(::volume).filter { it.managed && (it.phase == "Released" || it.phase == "Failed") }

    override suspend fun createPod(clusterId: String, request: PodRequest) {
        create(clusterId, "/api/v1/namespaces/${segment(request.namespace)}/pods", podManifest(request))
    }

    override suspend fun pod(clusterId: String, namespace: String, name: String): PodObservation? =
        get(clusterId, "/api/v1/namespaces/${segment(namespace)}/pods/${segment(name)}")?.let(::pod)

    override suspend fun deletePod(clusterId: String, namespace: String, name: String, force: Boolean) =
        delete(clusterId, "/api/v1/namespaces/${segment(namespace)}/pods/${segment(name)}", if (force) 0 else null)

    override suspend fun managed(clusterId: String, namespace: String): ManagedObjects {
        val selector = "$MANAGED_LABEL=teamcode,$CLUSTER_LABEL=$clusterId"
        return ManagedObjects(
            list(clusterId, "/api/v1/namespaces/${segment(namespace)}/pods", selector).map(::pod),
            list(clusterId, "/api/v1/namespaces/${segment(namespace)}/persistentvolumeclaims", selector).mapNotNull { it.obj("metadata")?.str("name") },
        )
    }

    override suspend fun reviewToken(clusterId: String, token: String, audience: String): TokenIdentity? {
        val body = buildJsonObject {
            put("apiVersion", "authentication.k8s.io/v1"); put("kind", "TokenReview")
            putJsonObject("spec") { put("token", token); putJsonArray("audiences") { add(audience) } }
        }
        val response = call(clusterId, HttpMethod.Post, "/apis/authentication.k8s.io/v1/tokenreviews", body)
        if (!response.status.isSuccess()) throw ClusterApiException(response.status.value)
        val status = parse(response).obj("status") ?: return null
        if (status["authenticated"]?.jsonPrimitive?.booleanOrNull != true) return null
        if (status["audiences"]?.jsonArray?.none { it.jsonPrimitive.contentOrNull == audience } == true) return null
        val user = status.obj("user") ?: return null
        val parts = user.str("username")?.split(':') ?: return null
        if (parts.size != 4 || parts[0] != "system" || parts[1] != "serviceaccount") return null
        val extra = user.obj("extra")
        fun extra(key: String) = extra?.get(key)?.jsonArray?.singleOrNull()?.jsonPrimitive?.contentOrNull
        return TokenIdentity(parts[2], parts[3], extra("authentication.kubernetes.io/pod-name"), extra("authentication.kubernetes.io/pod-uid"))
    }

    /**
     * The session pod: bare (`restartPolicy: Never`), non-root, no API token mounted. Two containers share the
     * workspace and a memory-backed volume holding two sockets, and nothing else:
     *
     * - `session` is the runner. It alone mounts the projected token, and it holds the server credentials.
     * - `agent` is the same image under another UID with no token. It runs the exec supervisor, which starts the
     *   agent and the preparation commands for the runner, and the git helper reaches the runner's credential socket.
     *
     * The containers do not share a process namespace, so the agent cannot read the runner's memory or environment.
     */
    internal fun podManifest(request: PodRequest): JsonObject = podManifest(request, options())

    private fun podManifest(request: PodRequest, pods: KubernetesPodOptions): JsonObject = buildJsonObject {
        put("apiVersion", "v1"); put("kind", "Pod")
        putJsonObject("metadata") { put("name", request.name); putLabels(request.labels) }
        putJsonObject("spec") {
            put("restartPolicy", "Never")
            put("automountServiceAccountToken", false)
            put("enableServiceLinks", false)
            put("shareProcessNamespace", false)
            put("terminationGracePeriodSeconds", 30)
            putJsonObject("securityContext") {
                put("runAsNonRoot", true); put("runAsUser", pods.runAsUser); put("runAsGroup", pods.runAsUser); put("fsGroup", pods.runAsUser)
                putJsonObject("seccompProfile") { put("type", "RuntimeDefault") }
            }
            if (request.imagePullSecrets.isNotEmpty()) putJsonArray("imagePullSecrets") { request.imagePullSecrets.forEach { addJsonObject { put("name", it) } } }
            putJsonArray("initContainers") {
                addJsonObject {
                    put("name", "install-runner"); put("image", pods.runnerImage)
                    putJsonArray("command") { add("cp"); add(pods.runnerBinary); add("$RUNNER_DIR/teamcode") }
                    put("securityContext", containerSecurity())
                    putJsonArray("volumeMounts") { addJsonObject { put("name", "runner"); put("mountPath", RUNNER_DIR) } }
                    putJsonObject("resources") { putJsonObject("requests") { put("cpu", "50m"); put("memory", "32Mi") }; putJsonObject("limits") { put("cpu", "200m"); put("memory", "64Mi") } }
                }
            }
            putJsonArray("containers") {
                addJsonObject {
                    put("name", SESSION_CONTAINER); put("image", request.image); put("workingDir", WORKSPACE_DIR)
                    putJsonArray("command") { add("$RUNNER_DIR/teamcode"); add("cloud"); add("runner") }
                    putJsonArray("env") {
                        fun env(name: String, value: String) = addJsonObject { put("name", name); put("value", value) }
                        env("TEAMCODE_SERVER_URL", pods.serverUrl)
                        env("TEAMCODE_CLOUD_TOKEN_FILE", "$TOKEN_DIR/token")
                        env("TEAMCODE_AGENT_SOCKET", AGENT_SOCKET)
                        env("TEAMCODE_CREDENTIAL_SOCKET", CREDENTIAL_SOCKET)
                        env("HOME", "$WORKSPACE_DIR/.home")
                        request.env.toSortedMap().forEach { (name, value) -> env(name, value) }
                    }
                    put("securityContext", containerSecurity(pods.runAsUser))
                    // The runner only relays: heartbeats, the transcript buffer and the loopback proxy.
                    putJsonObject("resources") {
                        putJsonObject("requests") { put("cpu", RUNNER_CPU); put("memory", RUNNER_MEMORY) }
                        putJsonObject("limits") { put("cpu", RUNNER_CPU); put("memory", RUNNER_MEMORY) }
                    }
                    putJsonArray("volumeMounts") {
                        addJsonObject { put("name", "workspace"); put("mountPath", WORKSPACE_DIR) }
                        addJsonObject { put("name", "runner"); put("mountPath", RUNNER_DIR); put("readOnly", true) }
                        addJsonObject { put("name", "runner-token"); put("mountPath", TOKEN_DIR); put("readOnly", true) }
                        addJsonObject { put("name", "sockets"); put("mountPath", SOCKET_DIR) }
                    }
                }
                addJsonObject {
                    put("name", AGENT_CONTAINER); put("image", request.image); put("workingDir", WORKSPACE_DIR)
                    putJsonArray("command") { add("$RUNNER_DIR/teamcode"); add("cloud"); add("agent-supervisor") }
                    putJsonArray("env") {
                        fun env(name: String, value: String) = addJsonObject { put("name", name); put("value", value) }
                        // No server URL, token path or session identifier: the agent's side knows nothing of the runner channel.
                        env("TEAMCODE_AGENT_SOCKET", AGENT_SOCKET)
                        env("TEAMCODE_CREDENTIAL_SOCKET", CREDENTIAL_SOCKET)
                        env("HOME", AGENT_HOME)
                        env("GIT_TERMINAL_PROMPT", "0")
                        env("GIT_CONFIG_COUNT", "1")
                        env("GIT_CONFIG_KEY_0", "credential.helper")
                        env("GIT_CONFIG_VALUE_0", "!$RUNNER_DIR/teamcode cloud credential")
                    }
                    put("securityContext", containerSecurity(pods.agentRunAsUser))
                    putJsonObject("resources") {
                        val cpu = "${request.resources.cpuMillis}m"; val memory = "${request.resources.memoryMebibytes}Mi"
                        putJsonObject("requests") { put("cpu", cpu); put("memory", memory) }
                        putJsonObject("limits") { put("cpu", cpu); put("memory", memory) }
                    }
                    putJsonArray("volumeMounts") {
                        addJsonObject { put("name", "workspace"); put("mountPath", WORKSPACE_DIR) }
                        addJsonObject { put("name", "runner"); put("mountPath", RUNNER_DIR); put("readOnly", true) }
                        addJsonObject { put("name", "sockets"); put("mountPath", SOCKET_DIR) }
                    }
                }
            }
            putJsonArray("volumes") {
                addJsonObject { put("name", "workspace"); putJsonObject("persistentVolumeClaim") { put("claimName", request.claimName) } }
                addJsonObject { put("name", "runner"); putJsonObject("emptyDir") { put("sizeLimit", "256Mi") } }
                addJsonObject { put("name", "sockets"); putJsonObject("emptyDir") { put("medium", "Memory"); put("sizeLimit", "1Mi") } }
                addJsonObject {
                    put("name", "runner-token")
                    putJsonObject("projected") { putJsonArray("sources") { addJsonObject { putJsonObject("serviceAccountToken") {
                        put("audience", AuthenticateRunnerUseCase.AUDIENCE); put("expirationSeconds", TOKEN_SECONDS); put("path", "token")
                    } } } }
                }
            }
        }
    }

    private fun containerSecurity(user: Long? = null) = buildJsonObject {
        user?.let { put("runAsUser", it); put("runAsNonRoot", true) }
        put("allowPrivilegeEscalation", false)
        putJsonObject("capabilities") { putJsonArray("drop") { add("ALL") } }
    }

    private fun storageClass(json: JsonObject) = StorageClassObservation(json.obj("metadata")?.str("name") ?: "", json.str("reclaimPolicy") ?: "Delete")
    private fun volume(json: JsonObject): VolumeObservation {
        val spec = json.obj("spec"); val claim = spec?.obj("claimRef")
        val claimName = claim?.str("name")
        return VolumeObservation(json.obj("metadata")?.str("name") ?: "", json.obj("status")?.str("phase") ?: "Pending",
            spec?.str("persistentVolumeReclaimPolicy") ?: "Retain", spec?.obj("csi")?.str("volumeHandle"), claim?.str("namespace"), claimName,
            managed = claimName?.startsWith("tc-ws-") == true)
    }
    private fun pod(json: JsonObject): PodObservation {
        val metadata = json.obj("metadata"); val status = json.obj("status")
        val phase = when (status?.str("phase")) { "Pending" -> PodPhase.PENDING; "Running" -> PodPhase.RUNNING; "Succeeded" -> PodPhase.SUCCEEDED; "Failed" -> PodPhase.FAILED; else -> PodPhase.UNKNOWN }
        val containers = (status?.get("initContainerStatuses")?.jsonArray.orEmpty() + status?.get("containerStatuses")?.jsonArray.orEmpty()).map { it.jsonObject }
        val reason = containers.firstNotNullOfOrNull { it.obj("state")?.obj("waiting")?.str("reason") }?.takeIf { safeReason.matches(it) }
        val session = status?.get("containerStatuses")?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("name") == SESSION_CONTAINER }
        return PodObservation(metadata?.str("name") ?: "", phase, metadata?.obj("labels")?.str(EPOCH_LABEL)?.toIntOrNull(), json.obj("spec")?.str("nodeName"),
            session?.str("imageID")?.takeIf { it.length <= 512 }, metadata?.get("deletionTimestamp")?.let { it !is JsonNull } == true, reason)
    }

    private suspend fun get(clusterId: String, path: String): JsonObject? {
        val response = call(clusterId, HttpMethod.Get, path, null)
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) throw ClusterApiException(response.status.value)
        return parse(response)
    }
    private suspend fun create(clusterId: String, path: String, body: JsonObject) {
        val response = call(clusterId, HttpMethod.Post, path, body)
        if (!response.status.isSuccess() && response.status != HttpStatusCode.Conflict) throw ClusterApiException(response.status.value)
    }
    private suspend fun delete(clusterId: String, path: String, gracePeriodSeconds: Int?) {
        val body = buildJsonObject { put("apiVersion", "v1"); put("kind", "DeleteOptions"); put("propagationPolicy", "Background"); gracePeriodSeconds?.let { put("gracePeriodSeconds", it) } }
        val response = call(clusterId, HttpMethod.Delete, path, body)
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) throw ClusterApiException(response.status.value)
    }
    /** Every page of a list, at most [MAX_PAGES] pages of [PAGE_SIZE]. */
    private suspend fun list(clusterId: String, path: String, labelSelector: String?, maxPages: Int = MAX_PAGES): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var next: String? = null
        repeat(maxPages) {
            val query = Parameters.build {
                append("limit", PAGE_SIZE.toString()); labelSelector?.let { append("labelSelector", it) }; next?.let { append("continue", it) }
            }.formUrlEncode()
            // A missing namespace lists nothing; a page vanishing mid-list must not pass for a complete list.
            val page = get(clusterId, "$path?$query") ?: if (next == null) return items else throw ClusterApiException(404)
            page["items"]?.jsonArray?.forEach { items += it.jsonObject }
            next = page.obj("metadata")?.str("continue")?.takeIf { it.isNotEmpty() } ?: return items
        }
        throw ClusterApiException(507) // More objects than one pass may read; refuse rather than act on a partial list.
    }

    private suspend fun call(clusterId: String, method: HttpMethod, path: String, body: JsonObject?): HttpResponse {
        val client = client(clusterId)
        return client.http.request(client.connection.apiUrl.trimEnd('/') + path) {
            this.method = method
            bearerAuth(client.connection.token)
            accept(ContentType.Application.Json)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        }
    }
    private suspend fun parse(response: HttpResponse): JsonObject {
        val text = response.bodyAsText()
        if (text.length > MAX_RESPONSE_CHARS) throw ClusterApiException(507)
        return Json.parseToJsonElement(text).jsonObject
    }

    private suspend fun client(clusterId: String): Client {
        // The connection is decrypted from the database at most every 30 s per cluster, so a rotated token applies quickly.
        clients[clusterId]?.takeIf { System.currentTimeMillis() - it.loadedAt < CONNECTION_REFRESH_MILLIS }?.let { return it }
        return creation.withLock { clients[clusterId]?.takeIf { System.currentTimeMillis() - it.loadedAt < CONNECTION_REFRESH_MILLIS } ?: load(clusterId) }
    }

    private suspend fun load(clusterId: String): Client {
        val connection = connections(clusterId) ?: throw ClusterApiException(404)
        requireAllowedHost(connection.apiUrl)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest((connection.apiUrl + "\u0000" + connection.caCertificate + "\u0000" + connection.token).encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        clients[clusterId]?.takeIf { it.fingerprint == fingerprint }?.let { current ->
            return Client(fingerprint, connection, current.http, System.currentTimeMillis()).also { clients[clusterId] = it }
        }
        val http = shared ?: HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 }
            engine { https { trustManager = trustManager(connection.caCertificate) } }
        }
        val created = Client(fingerprint, connection, http, System.currentTimeMillis())
        clients.put(clusterId, created)?.let { previous -> if (previous.http !== shared) retired += previous.http }
        return created
    }

    /**
     * A DNS name is re-resolved whenever the connection is loaded (at least every 30 s) and refused if any answer is
     * link-local, unspecified or a cloud metadata address, as for a literal address in the administration check.
     * Resolution happens off the event loop. The check cannot pin the address the HTTP client later dials.
     */
    private suspend fun requireAllowedHost(apiUrl: String) {
        val host = try { Url(apiUrl).host.removePrefix("[").removeSuffix("]") } catch (_: Exception) { throw ClusterHostNotAllowedException() }
        val addresses = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolver(host) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { throw ClusterHostNotAllowedException() }
        if (addresses.isEmpty() || addresses.any { !CloudAdministrationService.allowedResolvedAddress(it.address) }) throw ClusterHostNotAllowedException()
    }

    override fun close() {
        clients.values.forEach { if (it.http !== shared) it.http.close() }; clients.clear()
        generateSequence { retired.poll() }.forEach { it.close() }
        shared?.close()
    }

    companion object {
        /** The runner: the container the server observes, and the only one holding the projected token. */
        const val SESSION_CONTAINER = "session"
        const val AGENT_CONTAINER = "agent"
        const val WORKSPACE_DIR = "/workspace"
        const val RUNNER_DIR = "/opt/teamcode"
        const val TOKEN_DIR = "/var/run/secrets/teamcode"
        /** A memory volume shared by the two containers: the supervisor socket (served by the agent side) and the credential socket (served by the runner). */
        const val SOCKET_DIR = "/run/teamcode"
        const val AGENT_SOCKET = "$SOCKET_DIR/agent.sock"
        const val CREDENTIAL_SOCKET = "$SOCKET_DIR/credential.sock"
        /** The agent's `HOME`, created by the agent's UID on the workspace volume (the runner's lives in `.home`). */
        const val AGENT_HOME = "$WORKSPACE_DIR/.agent-home"
        private const val RUNNER_CPU = "250m"
        private const val RUNNER_MEMORY = "256Mi"
        /** The minimum the API server accepts; the kubelet rotates the file before expiry. */
        const val TOKEN_SECONDS = 600
        private const val CONNECTION_REFRESH_MILLIS = 30_000L
        private const val PAGE_SIZE = 500
        private const val MAX_PAGES = 20
        private const val MAX_RESPONSE_CHARS = 16 * 1024 * 1024
        private val safeReason = Regex("[A-Za-z]{1,64}")

        /** A trust manager that accepts only certificates chaining to the given PEM bundle. */
        fun trustManager(pem: String): X509TrustManager {
            val certificates = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.encodeToByteArray()))
            require(certificates.isNotEmpty()) { "The CA bundle holds no certificate." }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("ca-$index", certificate) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        }
        private fun segment(value: String): String {
            require(Regex("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?").matches(value)) { "Invalid Kubernetes object name." }
            return value
        }
        private fun JsonObject.obj(key: String) = (this[key] as? JsonObject)
        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObjectBuilder.putLabels(labels: Map<String, String>) = putJsonObject("labels") { labels.toSortedMap().forEach { (k, v) -> put(k, v) } }
    }
}
