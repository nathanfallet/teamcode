package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*

/**
 * A profile being edited on the dashboard: the next version of [id] (or a new profile), based on [version]. It is not
 * stored: the form carries it from one step to the next (reloading the cluster's choices, checking, publishing).
 */
internal data class ProfileDraft(val id: String?, val version: Int, val name: String, val spec: CloudProfileSpec, val members: Set<String>) {
    companion object {
        fun of(profile: CloudProfile) = ProfileDraft(profile.id, profile.version, profile.name, profile.spec, profile.assignedMemberIds)
        fun new(cluster: CloudCluster?) = ProfileDraft(null, 0, "", CloudProfileSpec(cluster?.id.orEmpty(), cluster?.allowedNamespaces?.first().orEmpty(), ""), emptySet())

        /** Out-of-range or missing numbers become -1, so the service's validation explains them. */
        fun parse(input: Parameters): ProfileDraft {
            fun text(name: String) = input[name]?.trim().orEmpty()
            fun optional(name: String) = text(name).takeIf { it.isNotEmpty() }
            fun number(name: String) = text(name).toIntOrNull() ?: -1
            fun words(name: String) = text(name).split(',', ' ', '\n', '\r', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            fun lines(name: String) = input.getAll(name).orEmpty().map { it.replace("\r\n", "\n").trim() }.filter { it.isNotEmpty() }
            val urls = input.getAll("repositoryUrl").orEmpty()
            val repositories = urls.indices.mapNotNull { index ->
                fun field(name: String) = input.getAll(name)?.getOrNull(index)?.trim().orEmpty()
                val url = field("repositoryUrl"); val path = field("repositoryPath")
                if (url.isEmpty() && path.isEmpty()) null
                else CloudRepository(url, path, field("repositoryRef").ifEmpty { null }, field("repositoryBranch").ifEmpty { null })
            }
            val secrets = (input.getAll("imagePullSecret").orEmpty().map { it.trim() }.filter { it.isNotEmpty() } + words("imagePullSecretsOther")).distinct()
            val spec = CloudProfileSpec(
                clusterId = text("clusterId"), namespace = text("namespace"), image = text("image"),
                allowedImageOverrides = text("allowedImageOverrides").lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                imagePullSecrets = secrets, resources = CloudResources(number("cpuMillis"), number("memoryMebibytes")),
                workspaceSizeGibibytes = number("workspaceSizeGibibytes"), storageClass = optional("storageClass"),
                agentKind = text("agentKind"), agentVersion = optional("agentVersion"),
                idleTimeoutMinutes = number("idleTimeoutMinutes"), minIdleTimeoutMinutes = number("minIdleTimeoutMinutes"), maxIdleTimeoutMinutes = number("maxIdleTimeoutMinutes"),
                environment = CloudEnvironmentSpec(repositories, optional("workingDirectory"), CloudSetup(lines("setupCreate"), lines("setupStart")),
                    words("requiredVariables"), input["requireGitHub"] == "on", optional("gitEmailDomain"), words("repositoryHosts")),
            )
            val id = optional("id")?.also { if (!CloudIdentifier.valid(it)) throw TeamFailure("not_found", 404, "Profile not found.") }
            return ProfileDraft(id, text("version").toIntOrNull() ?: 0, text("name"), spec, input.getAll("member").orEmpty().toSet())
        }
    }
}

/** Shown once the draft passed every check, or why it did not. */
internal data class DraftOutcome(val message: String, val problem: Boolean)

private val CPU_PRESETS = listOf(250, 500, 1000, 2000, 4000, 8000, 16_000, 32_000, 64_000)
private val MEMORY_PRESETS = listOf(512, 1024, 2048, 4096, 8192, 16_384, 32_768, 65_536, 131_072)
private val WORKSPACE_PRESETS = listOf(1, 5, 10, 20, 50, 100, 200, 500, 1000, 2048)
private val IDLE_PRESETS = listOf(5, 10, 15, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720, 1440)

private fun cpuLabel(millis: Int) = if (millis % 1000 == 0) "${millis / 1000} CPU" else "${millis / 1000.0} CPU (${millis}m)"
private fun memoryLabel(mebibytes: Int) = if (mebibytes % 1024 == 0) "${mebibytes / 1024} GiB" else "$mebibytes MiB"
private fun minutesLabel(minutes: Int) = when {
    minutes % 60 == 0 -> "${minutes / 60} hour${if (minutes == 60) "" else "s"}"
    else -> "$minutes minutes"
}

/** A select over [presets], plus the draft's value when it is not one of them (a profile saved through the API). */
private fun presetSelect(name: String, presets: List<Int>, value: Int, label: (Int) -> String) =
    """<select name="$name" required>""" + (if (value > 0 && value !in presets) (presets + value).sorted() else presets).joinToString("") {
        """<option value="$it"${if (it == value) " selected" else ""}>${escape(label(it))}</option>"""
    } + "</select>"

internal fun profileEditor(
    actor: Principal,
    draft: ProfileDraft,
    clusters: List<CloudCluster>,
    choices: ClusterChoices?,
    members: List<Member>,
    images: List<String>,
    outcome: DraftOutcome?,
): String {
    val spec = draft.spec
    val next = draft.version + 1
    val intro = if (draft.id == null) "<p>A new profile. Publishing creates its version 1.</p>"
        else "<p>A draft of version $next, started from version ${draft.version}. Version ${draft.version} stays in use until you publish; sessions keep the version they were created with.</p>"
    val result = outcome?.let { """<div class="notice${if (it.problem) " danger" else ""}" role="status">${escape(it.message)}</div>""" }.orEmpty()
    if (clusters.isEmpty()) return intro + "<div class='empty'><strong>No cluster is connected.</strong> Connect one on <a href=\"/dashboard/cloud\">Cloud sessions</a> first.</div>"
    // Enter in a field checks the draft: the first submit button of a form is the one implicit submission presses.
    val form = """<form id="profile-form" action="/dashboard/cloud/profiles/draft" method="post">${csrf(actor)}""" +
        """<button name="intent" value="check" tabindex="-1" aria-hidden="true" style="position:absolute;left:-9999px">Check draft</button>""" +
        (choices?.let { """<input type="hidden" name="choicesCluster" value="${escape(it.cluster.id)}">""" }.orEmpty()) +
        (draft.id?.let { """<input type="hidden" name="id" value="${escape(it)}">""" }.orEmpty()) + """<input type="hidden" name="version" value="${draft.version}">""" +
        """<fieldset><legend>Profile</legend><label>Name <input name="name" maxlength="120" required value="${escape(draft.name)}"></label></fieldset>""" +
        placementFields(spec, clusters, choices) + imageFields(spec, choices, images) + resourceFields(spec) + agentFields(spec) +
        environmentFields(spec.environment) + memberFields(draft.members, members) +
        """<button name="intent" value="check">Check draft</button> <button name="intent" value="publish">Publish ${if (draft.id == null) "version 1" else "version $next"}</button>""" +
        """</form><script src="/dashboard/assets/profile-editor.js"></script>"""
    return intro + result + form
}

private fun placementFields(spec: CloudProfileSpec, clusters: List<CloudCluster>, choices: ClusterChoices?): String {
    val clusterOptions = clusters.joinToString("") { """<option value="${escape(it.id)}"${if (it.id == spec.clusterId) " selected" else ""}>${escape(it.name)}</option>""" }
    val namespaceOptions = choices?.namespaces.orEmpty().joinToString("") { choice ->
        val selected = choice.name == choices?.namespace
        val missing = choice.status == NamespaceStatus.MISSING
        """<option value="${escape(choice.name)}"${if (selected) " selected" else ""}${if (missing && !selected) " disabled" else ""}>${escape(choice.name)}${if (missing) " (not found on the cluster)" else ""}</option>"""
    }
    val namespaceHint = when {
        choices == null -> ""
        choices.namespaces.any { it.name == choices.namespace && it.status == NamespaceStatus.MISSING } ->
            hint("<strong>${escape(choices.namespace)} does not exist on the cluster.</strong> Create it, or choose another allowed namespace.")
        choices.namespaces.all { it.status == NamespaceStatus.UNKNOWN } ->
            hint("The cluster's allowed namespaces. TeamCode cannot check that they exist without the optional <code>get</code> right on <code>namespaces</code>.")
        else -> hint("The cluster's allowed namespaces that exist on it.")
    }
    return """<fieldset><legend>Where sessions run</legend><div class="fields">""" +
        """<label>Cluster <select name="clusterId" id="profile-cluster" required>$clusterOptions</select>${hint("A connected cluster. Changing it reloads the lists below from its API.")}</label>""" +
        """<label>Namespace <select name="namespace" id="profile-namespace" required>$namespaceOptions</select>$namespaceHint</label>""" +
        """</div><button name="intent" value="reload" formnovalidate id="profile-reload">Reload choices from the cluster</button>""" +
        storageClassField(spec, choices) + "</fieldset>"
}

private fun storageClassField(spec: CloudProfileSpec, choices: ClusterChoices?): String {
    val listing = choices?.storageClasses
    val classes = (listing as? ClusterListing.Listed)?.items.orEmpty()
    if (classes.isEmpty()) {
        val reason = when (listing) {
            ClusterListing.Forbidden -> "The service account cannot list storage classes; type the name."
            is ClusterListing.Listed -> "The cluster returned no storage class; type the name."
            else -> "The cluster could not be read; type the name."
        }
        return """<label>Workspace storage class (empty uses the cluster's default) <input name="storageClass" maxlength="253" value="${escape(spec.storageClass.orEmpty())}">${hint(reason)}</label>"""
    }
    val recordDefault = choices!!.cluster.defaultStorageClass
    val effectiveDefault = recordDefault ?: classes.firstOrNull { it.isDefault }?.name
    val defaultLabel = when {
        recordDefault != null -> "Cluster default: $recordDefault"
        effectiveDefault != null -> "Kubernetes default: $effectiveDefault"
        else -> "Kubernetes default (no class is marked default)"
    }
    fun label(choice: StorageClassChoice) = choice.name + (if (choice.isDefault) " (default)" else "") + " · reclaimPolicy ${choice.reclaimPolicy}" +
        (choice.provisioner?.let { " · $it" } ?: "")
    val unknown = spec.storageClass?.takeIf { name -> classes.none { it.name == name } }
    val options = """<option value="" data-class="${escape(effectiveDefault.orEmpty())}"${if (spec.storageClass == null) " selected" else ""}>${escape(defaultLabel)}</option>""" +
        classes.joinToString("") { """<option value="${escape(it.name)}" data-class="${escape(it.name)}"${if (it.name == spec.storageClass) " selected" else ""}>${escape(label(it))}</option>""" } +
        (unknown?.let { """<option value="${escape(it)}" selected>${escape(it)} (not found on the cluster)</option>""" }.orEmpty())
    val selected = spec.storageClass ?: effectiveDefault
    val warnings = classes.filter { it.keepsVolumes }.joinToString("") { choice ->
        """<div class="notice warn" data-storage-warning="${escape(choice.name)}"${if (choice.name == selected) "" else " hidden"}><strong>${escape(choice.name)} has reclaimPolicy ${escape(choice.reclaimPolicy)}.</strong>""" +
            """<p>Deleting a session deletes its workspace claim, but TeamCode never deletes a PersistentVolume, so with this policy every deleted workspace leaves its volume and the provider's disk behind, and they accumulate. Publishing a profile on this class is refused: choose a class with reclaimPolicy Delete.</p></div>"""
    }
    return """<label>Workspace storage class <select name="storageClass" id="profile-storage-class">$options</select>${hint("Storage classes of the cluster; the default is marked.")}</label>$warnings""" +
        (unknown?.let { "<div class=\"notice warn\"><strong>Storage class ${escape(it)} is not on the cluster.</strong><p>Publishing is refused until it exists or another class is chosen.</p></div>" }.orEmpty())
}

private fun imageFields(spec: CloudProfileSpec, choices: ClusterChoices?, images: List<String>): String {
    val known = (images + spec.image).filter { it.isNotEmpty() }.distinct().sorted()
    val listing = choices?.imagePullSecrets
    val listed = (listing as? ClusterListing.Listed)?.items.orEmpty()
    val namespace = choices?.namespace?.let(::escape) ?: "the namespace"
    val secrets = if (listed.isNotEmpty()) {
        val missing = spec.imagePullSecrets.filter { it !in listed }
        "<p class=\"card-title\">Image pull secrets</p>" + hint("The <code>kubernetes.io/dockerconfigjson</code> secrets of $namespace, by name. TeamCode reads their names only, never their contents.") +
            (listed.map { it to false } + missing.map { it to true }).joinToString("") { (name, absent) ->
                """<label class="choice"><input type="checkbox" name="imagePullSecret" value="${escape(name)}"${if (name in spec.imagePullSecrets) " checked" else ""}><span><code>${escape(name)}</code>${if (absent) " <span class=\"chip warn\">not found in $namespace</span>" else ""}</span></label>"""
            } + """<label>Other secrets (comma-separated) <input name="imagePullSecretsOther" maxlength="2048"></label>"""
    } else {
        val reason = when (listing) {
            ClusterListing.Forbidden -> "The service account may not list secrets in $namespace, so type their names. Grant the optional <code>list</code> right on <code>secrets</code> to choose them here."
            is ClusterListing.Listed -> "No <code>kubernetes.io/dockerconfigjson</code> secret in $namespace; type the names of the secrets to use."
            else -> "The cluster could not be read; type the secret names."
        }
        """<label>Image pull secrets (comma-separated) <input name="imagePullSecretsOther" maxlength="2048" value="${escape(spec.imagePullSecrets.joinToString(", "))}">${hint(reason)}</label>"""
    }
    return """<fieldset><legend>Image</legend><label>Image <input name="image" maxlength="512" required list="profile-images" placeholder="registry.example.com/toolchain@sha256:..." value="${escape(spec.image)}">""" +
        hint("Pin it by digest. The list holds the images of the other profiles.") + "</label>" +
        """<datalist id="profile-images">${known.joinToString("") { "<option value=\"${escape(it)}\">" }}</datalist>""" +
        """<label>Images a session may ask for instead (one per line) <textarea name="allowedImageOverrides" rows="3" cols="70">${escape(spec.allowedImageOverrides.joinToString("\n"))}</textarea></label>""" +
        secrets + "</fieldset>"
}

private fun resourceFields(spec: CloudProfileSpec) = """<fieldset><legend>Resources</legend><div class="fields">""" +
    """<label>CPU for the agent ${presetSelect("cpuMillis", CPU_PRESETS, spec.resources.cpuMillis, ::cpuLabel)}</label>""" +
    """<label>Memory for the agent ${presetSelect("memoryMebibytes", MEMORY_PRESETS, spec.resources.memoryMebibytes, ::memoryLabel)}</label>""" +
    """<label>Workspace size ${presetSelect("workspaceSizeGibibytes", WORKSPACE_PRESETS, spec.workspaceSizeGibibytes) { "$it GiB" }}</label>""" +
    "</div>" + hint("Requests equal limits. The runner container adds 0.25 CPU and 256 MiB.") + "</fieldset>"

private fun agentFields(spec: CloudProfileSpec) = """<fieldset><legend>Agent and idle pause</legend><div class="fields">""" +
    """<label>Agent <select name="agentKind" required>${listOf("claude" to "Claude Code", "codex" to "Codex").joinToString("") { (value, label) -> """<option value="$value"${if (value == spec.agentKind) " selected" else ""}>$label</option>""" }}</select></label>""" +
    """<label>Agent version (empty installs the latest) <input name="agentVersion" maxlength="64" pattern="[0-9A-Za-z.+\-]{1,64}" value="${escape(spec.agentVersion.orEmpty())}"></label>""" +
    """<label>Idle pause after ${presetSelect("idleTimeoutMinutes", IDLE_PRESETS, spec.idleTimeoutMinutes, ::minutesLabel)}</label>""" +
    """<label>Shortest idle pause a session may ask for ${presetSelect("minIdleTimeoutMinutes", IDLE_PRESETS, spec.minIdleTimeoutMinutes, ::minutesLabel)}</label>""" +
    """<label>Longest idle pause a session may ask for ${presetSelect("maxIdleTimeoutMinutes", IDLE_PRESETS, spec.maxIdleTimeoutMinutes, ::minutesLabel)}</label>""" +
    "</div></fieldset>"

private fun environmentFields(environment: CloudEnvironmentSpec): String {
    val rows = (environment.repositories + List((CloudEnvironmentRules.MAX_REPOSITORIES - environment.repositories.size).coerceIn(0, 2)) { null }).joinToString("") { repository ->
        """<tr><td><input name="repositoryUrl" maxlength="512" placeholder="https://github.com/org/repo.git" value="${escape(repository?.url.orEmpty())}"></td>""" +
            """<td><input name="repositoryPath" maxlength="255" placeholder="repo" value="${escape(repository?.path.orEmpty())}"></td>""" +
            """<td><input name="repositoryRef" maxlength="200" placeholder="default branch" value="${escape(repository?.ref.orEmpty())}"></td>""" +
            """<td><input name="repositoryBranch" maxlength="200" value="${escape(repository?.branch.orEmpty())}"></td></tr>"""
    }
    fun commands(name: String, values: List<String>) = (values + List(if (values.size < CloudEnvironmentRules.MAX_COMMANDS) 1 else 0) { "" }).joinToString("") {
        """<textarea name="$name" rows="2" cols="70" maxlength="${CloudEnvironmentRules.MAX_COMMAND_LENGTH}">${escape(it)}</textarea>"""
    }
    val paths = environment.repositories.map { it.path }.distinct()
    return """<fieldset><legend>Environment recipe</legend>""" +
        hint("Prepared in each new workspace. Leave a row empty to drop it; reloading the choices adds empty rows.") +
        """<div class="table-wrap"><table><thead><tr><th>Clone URL</th><th>Directory</th><th>Ref</th><th>New branch</th></tr></thead><tbody>$rows</tbody></table></div>""" +
        """<label>Repository hosts (comma-separated) <input name="repositoryHosts" maxlength="2048" value="${escape(environment.repositoryHosts.joinToString(", "))}">${hint("Clone URLs may only point at these hosts.")}</label>""" +
        """<label>Working directory (empty is the workspace root) <input name="workingDirectory" maxlength="255" list="profile-paths" value="${escape(environment.workingDirectory.orEmpty())}"></label>""" +
        """<datalist id="profile-paths">${paths.joinToString("") { "<option value=\"${escape(it)}\">" }}</datalist>""" +
        """<p class="card-title">Setup commands run once per workspace</p>${commands("setupCreate", environment.setup.create)}""" +
        """<p class="card-title">Setup commands run on every start</p>${commands("setupStart", environment.setup.start)}""" +
        """<label>Required variables (comma-separated) <input name="requiredVariables" maxlength="4096" value="${escape(environment.requiredVariables.joinToString(", "))}">${hint("Environment variables the owner's variants must assign, in UPPER_SNAKE_CASE.")}</label>""" +
        """<label><input type="checkbox" name="requireGitHub"${if (environment.requireGitHub) " checked" else ""}> The owner must have GitHub access</label>""" +
        """<label>Git email domain for owners without GitHub <input name="gitEmailDomain" maxlength="253" value="${escape(environment.gitEmailDomain.orEmpty())}"></label></fieldset>"""
}

private fun memberFields(assigned: Set<String>, members: List<Member>): String {
    val shown = members.filter { !it.disabled || it.id in assigned }.sortedBy { it.username.lowercase() }
    // An assignment the list cannot show is kept as it is, never dropped by a new version.
    val kept = assigned.filter { id -> shown.none { it.id == id } }
    return """<fieldset><legend>Members</legend>""" + hint("Members who may create sessions from this profile. Administrators may use every profile.") +
        shown.joinToString("") { member ->
            """<label class="choice"><input type="checkbox" name="member" value="${escape(member.id)}"${if (member.id in assigned) " checked" else ""}><span>${escape(member.username)} <span class="chip">${escape(member.role.name.lowercase())}</span>${if (member.disabled) " <span class=\"chip danger\">disabled</span>" else ""}</span></label>"""
        } + kept.joinToString("") { """<input type="hidden" name="member" value="${escape(it)}">""" } + "</fieldset>"
}
