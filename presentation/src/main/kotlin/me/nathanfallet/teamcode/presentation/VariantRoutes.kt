package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.*

private fun Variant.view() = VariantView(id, kind.name, target, name, binding.name, version, endpoint, config, hasSharedSecret, createdAt, updatedAt)
private fun VariantTarget.view() = VariantTargetView(kind.name, name)
private fun VariantAssignment.view() = VariantAssignmentView(target.kind.name, target.name, variantId, variantName, version, source.name, hasMemberSecret, assignedAt)
private fun MemberVariantAssignments.view() = MemberVariantAssignmentsView(memberId, username, disabled, assignments.map { it.view() }, exclusions.map { it.view() })
private fun BulkAssignment.view() = BulkAssignmentResponse(assigned, keptDirect, excluded)

private fun kind(value: String) = VariantKind.entries.find { it.name == value } ?: throw TeamFailure("invalid_variant", 400, "Use ENV or MCP.")
private fun binding(value: String) = VariantBinding.entries.find { it.name == value } ?: throw TeamFailure("invalid_variant", 400, "Use SHARED or PER_MEMBER.")
/** JSON: an explicit empty string is refused by validation instead of silently keeping the stored value. */
private fun secret(value: String?) = value?.let(::SecretValue)
/** Dashboard forms submit an empty field when nothing was typed, which means no value. */
private fun formSecret(value: String?) = value?.takeIf { it.isNotEmpty() }?.let(::SecretValue)
private fun Parameters.target() = VariantTarget(kind(this["kind"].orEmpty()), this["target"].orEmpty())
private fun Parameters.required(name: String) = this[name]?.takeIf { it.isNotEmpty() } ?: throw TeamFailure("invalid_variant", 400, "Missing $name.")

/** Dashboard configuration is one `key=value` per line; secrets never belong here. */
private fun config(text: String?): Map<String, String> = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.associate { line ->
    val separator = line.indexOf('=')
    if (separator <= 0) throw TeamFailure("invalid_variant", 400, "Write configuration as one key=value per line.")
    line.substring(0, separator).trim() to line.substring(separator + 1).trim()
}

internal fun Route.variantRoutes(variants: VariantService, publicOrigin: String) {
    val origin = publicOrigin.trimEnd('/')
    get("/api/v1/variants") { call.respond(variants.list(call.principal).map { it.view() }) }
    post("/api/v1/variants") {
        val input = call.jsonBody<CreateVariantRequest>()
        val created = variants.create(call.principal, NewVariant(kind(input.kind), input.target, input.name, binding(input.binding), input.endpoint, input.config, secret(input.secret)))
        call.respond(HttpStatusCode.Created, created.view())
    }
    patch("/api/v1/variants/{id}") {
        val input = call.jsonBody<UpdateVariantRequest>()
        call.respond(variants.update(call.principal, call.parameters["id"]!!, VariantUpdate(input.expectedVersion, input.name, input.config, secret(input.secret), input.clearSecret)).view())
    }
    delete("/api/v1/variants/{id}") { variants.delete(call.principal, call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }
    post("/api/v1/variants/{id}/bulk-assign") { call.respond(variants.bulkAssign(call.principal, call.parameters["id"]!!).view()) }
    get("/api/v1/variant-assignments") { call.respond(variants.assignments(call.principal).map { it.view() }) }
    get("/api/v1/members/{memberId}/variants") { call.respond(variants.assignments(call.principal, call.parameters["memberId"]!!).single().view()) }
    put("/api/v1/members/{memberId}/variants") {
        val input = call.jsonBody<AssignVariantRequest>()
        call.respond(variants.assign(call.principal, call.parameters["memberId"]!!, input.variantId, secret(input.memberSecret)).view())
    }
    delete("/api/v1/members/{memberId}/variants/{kind}/{target}") {
        variants.unassign(call.principal, call.parameters["memberId"]!!, call.parameters.target())
        call.respond(HttpStatusCode.NoContent)
    }
    put("/api/v1/members/{memberId}/variant-exclusions") {
        val input = call.jsonBody<VariantTargetView>()
        variants.exclude(call.principal, call.parameters["memberId"]!!, VariantTarget(kind(input.kind), input.target))
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/api/v1/members/{memberId}/variant-exclusions/{kind}/{target}") {
        variants.include(call.principal, call.parameters["memberId"]!!, call.parameters.target())
        call.respond(HttpStatusCode.NoContent)
    }

    get("/dashboard/variants") {
        val actor = call.principal
        val all = variants.list(actor)
        val members = variants.assignments(actor)
        val action = { path: String, label: String, fields: String -> """<form method="post" action="/dashboard/variants/$path">${csrf(actor)}$fields<button>${escape(label)}</button></form>""" }
        val hidden = { name: String, value: String -> """<input type="hidden" name="$name" value="${escape(value)}">""" }
        val rows = all.joinToString("") { variant ->
            val details = listOfNotNull(variant.endpoint, variant.config.entries.joinToString(", ") { "${it.key}=${it.value}" }.takeIf { it.isNotEmpty() },
                if (variant.binding == VariantBinding.SHARED && variant.hasSharedSecret) "shared secret stored" else null,
                if (variant.binding == VariantBinding.PER_MEMBER) "value set per member" else null)
            """<tr><td>${variant.kind}<br><code>${escape(variant.target)}</code></td><td>${escape(variant.name)}<br><small>v${variant.version} · ${variant.binding.name.lowercase()}</small></td><td><small>${escape(details.joinToString(" · "))}</small></td><td>""" +
                (if (variant.binding == VariantBinding.SHARED) action("${escape(variant.id)}/bulk-assign", "Assign to all members", "") else "") +
                action("${escape(variant.id)}/delete", "Delete", "") + "</td></tr>"
        }
        val memberOptions = members.joinToString("") { """<option value="${escape(it.memberId)}">${escape(it.username)}</option>""" }
        val variantOptions = all.joinToString("") { """<option value="${escape(it.id)}">${it.kind} ${escape(it.target)}: ${escape(it.name)}</option>""" }
        val effective = members.joinToString("") { member ->
            val assigned = member.assignments.joinToString("") { assignment ->
                """<div>${assignment.target.kind} <code>${escape(assignment.target.name)}</code> → ${escape(assignment.variantName)} <small>v${assignment.version} · ${assignment.source.name.lowercase()}</small>""" +
                    action("unassign", "Remove", hidden("member", member.memberId) + hidden("kind", assignment.target.kind.name) + hidden("target", assignment.target.name)) + "</div>"
            }
            val excluded = member.exclusions.joinToString("") { target ->
                """<div>${target.kind} <code>${escape(target.name)}</code> excluded""" +
                    action("include", "Remove exclusion", hidden("member", member.memberId) + hidden("kind", target.kind.name) + hidden("target", target.name)) + "</div>"
            }
            """<tr><td>${escape(member.username)}${if (member.disabled) " <small>Disabled: receives nothing</small>" else ""}</td><td>${assigned.ifEmpty { "<small>Nothing assigned</small>" }}</td><td>$excluded</td></tr>"""
        }
        val kinds = """<select name="kind"><option>ENV</option><option>MCP</option></select>"""
        call.respondText(call.page("Environment and MCP variants", """<details class='help'><summary>How this works</summary><p>A member receives an environment variable or an MCP server only through an assignment. Shared variants are never an implicit grant: assign them per member or in bulk. Exclusions win over bulk assignment. Secret values are encrypted, write-only and never shown again. Endpoints and configuration are visible to administrators; keep credentials in the secret field.</p></details>
<table><thead><tr><th>Target</th><th>Variant</th><th>Details</th><th>Manage</th></tr></thead><tbody>$rows</tbody></table>${if (all.isEmpty()) "<div class='empty'><strong>No variants yet.</strong></div>" else ""}
<h2>Effective assignments</h2><table><thead><tr><th>Member</th><th>Receives</th><th>Exclusions</th></tr></thead><tbody>$effective</tbody></table>
<h2>Create a variant</h2><form method="post" action="/dashboard/variants">${csrf(actor)}<label>Kind $kinds</label><label>Target <input name="target" maxlength="128" required placeholder="GITHUB_TOKEN or linear"></label><label>Variant name <input name="name" maxlength="120" required></label><label>Binding <select name="binding"><option>SHARED</option><option>PER_MEMBER</option></select></label><label>MCP endpoint <input name="endpoint" maxlength="2048" placeholder="https://"></label><label>MCP configuration (key=value per line, not secret) <textarea name="config" rows="3"></textarea></label><label>Shared value or credential <input name="secret" type="password" autocomplete="off"></label><button>Create variant</button></form>
<h2>Assign a variant</h2><form method="post" action="/dashboard/variants/assign">${csrf(actor)}<label>Member <select name="member">$memberOptions</select></label><label>Variant <select name="variant">$variantOptions</select></label><label>Member value or delegated credential (per-member variants only) <input name="secret" type="password" autocomplete="off"></label><button>Assign</button></form>
<h2>Exclude a member</h2><form method="post" action="/dashboard/variants/exclude">${csrf(actor)}<label>Member <select name="member">$memberOptions</select></label><label>Kind $kinds</label><label>Target <input name="target" maxlength="128" required></label><button>Exclude</button></form>"""), ContentType.Text.Html)
    }
    post("/dashboard/variants") {
        val input = call.form(origin)
        val endpoint = input["endpoint"]?.takeIf { it.isNotBlank() }
        variants.create(call.principal, NewVariant(kind(input.required("kind")), input.required("target"), input.required("name"), binding(input.required("binding")), endpoint, config(input["config"]), formSecret(input["secret"])))
        call.respondRedirect("/dashboard/variants")
    }
    post("/dashboard/variants/{id}/bulk-assign") { call.form(origin); variants.bulkAssign(call.principal, call.parameters["id"]!!); call.respondRedirect("/dashboard/variants") }
    post("/dashboard/variants/{id}/delete") { call.form(origin); variants.delete(call.principal, call.parameters["id"]!!); call.respondRedirect("/dashboard/variants") }
    post("/dashboard/variants/assign") {
        val input = call.form(origin)
        variants.assign(call.principal, input.required("member"), input.required("variant"), formSecret(input["secret"]))
        call.respondRedirect("/dashboard/variants")
    }
    post("/dashboard/variants/unassign") {
        val input = call.form(origin)
        variants.unassign(call.principal, input.required("member"), input.target())
        call.respondRedirect("/dashboard/variants")
    }
    post("/dashboard/variants/exclude") {
        val input = call.form(origin)
        variants.exclude(call.principal, input.required("member"), input.target())
        call.respondRedirect("/dashboard/variants")
    }
    post("/dashboard/variants/include") {
        val input = call.form(origin)
        variants.include(call.principal, input.required("member"), input.target())
        call.respondRedirect("/dashboard/variants")
    }
}
