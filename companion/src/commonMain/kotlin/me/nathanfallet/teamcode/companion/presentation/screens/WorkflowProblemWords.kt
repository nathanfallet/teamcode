package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.runtime.Composable
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.ProblemRule
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowProblem
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A refusal of the definition's rules in the member's words (the arguments are the names and limits [ProblemRule] documents). */
@Composable
fun WorkflowProblem.words(): String {
    val resource: StringResource = when (rule) {
        ProblemRule.NAME -> Res.string.wfp_name
        ProblemRule.DESCRIPTION -> Res.string.wfp_description
        ProblemRule.LIMITS -> Res.string.wfp_limits
        ProblemRule.NO_NODES -> Res.string.wfp_no_nodes
        ProblemRule.TOO_MANY_NODES -> Res.string.wfp_too_many_nodes
        ProblemRule.TOO_MANY_INPUTS -> Res.string.wfp_too_many_inputs
        ProblemRule.INPUT_ID -> Res.string.wfp_input_id
        ProblemRule.INPUT_LABEL -> Res.string.wfp_input_label
        ProblemRule.INPUT_MAX_LENGTH -> Res.string.wfp_input_max_length
        ProblemRule.INPUT_DEFAULT_LONG -> Res.string.wfp_input_default_long
        ProblemRule.INPUT_DUPLICATE -> Res.string.wfp_input_duplicate
        ProblemRule.NODE_ID -> Res.string.wfp_node_id
        ProblemRule.NODE_DUPLICATE -> Res.string.wfp_node_duplicate
        ProblemRule.START_MISSING -> Res.string.wfp_start_missing
        ProblemRule.START_HUMAN -> Res.string.wfp_start_human
        ProblemRule.NOT_REACHABLE -> Res.string.wfp_not_reachable
        ProblemRule.NO_ENDING -> Res.string.wfp_no_ending
        ProblemRule.TITLE -> Res.string.wfp_title
        ProblemRule.INSTRUCTION_LONG -> Res.string.wfp_instruction_long
        ProblemRule.MAX_VISITS -> Res.string.wfp_max_visits
        ProblemRule.TEMPLATE_UNKNOWN -> Res.string.wfp_template_unknown
        ProblemRule.AGENT_INSTRUCTION -> Res.string.wfp_agent_instruction
        ProblemRule.DELIVERABLE_MISSING -> Res.string.wfp_deliverable_missing
        ProblemRule.DELIVERABLE_LABEL -> Res.string.wfp_deliverable_label
        ProblemRule.DELIVERABLE_DOMAIN -> Res.string.wfp_deliverable_domain
        ProblemRule.DELIVERABLE_SCHEMA_ONLY_JSON -> Res.string.wfp_deliverable_schema_only_json
        ProblemRule.DELIVERABLE_SCHEMA_MISSING -> Res.string.wfp_deliverable_schema_missing
        ProblemRule.DELIVERABLE_SCHEMA_INVALID -> Res.string.wfp_deliverable_schema_invalid
        ProblemRule.ASSIGNEE_MEMBER -> Res.string.wfp_assignee_member
        ProblemRule.FIELDS_TOO_MANY -> Res.string.wfp_fields_too_many
        ProblemRule.FIELD_ID -> Res.string.wfp_field_id
        ProblemRule.FIELD_DUPLICATE -> Res.string.wfp_field_duplicate
        ProblemRule.FIELD_LABEL -> Res.string.wfp_field_label
        ProblemRule.FIELD_HELP -> Res.string.wfp_field_help
        ProblemRule.FIELD_SECRET -> Res.string.wfp_field_secret
        ProblemRule.FIELD_MAX_LENGTH -> Res.string.wfp_field_max_length
        ProblemRule.FIELD_OPTIONS -> Res.string.wfp_field_options
        ProblemRule.FIELD_OPTION_VALUE -> Res.string.wfp_field_option_value
        ProblemRule.FIELD_OPTION_DUPLICATE -> Res.string.wfp_field_option_duplicate
        ProblemRule.FIELD_INFO_VALUE -> Res.string.wfp_field_info_value
        ProblemRule.FIELD_DEFAULT -> Res.string.wfp_field_default
        ProblemRule.ACTIONS_TOO_MANY -> Res.string.wfp_actions_too_many
        ProblemRule.ACTION_ID -> Res.string.wfp_action_id
        ProblemRule.ACTION_DUPLICATE -> Res.string.wfp_action_duplicate
        ProblemRule.ACTION_LABEL -> Res.string.wfp_action_label
        ProblemRule.ACTION_REQUIRES -> Res.string.wfp_action_requires
        ProblemRule.CALLEE_MISSING -> Res.string.wfp_callee_missing
        ProblemRule.CALLEE_UNKNOWN -> Res.string.wfp_callee_unknown
        ProblemRule.CALLEE_SELF -> Res.string.wfp_callee_self
        ProblemRule.CALLEE_LOOP -> Res.string.wfp_callee_loop
        ProblemRule.CALLEE_DEPTH -> Res.string.wfp_callee_depth
        ProblemRule.ARGUMENT_NAME -> Res.string.wfp_argument_name
        ProblemRule.ARGUMENT_MISSING -> Res.string.wfp_argument_missing
        ProblemRule.ARGUMENT_UNKNOWN -> Res.string.wfp_argument_unknown
        ProblemRule.TRANSITION_DUPLICATE -> Res.string.wfp_transition_duplicate
        ProblemRule.TRANSITION_OUTCOME -> Res.string.wfp_transition_outcome
        ProblemRule.TRANSITION_TARGET -> Res.string.wfp_transition_target
        ProblemRule.TRANSITION_MISSING -> Res.string.wfp_transition_missing
    }
    return stringResource(resource, *args.toTypedArray())
}
