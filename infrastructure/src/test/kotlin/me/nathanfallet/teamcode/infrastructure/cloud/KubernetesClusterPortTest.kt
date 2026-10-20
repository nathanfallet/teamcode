package me.nathanfallet.teamcode.infrastructure.cloud

import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.infrastructure.database.ClusterConnection
import kotlin.test.*

class KubernetesClusterPortTest {
    private val token = "cluster-service-account-token-0123456789"
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<JsonObject?>()

    private fun address(vararg octets: Int) = java.net.InetAddress.getByAddress(ByteArray(octets.size) { octets[it].toByte() })
    private fun port(resolver: (String) -> List<java.net.InetAddress> = { listOf(address(10, 0, 0, 1)) }, handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = KubernetesClusterPort(
        { ClusterConnection("https://k8s.example.test:6443", "unused-with-mock", token) },
        KubernetesPodOptions("https://teamcode.example.test", "registry.example/teamcode-runner:1.0"),
        MockEngine { request ->
            requests += request
            bodies += (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
            handler(request)
        },
        resolver,
    )

    @Test fun aClusterNameResolvingToAMetadataAddressIsNeverContacted(): Unit = runBlocking {
        for (resolved in listOf(
            listOf(address(169, 254, 169, 254)), listOf(address(10, 0, 0, 1), address(169, 254, 169, 254)), listOf(address(0, 0, 0, 0)),
            listOf(java.net.InetAddress.getByAddress(ByteArray(16) { if (it == 0) 0xfe.toByte() else if (it == 1) 0x80.toByte() else 0 })),
            listOf(java.net.InetAddress.getByAddress(byteArrayOf(0xfd.toByte(), 0, 0x0e, 0xc2.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x54))),
            emptyList(),
        )) {
            requests.clear()
            val port = port({ resolved }) { json("{}", HttpStatusCode.OK) }
            assertFailsWith<ClusterHostNotAllowedException>(resolved.toString()) { port.claim("c1", "teamcode", "tc-ws-1") }
            assertTrue(requests.isEmpty(), "The service-account token must not be sent to $resolved")
        }
        val ok = port({ listOf(address(10, 0, 0, 1), address(127, 0, 0, 1)) }) { json("""{"spec":{"volumeName":"pv-1"},"status":{"phase":"Bound"}}""") }
        assertNotNull(ok.claim("c1", "teamcode", "tc-ws-1"))
    }
    private fun MockRequestHandleScope.json(value: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(value, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun claimsVolumesAndIdempotentWrites(): Unit = runBlocking {
        val port = port { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/persistentvolumeclaims") -> json("""{"kind":"Status"}""", HttpStatusCode.Conflict)
                request.url.encodedPath == "/api/v1/namespaces/teamcode/persistentvolumeclaims/tc-ws-1" ->
                    if (request.method == HttpMethod.Delete) json("{}", HttpStatusCode.NotFound)
                    else json("""{"spec":{"volumeName":"pv-1","storageClassName":"standard"},"status":{"phase":"Bound"}}""")
                request.url.encodedPath == "/api/v1/persistentvolumes/pv-1" ->
                    json("""{"metadata":{"name":"pv-1"},"spec":{"persistentVolumeReclaimPolicy":"Delete","csi":{"volumeHandle":"cinder-1"},"claimRef":{"namespace":"teamcode","name":"tc-ws-1"}},"status":{"phase":"Bound"}}""")
                request.url.encodedPath == "/apis/storage.k8s.io/v1/storageclasses" ->
                    json("""{"metadata":{},"items":[{"metadata":{"name":"slow"},"reclaimPolicy":"Retain"},{"metadata":{"name":"fast","annotations":{"storageclass.kubernetes.io/is-default-class":"true"}},"reclaimPolicy":"Delete"}]}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        port.ensureClaim("c1", WorkspaceRequest("teamcode", "tc-ws-1", "standard", 10, mapOf("app.kubernetes.io/managed-by" to "teamcode")))
        val claim = bodies.single()!!
        assertEquals("ReadWriteOncePod", claim["spec"]!!.jsonObject["accessModes"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("10Gi", claim["spec"]!!.jsonObject["resources"]!!.jsonObject["requests"]!!.jsonObject["storage"]!!.jsonPrimitive.content)
        assertEquals("Bearer $token", requests.single().headers[HttpHeaders.Authorization])
        assertEquals(ClaimObservation(ClaimPhase.BOUND, "pv-1", "standard"), port.claim("c1", "teamcode", "tc-ws-1"))
        assertEquals(VolumeObservation("pv-1", "Bound", "Delete", "cinder-1", "teamcode", "tc-ws-1", true), port.volume("c1", "pv-1"))
        assertNull(port.volume("c1", "pv-gone"))
        port.deleteClaim("c1", "teamcode", "tc-ws-1") // Already gone: succeeds.
        assertEquals(StorageClassObservation("fast", "Delete"), port.storageClass("c1", null), "No class named: the cluster default")
        assertFailsWith<IllegalArgumentException> { port.claim("c1", "teamcode", "../secrets") }
    }

    @Test fun sessionPodIsBareNonRootAndCarriesOnlyAProjectedToken(): Unit = runBlocking {
        val port = port { json("{}", HttpStatusCode.Created) }
        port.createPod("c1", PodRequest("teamcode", "tc-1-e2", "registry.example/toolchain@sha256:" + "a".repeat(64), "tc-ws-1",
            mapOf("teamcode.nathanfallet.me/epoch" to "2"), CloudResources(2000, 4096), listOf("registry"),
            mapOf("TEAMCODE_CLOUD_SESSION" to "1", "TEAMCODE_CLOUD_EPOCH" to "2", "TEAMCODE_CLOUD_TEAM" to "t")))
        val pod = bodies.single()!!
        val spec = pod["spec"]!!.jsonObject
        assertEquals("Never", spec["restartPolicy"]!!.jsonPrimitive.content)
        assertFalse(spec["automountServiceAccountToken"]!!.jsonPrimitive.boolean, "The default API token is never mounted")
        assertTrue(spec["securityContext"]!!.jsonObject["runAsNonRoot"]!!.jsonPrimitive.boolean)
        val session = spec["containers"]!!.jsonArray.single().jsonObject
        assertEquals(false, session["securityContext"]!!.jsonObject["allowPrivilegeEscalation"]!!.jsonPrimitive.boolean)
        assertEquals("2000m", session["resources"]!!.jsonObject["limits"]!!.jsonObject["cpu"]!!.jsonPrimitive.content)
        val env = session["env"]!!.jsonArray.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content }
        assertEquals("2", env["TEAMCODE_CLOUD_EPOCH"]); assertEquals("https://teamcode.example.test", env["TEAMCODE_SERVER_URL"])
        assertTrue(env.values.none { it.contains(token) }, "No credential in the pod environment")
        val projected = spec["volumes"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "runner-token" }
        val source = projected["projected"]!!.jsonObject["sources"]!!.jsonArray.single().jsonObject["serviceAccountToken"]!!.jsonObject
        assertEquals("teamcode", source["audience"]!!.jsonPrimitive.content); assertEquals(600, source["expirationSeconds"]!!.jsonPrimitive.int)
        assertEquals("registry", spec["imagePullSecrets"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun podObservationTokenReviewAndPagedLists(): Unit = runBlocking {
        var page = 0
        val port = port { request ->
            when {
                request.url.encodedPath == "/api/v1/namespaces/teamcode/pods/tc-1-e1" -> json("""{"metadata":{"name":"tc-1-e1","labels":{"teamcode.nathanfallet.me/epoch":"1"},"deletionTimestamp":"2026-10-19T10:00:00Z"},
                    "spec":{"nodeName":"node-a"},"status":{"phase":"Pending","containerStatuses":[{"name":"session","state":{"waiting":{"reason":"ImagePullBackOff","message":"secret-ish detail"}}}]}}""")
                request.url.encodedPath == "/apis/authentication.k8s.io/v1/tokenreviews" -> json("""{"status":{"authenticated":true,"audiences":["teamcode"],
                    "user":{"username":"system:serviceaccount:teamcode:default","extra":{"authentication.kubernetes.io/pod-name":["tc-1-e1"],"authentication.kubernetes.io/pod-uid":["u1"]}}}}""", HttpStatusCode.Created)
                request.url.encodedPath == "/api/v1/namespaces/teamcode/pods" -> {
                    assertEquals("app.kubernetes.io/managed-by=teamcode,teamcode.nathanfallet.me/cluster=c1", request.url.parameters["labelSelector"])
                    page++
                    if (page == 1) json("""{"metadata":{"continue":"next"},"items":[{"metadata":{"name":"tc-1-e1"},"status":{"phase":"Running"}}]}""")
                    else { assertEquals("next", request.url.parameters["continue"]); json("""{"metadata":{},"items":[{"metadata":{"name":"tc-2-e1"},"status":{"phase":"Running"}}]}""") }
                }
                request.url.encodedPath == "/api/v1/namespaces/teamcode/persistentvolumeclaims" -> json("""{"metadata":{},"items":[{"metadata":{"name":"tc-ws-1"}}]}""")
                else -> json("""{"message":"forbidden: $token"}""", HttpStatusCode.Forbidden)
            }
        }
        val pod = port.pod("c1", "teamcode", "tc-1-e1")!!
        assertEquals(PodObservation("tc-1-e1", PodPhase.PENDING, 1, "node-a", null, true, "ImagePullBackOff"), pod)
        assertEquals(TokenIdentity("teamcode", "default", "tc-1-e1", "u1"), port.reviewToken("c1", "pod-token-0123456789", "teamcode"))
        assertEquals("pod-token-0123456789", bodies.last()!!["spec"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        assertEquals(listOf("tc-1-e1", "tc-2-e1"), port.managed("c1", "teamcode").pods.map { it.name })
        val error = assertFailsWith<ClusterApiException> { port.volume("c1", "pv-1") }
        assertEquals(403, error.status)
        assertFalse(error.message!!.contains(token), "The API body is never surfaced")
    }

    @Test fun partialListsAndRunnerOriginsAreRefused(): Unit = runBlocking {
        for (origin in listOf("http://teamcode.example.test", "https://localhost:8080", "https://127.0.0.1", "https://user@teamcode.example.test"))
            assertFailsWith<IllegalArgumentException>(origin) { KubernetesPodOptions(origin, "runner:1") }
        KubernetesPodOptions("https://teamcode.example.test:8443/", "runner:1")
        var page = 0
        val port = port { request ->
            page++
            if (page == 1) json("""{"metadata":{"continue":"next"},"items":[{"metadata":{"name":"tc-1-e1"},"status":{"phase":"Running"}}]}""")
            else json("{}", HttpStatusCode.NotFound)
        }
        assertEquals(404, assertFailsWith<ClusterApiException> { port.managed("c1", "teamcode") }.status, "A page vanishing mid-list is not a complete list")
        val missing = port { json("{}", HttpStatusCode.NotFound) }
        assertEquals(ManagedObjects(emptyList(), emptyList()), missing.managed("c1", "gone"), "A missing namespace lists nothing")
    }

    @Test fun refusedTokenReviews(): Unit = runBlocking {
        val responses = ArrayDeque(listOf(
            """{"status":{"authenticated":false}}""",
            """{"status":{"authenticated":true,"audiences":["other"],"user":{"username":"system:serviceaccount:teamcode:default"}}}""",
            """{"status":{"authenticated":true,"user":{"username":"alice"}}}""",
        ))
        val port = port { json(responses.removeFirst(), HttpStatusCode.Created) }
        repeat(3) { assertNull(port.reviewToken("c1", "pod-token-0123456789", "teamcode")) }
    }
}
