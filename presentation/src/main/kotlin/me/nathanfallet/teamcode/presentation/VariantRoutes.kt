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
        val kinds = """<select name="kind"><option value="ENV">Environment variable</option><option value="MCP">MCP server</option></select>"""
        call.respondText(call.page("Environment and MCP variants", """<div class="notice"><strong>What is a variant?</strong>
<p>A variant is a named value that the team shares through TeamCode instead of pasting it into each machine. There are two types: an <strong>environment variable</strong> (a name such as <code>GITHUB_TOKEN</code> and its value) and an <strong>MCP server</strong> (a name such as <code>linear</code>, an HTTPS endpoint and an optional credential). Several variants can exist for the same name, for example one per organisation.</p>
<p><strong>Who receives it.</strong> Nobody, until you assign it: pick a variant for one member, or assign a shared one to every current member at once. A member has at most one variant per name, and an exclusion always wins over an assignment.</p>
<p><strong>Where it reaches a session.</strong> An environment variable is set in the member's cloud sessions each time one is prepared or resumed. An MCP server is offered to the member's agents at <code>/mcp/&lt;name&gt;</code> on this TeamCode server, which calls the real endpoint with the stored credential, so agents never see it. Secret values are encrypted, write-only and never shown again; endpoints and configuration are visible to administrators.</p></div>
<table><thead><tr><th>Target</th><th>Variant</th><th>Details</th><th>Manage</th></tr></thead><tbody>$rows</tbody></table>${if (all.isEmpty()) "<div class='empty'><strong>No variants yet.</strong></div>" else ""}
<h2>Effective assignments</h2><table><thead><tr><th>Member</th><th>Receives</th><th>Exclusions</th></tr></thead><tbody>$effective</tbody></table>
<h2>Create a variant</h2><form method="post" action="/dashboard/variants" class="variant-form">${csrf(actor)}<fieldset><legend>Type</legend><label class="choice"><input type="radio" name="kind" value="ENV" checked><span><strong>Environment variable</strong><span class="hint">A named value set in the member's sessions, such as an API token.</span></span></label><label class="choice"><input type="radio" name="kind" value="MCP"><span><strong>MCP server</strong><span class="hint">A remote tool server that agents reach through TeamCode, such as an issue tracker.</span></span></label></fieldset><label class="only-env">Variable name <input name="env_target" maxlength="128" placeholder="GITHUB_TOKEN" pattern="[A-Z_][A-Z0-9_]*" autocomplete="off"><span class="hint">Uppercase letters, digits and underscores. Names the runtime reserves, such as <code>PATH</code> or <code>ANTHROPIC_*</code>, are refused.</span></label><label class="only-mcp">Server name <input name="mcp_target" maxlength="64" placeholder="linear" pattern="[a-z0-9][a-z0-9._\-]*" autocomplete="off"><span class="hint">Lowercase letters, digits, dots, dashes and underscores. Agents reach it at <code>/mcp/&lt;name&gt;</code>.</span></label><label>Variant name <input name="name" maxlength="120" required placeholder="Acme organisation"><span class="hint">Tells variants for the same name apart when assigning, such as <code>acme</code> and <code>personal</code>.</span></label><fieldset><legend>Who provides the value</legend><label class="choice"><input type="radio" name="binding" value="SHARED" checked><span><strong>One value for everyone assigned</strong><span class="hint only-env">You enter the value once below; every assigned member gets the same one.</span><span class="hint only-mcp">You enter one credential below, if the server needs one; every assigned member uses it.</span></span></label><label class="choice"><input type="radio" name="binding" value="PER_MEMBER"><span><strong>Each member has their own</strong><span class="hint only-env">Nothing is stored here: you enter each member's own value when you assign the variant to them.</span><span class="hint only-mcp">Nothing is stored here: you enter each member's own credential when you assign the variant to them.</span></span></label></fieldset><label class="only-mcp">Endpoint <input name="endpoint" maxlength="2048" placeholder="https://mcp.example.com/mcp" type="url" autocomplete="off"><span class="hint">An HTTPS address, without credentials in it. It cannot be changed later: create another variant to point elsewhere.</span></label><label class="only-mcp">Configuration (optional) <textarea name="config" rows="3" placeholder="workspace=acme&#10;region=eu"></textarea><span class="hint">One <code>key=value</code> per line, up to 32. Visible to administrators: never put a credential here.</span></label><label class="only-env only-shared">Value <input name="secret_env" type="password" autocomplete="off" placeholder="ghp_..."><span class="hint">Required. Encrypted and never shown again; to change it later, use the API.</span></label><label class="only-mcp only-shared">Credential (optional) <input name="secret_mcp" type="password" autocomplete="off"><span class="hint">Sent to the server as a bearer token. Leave empty for a server without authentication.</span></label><button>Create variant</button></form>
<h2>Assign a variant</h2><form method="post" action="/dashboard/variants/assign">${csrf(actor)}<label>Member <select name="member">$memberOptions</select></label><label>Variant <select name="variant">$variantOptions</select></label><label>Member value or delegated credential (per-member variants only) <input name="secret" type="password" autocomplete="off"></label><button>Assign</button></form>
<h2>Exclude a member</h2><form method="post" action="/dashboard/variants/exclude">${csrf(actor)}<label>Member <select name="member">$memberOptions</select></label><label>Type $kinds</label><label>Variable or server name <input name="target" maxlength="128" required placeholder="GITHUB_TOKEN or linear"><span class="hint">The member then receives nothing for this name, and bulk assignment skips them, until you remove the exclusion.</span></label><button>Exclude</button></form>"""), ContentType.Text.Html)
    }
    post("/dashboard/variants") {
        val input = call.form(origin)
        // The form shows the fields of the selected type only, but a browser still submits the hidden ones: read just the selected type's.
        val kind = kind(input.required("kind"))
        val binding = binding(input.required("binding"))
        val mcp = kind == VariantKind.MCP
        val suffix = if (mcp) "mcp" else "env"
        val target = input["${suffix}_target"]?.takeIf { it.isNotEmpty() } ?: input["target"]?.takeIf { it.isNotEmpty() }
            ?: throw TeamFailure("invalid_variant", 400, if (mcp) "Enter the MCP server name." else "Enter the environment variable name.")
        val endpoint = if (mcp) input["endpoint"]?.takeIf { it.isNotBlank() } else null
        // A per-member variant stores no value of its own, so a value left in the hidden field is dropped instead of refused.
        val secret = if (binding == VariantBinding.SHARED) formSecret(input["secret_$suffix"] ?: input["secret"]) else null
        variants.create(call.principal, NewVariant(kind, target, input.required("name"), binding, endpoint, if (mcp) config(input["config"]) else emptyMap(), secret))
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
