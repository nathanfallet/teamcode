package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.team.*

internal const val ARTIFACT_SETTINGS_PATH = "/dashboard/artifacts/settings"

private fun ArtifactStorage?.view(inUse: Boolean) = if (this == null) ArtifactStorageView(false, inUse = inUse) else
    ArtifactStorageView(true, endpoint, region, bucket, prefix, pathStyle, accessKeyId, true, serverSideEncryption.wire, verifiedAt, version, updatedAt, inUse)

private fun ArtifactSettings.view(availability: ArtifactAvailability) = ArtifactSettingsView(enabled, whoCanPublish.wire, publicSharing.wire,
    quotas.memberBytes, quotas.teamBytes, quotas.artifactsPerMember, quotas.publishesPerHour,
    retention.personalDays, retention.sharedDays, retention.maxVersionsPerArtifact, version, updatedAt, availability.active, availability.reason)

private fun StorageTestResult.view() = ArtifactStorageTestView(ok, steps.map { ArtifactStorageTestStepView(it.step, it.ok, it.status, it.code) })

private fun encryption(value: String) = ServerSideEncryption.parse(value) ?: throw TeamFailure("invalid_artifact_storage", 400, "Server-side encryption is none or AES256.")

private fun SaveArtifactStorageRequest.input() = ArtifactStorageInput(endpoint.trim(), region.trim(), bucket.trim(), prefix.trim(), pathStyle, accessKeyId.trim(),
    secretAccessKey?.trim()?.takeIf { it.isNotEmpty() }, encryption(serverSideEncryption))

private fun SaveArtifactSettingsRequest.settings() = ArtifactSettings(enabled,
    WhoCanPublish.parse(whoCanPublish) ?: throw TeamFailure("invalid_artifact_settings", 400, "Who can publish is everyone or admins."),
    PublicSharing.parse(publicSharing) ?: throw TeamFailure("invalid_artifact_settings", 400, "Public sharing is owners_of_artifacts, admins or off."),
    ArtifactQuotas(quotaMemberBytes, quotaTeamBytes, quotaArtifactsPerMember, quotaPublishesPerHour),
    ArtifactRetention(personalRetentionDays, sharedRetentionDays, maxVersionsPerArtifact), version)

/** The artifact bucket and settings: interactive administrators only, rechecked by the service on every call. */
internal fun Route.artifactSettingsRoutes(artifacts: ArtifactAdministrationService, publicOrigin: String) {
    val origin = publicOrigin.trimEnd('/')
    route("/api/v1/artifacts") {
        get("/storage") { val overview = artifacts.overview(call.principal); call.respond(overview.storage.view(overview.inUse)) }
        put("/storage") {
            val input = call.jsonBody<SaveArtifactStorageRequest>(8_192)
            val actor = call.principal
            call.respond(artifacts.saveStorage(actor, input.version, input.input()).view(artifacts.inUse(actor)))
        }
        delete("/storage") { artifacts.deleteStorage(call.principal); call.respond(HttpStatusCode.NoContent) }
        post("/storage/test") { call.respond(artifacts.testStorage(call.principal).view()) }
        get("/settings") { val overview = artifacts.overview(call.principal); call.respond(overview.settings.view(overview.availability)) }
        put("/settings") {
            val input = call.jsonBody<SaveArtifactSettingsRequest>(4_096)
            val actor = call.principal
            val saved = artifacts.saveSettings(actor, input.settings())
            call.respond(saved.view(artifacts.availability(actor)))
        }
    }

    suspend fun ApplicationCall.settingsPage(test: StorageTestResult? = null) {
        val actor = principal
        val overview = artifacts.overview(actor)
        val storage = overview.storage
        val inUse = overview.inUse
        val settings = overview.settings
        val availability = overview.availability
        val state = when (availability.reason) {
            null -> "<div class=\"notice\"><strong>Artifacts are on.</strong> Members publish them from their agents.</div>"
            "artifact_storage_missing" -> "<div class=\"notice\"><strong>Artifacts are off:</strong> configure an S3-compatible bucket below, test it, then turn artifacts on.</div>"
            "artifact_storage_unverified" -> "<div class=\"notice\"><strong>Artifacts are off:</strong> the storage changed or its last test failed. Test the connection below.</div>"
            else -> "<div class=\"notice\"><strong>Artifacts are off.</strong> The storage passed its test; turn artifacts on in the settings below.</div>"
        }
        val testResult = test?.let { result ->
            val rows = result.steps.joinToString("") { s -> "<tr><td>${escape(s.step)}</td><td>${if (s.ok) "OK" else "Failed"}</td><td>${s.status ?: ""}</td><td>${escape(s.code.orEmpty())}</td></tr>" }
            """<h2>Connection test</h2><p>${if (result.ok) "<strong>The bucket works.</strong> You can turn artifacts on." else "<strong>The test failed.</strong> Check the endpoint, bucket, region, path-style setting and the key's permissions on the prefix."}</p><table><thead><tr><th>Step</th><th>Result</th><th>HTTP</th><th>Code</th></tr></thead><tbody>$rows</tbody></table>"""
        } ?: ""
        val keep = if (storage != null) " (stored; leave empty to keep it, or paste a replacement)" else ""
        val fixed = if (inUse) " readonly" else ""
        fun option(value: String, label: String, selected: String) = """<option value="${escape(value)}"${if (value == selected) " selected" else ""}>${escape(label)}</option>"""
        val storageForm = """<h2>Storage</h2>""" + help("Which bucket and which permissions", """<p>Any S3-compatible bucket: AWS S3, OVHcloud Object Storage, Cloudflare R2 or MinIO. The key only needs <code>s3:PutObject</code>, <code>s3:GetObject</code>, <code>s3:ListBucket</code> and <code>s3:DeleteObject</code> on the prefix. Each artifact is stored in its own folder, <code>&lt;prefix&gt;/&lt;team&gt;/artifacts/&lt;id&gt;/</code>. The secret is encrypted with the server's key and never shown again.</p>""") +
            (if (storage != null) """<p>Saved version ${storage.version}, ${if (storage.verifiedAt != null) "tested ${escape(Instant.ofEpochMilli(storage.verifiedAt!!).toString())}" else "<strong>not tested since the last change</strong>"}.${if (inUse) " Artifacts exist in this bucket: the endpoint, bucket and prefix are fixed; the keys can be rotated." else ""}</p>""" else "<div class='empty'><strong>No storage is configured.</strong></div>") +
            """<form action="$ARTIFACT_SETTINGS_PATH/storage" method="post">${csrf(actor)}<input type="hidden" name="version" value="${storage?.version ?: 0}">""" +
            """<label>Endpoint (https) <input name="endpoint" required maxlength="512" placeholder="https://s3.gra.io.cloud.ovh.net" value="${escape(storage?.endpoint.orEmpty())}"$fixed></label>""" +
            """<label>Region <input name="region" required maxlength="64" placeholder="us-east-1, gra, auto" value="${escape(storage?.region.orEmpty())}"></label>""" +
            """<label>Bucket <input name="bucket" required maxlength="63" value="${escape(storage?.bucket.orEmpty())}"$fixed></label>""" +
            """<label>Prefix (optional) <input name="prefix" maxlength="200" placeholder="teamcode" value="${escape(storage?.prefix.orEmpty())}"$fixed></label>""" +
            """<label><input type="checkbox" name="pathStyle"${if (storage?.pathStyle == true) " checked" else ""}> Path-style addressing (MinIO, some OVH endpoints)</label>""" +
            """<label>Access key id <input name="accessKeyId" required maxlength="256" autocomplete="off" value="${escape(storage?.accessKeyId.orEmpty())}"></label>""" +
            """<label>Secret access key$keep <input type="password" name="secretAccessKey" maxlength="512" autocomplete="off"${if (storage == null) " required" else ""}></label>""" +
            """<label>Server-side encryption <select name="serverSideEncryption">${option("none", "None", storage?.serverSideEncryption?.wire ?: "none")}${option("AES256", "AES256 (ignored by R2)", storage?.serverSideEncryption?.wire ?: "none")}</select></label>""" +
            """<button>Save storage</button></form>""" +
            (if (storage != null) """<form action="$ARTIFACT_SETTINGS_PATH/storage/test" method="post">${csrf(actor)}<button>Test the connection</button></form>""" else "") +
            (if (storage != null && !inUse) """<form action="$ARTIFACT_SETTINGS_PATH/storage/delete" method="post">${csrf(actor)}<button>Remove the storage</button></form>""" else "")
        val gib = ArtifactAdministrationService.GIB
        fun days(value: Int?) = value?.toString() ?: ""
        val settingsForm = """<h2>Settings</h2><form action="$ARTIFACT_SETTINGS_PATH/settings" method="post">${csrf(actor)}<input type="hidden" name="version" value="${settings.version}">""" +
            """<label><input type="checkbox" name="enabled"${if (settings.enabled) " checked" else ""}> Artifacts on (requires a storage whose last test passed)</label>""" +
            """<label>Who can publish <select name="whoCanPublish">${option("everyone", "Every member", settings.whoCanPublish.wire)}${option("admins", "Administrators only", settings.whoCanPublish.wire)}</select></label>""" +
            """<label>Public links <select name="publicSharing">${option("owners_of_artifacts", "Owners may make their artifacts public", settings.publicSharing.wire)}${option("admins", "Administrators only", settings.publicSharing.wire)}${option("off", "Off (every public link stops at once)", settings.publicSharing.wire)}</select></label>""" +
            // The exact byte value rides along, so an unchanged field keeps a quota that is not a round number of GiB.
            """<label>Storage per member (GiB) <input name="quotaMemberGib" inputmode="decimal" required value="${formatGib(settings.quotas.memberBytes, gib)}"></label><input type="hidden" name="quotaMemberBytes" value="${settings.quotas.memberBytes}">""" +
            """<label>Storage for the team (GiB) <input name="quotaTeamGib" inputmode="decimal" required value="${formatGib(settings.quotas.teamBytes, gib)}"></label><input type="hidden" name="quotaTeamBytes" value="${settings.quotas.teamBytes}">""" +
            """<label>Artifacts per member <input name="quotaArtifactsPerMember" inputmode="numeric" required value="${settings.quotas.artifactsPerMember}"></label>""" +
            """<label>Publications per member and hour <input name="quotaPublishesPerHour" inputmode="numeric" required value="${settings.quotas.publishesPerHour}"></label>""" +
            """<label>Delete personal artifacts after (days, empty to keep) <input name="personalRetentionDays" inputmode="numeric" value="${days(settings.retention.personalDays)}"></label>""" +
            """<label>Delete shared artifacts after (days, empty to keep) <input name="sharedRetentionDays" inputmode="numeric" value="${days(settings.retention.sharedDays)}"></label>""" +
            """<label>Versions kept per artifact <input name="maxVersionsPerArtifact" inputmode="numeric" required value="${settings.retention.maxVersionsPerArtifact}"></label>""" +
            """<button>Save settings</button></form>"""
        val report = artifacts.usage(actor)
        val members = report.members
        val used = report.teamBytes
        val usage = """<h2>Usage</h2><p>${byteSize(used)} of ${byteSize(settings.quotas.teamBytes)} stored for the team, counting each file once per artifact; deleted artifacts are not counted.</p>""" +
            (if (members.isEmpty()) "<div class='empty'><strong>No artifact is stored.</strong></div>"
            else """<table><thead><tr><th>Member</th><th class="num">Artifacts</th><th class="num">Stored</th><th class="num">Of the member quota</th></tr></thead><tbody>""" +
                members.joinToString("") { row ->
                    val share = if (settings.quotas.memberBytes > 0) row.bytes * 100 / settings.quotas.memberBytes else 0
                    """<tr><td>${escape(row.username.ifEmpty { row.memberId })}</td><td class="num">${row.artifacts}</td><td class="num">${byteSize(row.bytes)}</td><td class="num">$share %</td></tr>"""
                } + "</tbody></table>" +
                (if (members.size >= ArtifactAdministrationService.MAX_USAGE_ROWS) "<p><small>The ${ArtifactAdministrationService.MAX_USAGE_ROWS} largest owners are shown.</small></p>" else ""))
        respondText(page("Artifacts", state + testResult + usage + storageForm + settingsForm), ContentType.Text.Html)
    }

    get(ARTIFACT_SETTINGS_PATH) { call.settingsPage() }
    post("$ARTIFACT_SETTINGS_PATH/storage") {
        val input = call.form(origin)
        val actor = call.principal
        artifacts.saveStorage(actor, input["version"]?.toIntOrNull() ?: throw TeamFailure("invalid_artifact_storage", 400, "Reload the page."),
            ArtifactStorageInput(input["endpoint"].orEmpty().trim(), input["region"].orEmpty().trim(), input["bucket"].orEmpty().trim(), input["prefix"].orEmpty().trim(),
                input["pathStyle"] == "on", input["accessKeyId"].orEmpty().trim(), input["secretAccessKey"]?.trim()?.takeIf { it.isNotEmpty() },
                encryption(input["serverSideEncryption"] ?: "none")))
        call.respondRedirect(ARTIFACT_SETTINGS_PATH)
    }
    post("$ARTIFACT_SETTINGS_PATH/storage/test") { call.form(origin); call.settingsPage(artifacts.testStorage(call.principal)) }
    post("$ARTIFACT_SETTINGS_PATH/storage/delete") { call.form(origin); artifacts.deleteStorage(call.principal); call.respondRedirect(ARTIFACT_SETTINGS_PATH) }
    post("$ARTIFACT_SETTINGS_PATH/settings") {
        val input = call.form(origin)
        val gib = ArtifactAdministrationService.GIB
        fun bytes(name: String, original: String): Long {
            val typed = input[name]?.trim() ?: ""
            input[original]?.toLongOrNull()?.let { if (typed == formatGib(it, gib)) return it }
            return typed.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { Math.round(it * gib) }
                ?: throw TeamFailure("invalid_artifact_settings", 400, "Enter the storage quotas in GiB.")
        }
        fun int(name: String) = input[name]?.trim()?.toIntOrNull() ?: throw TeamFailure("invalid_artifact_settings", 400, "Enter whole numbers for the quotas and versions.")
        fun days(name: String) = input[name]?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: throw TeamFailure("invalid_artifact_settings", 400, "Enter retention in whole days, or leave it empty.") }
        artifacts.saveSettings(call.principal, ArtifactSettings(input["enabled"] == "on",
            WhoCanPublish.parse(input["whoCanPublish"].orEmpty()) ?: throw TeamFailure("invalid_artifact_settings", 400, "Choose who can publish."),
            PublicSharing.parse(input["publicSharing"].orEmpty()) ?: throw TeamFailure("invalid_artifact_settings", 400, "Choose who can make artifacts public."),
            ArtifactQuotas(bytes("quotaMemberGib", "quotaMemberBytes"), bytes("quotaTeamGib", "quotaTeamBytes"), int("quotaArtifactsPerMember"), int("quotaPublishesPerHour")),
            ArtifactRetention(days("personalRetentionDays"), days("sharedRetentionDays"), int("maxVersionsPerArtifact")),
            input["version"]?.toIntOrNull() ?: throw TeamFailure("invalid_artifact_settings", 400, "Reload the page.")))
        call.respondRedirect(ARTIFACT_SETTINGS_PATH)
    }
}

private fun formatGib(bytes: Long, gib: Long): String {
    val value = bytes.toDouble() / gib
    return if (value == Math.floor(value)) value.toLong().toString() else "%.6f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
}
