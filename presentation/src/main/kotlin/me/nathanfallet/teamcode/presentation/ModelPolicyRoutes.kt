package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.ModelPolicyView
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.domain.team.*

private fun ModelPolicy.view() = ModelPolicyView(allowedModels, deniedModels, allowedEfforts, maximumThinkingTokens, experimentalCrossProviderTranslation)
internal fun Route.modelPolicyRoutes(policies: ModelPolicyStore, origin: String) {
    get("/api/v1/policy/models") { call.principal.requireReady(); call.respond(policies.get(call.principal.teamId).view()) }
    put("/api/v1/policy/models") {
        val input = call.jsonBody<ModelPolicyView>()
        call.respond(policies.save(call.principal, ModelPolicy(input.allowedModels, input.deniedModels, input.allowedEfforts, input.maximumThinkingTokens, input.experimentalCrossProviderTranslation)).view())
    }
    get("/dashboard/models") {
        val actor = call.principal
        actor.requireAdmin()
        val policy = policies.get(actor.teamId)
        call.respondText(call.page("Model and effort policy", """<details class='help'><summary>How this works</summary><p>Denied patterns win over allowed patterns. Every request is checked before provider dispatch, including its final model after routing. Forbidden requests return an explicit error; no silent effort substitution occurs.</p></details><p><code>*fable*</code> blocks Fable variants. Patterns are case-insensitive and support <code>*</code>. The <code>provider-default</code> effort permits an unspecified provider default; remove it to require an explicit allowed effort. Budget-based thinking is separately gated by <code>budgeted</code> and the ceiling below.</p><form method="post">${csrf(actor)}<label>Allowed models (comma separated) <input name="allowed" value="${escape(policy.allowedModels.joinToString(","))}" size="60" required></label><label>Denied models <input name="denied" value="${escape(policy.deniedModels.joinToString(","))}" size="60"></label><label>Allowed efforts <input name="efforts" value="${escape(policy.allowedEfforts.joinToString(","))}" size="60" required></label><label>Maximum explicit thinking tokens <input type="number" name="thinking" min="0" max="65536" value="${policy.maximumThinkingTokens}" required></label><label><input type="checkbox" name="experimentalTranslation" ${if (policy.experimentalCrossProviderTranslation) "checked" else ""}> Enable experimental cross-provider translation</label><p>Disabled by default. When enabled, compatible Claude/Codex requests may use configured cross-provider routes. Signed/encrypted reasoning and some agent features remain unsupported. Native pools and native-provider fallback work independently of this experiment.</p><button>Save policy</button></form>"""), ContentType.Text.Html)
    }
    post("/dashboard/models") {
        val input = call.form(origin.trimEnd('/'))
        fun list(name: String) = input[name].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        policies.save(call.principal, ModelPolicy(list("allowed"), list("denied"), list("efforts"), input["thinking"]?.toLongOrNull() ?: -1, input["experimentalTranslation"] == "on"))
        call.respondRedirect("/dashboard/models")
    }
}
