package me.nathanfallet.teamcode.domain.workflows

import me.nathanfallet.teamcode.domain.cloud.CloudConversationRules

/** `{{inputs.x}}` and `{{nodes.id.output}}` replaced by plain text; nothing else is interpreted, and an unknown name renders empty. */
object WorkflowTemplate {
    private val placeholder = Regex("\\{\\{\\s*([^{}]*?)\\s*\\}\\}")
    private const val TRUNCATED = "\n[truncated]"

    /** [outputs]: the output of the latest finished visit of each node. */
    fun render(text: String, inputs: Map<String, String>, outputs: Map<String, String>): String {
        val rendered = placeholder.replace(text) { match ->
            val parts = match.groupValues[1].split('.')
            val value = when {
                parts.size == 2 && parts[0] == "inputs" -> inputs[parts[1]]
                parts.size == 3 && parts[0] == "nodes" && parts[2] == "output" -> outputs[parts[1]]
                else -> null
            }
            Regex.escapeReplacement(value.orEmpty())
        }
        val clean = rendered.filterNot { it.isISOControl() && it != '\n' && it != '\t' }
        return if (clean.length <= CloudConversationRules.MAX_MESSAGE_CHARS) clean else clean.take(CloudConversationRules.MAX_MESSAGE_CHARS - TRUNCATED.length) + TRUNCATED
    }

    fun outputs(visits: List<NodeVisit>): Map<String, String> =
        visits.filter { it.status == VisitStatus.DONE && it.output != null }.groupBy { it.nodeId }.mapValues { it.value.last().output!! }
}
