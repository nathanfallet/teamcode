package me.nathanfallet.teamcode.companion.presentation.navigation

import kotlinx.serialization.Serializable

/** Enter a server origin and check it is a TeamCode server. */
@Serializable
object AddServerRoute

/** Password sign-in on [origin]; [username] is set when an existing account must sign in again. */
@Serializable
data class LoginRoute(val origin: String, val username: String? = null)

/** The tabs of a signed-in app: Sessions, Waits, Automations and Workflows (when the server has them), Artifacts, Account. */
@Serializable
object MainRoute

/** One cloud session of an account: header and live transcript. */
@Serializable
data class SessionRoute(val accountId: String, val sessionId: String)

/** The new-session sheet's account (a sheet over the Sessions tab, not a destination of its own). */
@Serializable
data class NewSessionRoute(val accountId: String, val directory: String? = null)

/** An account's artifact gallery; [sessionId] keeps those one cloud session published. */
@Serializable
data class ArtifactsRoute(val accountId: String, val sessionId: String? = null)

/** One artifact of an account, at [version] (the current one when null). */
@Serializable
data class ArtifactRoute(val accountId: String, val artifactId: String, val version: Int? = null)

/** The transfer sheet's session (a sheet over the session, not a destination of its own). */
@Serializable
data class TransferRoute(val accountId: String, val sessionId: String, val ownerId: String)

/** An account's incoming and outgoing session transfers. */
@Serializable
data class TransfersRoute(val accountId: String)

/** The subscription accounts of an account's server, and adding one with a provider sign-in. */
@Serializable
data class SubscriptionAccountsRoute(val accountId: String)

/** The member's GitHub link on an account's server: which account is linked, and linking with GitHub's device flow. */
@Serializable
data class GitHubLinkRoute(val accountId: String)

/** The MCP servers assigned to the member on an account's server, with their state (read-only). */
@Serializable
data class McpServersRoute(val accountId: String)

/** An account's automations: the Automations tab of a phone, and a screen of the wide layout's content panel. */
@Serializable
data class AutomationsRoute(val accountId: String)

/** An account's waits ("Waiting for you"): the Waits tab of a phone, and a screen of the wide layout's content panel. */
@Serializable
data class WaitsRoute(val accountId: String)

/** One automation: its definition, webhook URL and history. */
@Serializable
data class AutomationRoute(val accountId: String, val automationId: String)

/** The automation form: a new one, or [automationId] changed. */
@Serializable
data class AutomationEditRoute(val accountId: String, val automationId: String? = null)

/** The test of one automation: payload, preview of the prompt, a real run. */
@Serializable
data class AutomationTestRoute(val accountId: String, val automationId: String)

/** An account's office: its sessions as agents at desks, a screen of the wide layout's content panel only. */
@Serializable
data class OfficeRoute(val accountId: String)

/** An account's workflows: the Workflows tab of a phone (the runs, then the saved workflows), and a screen of the wide layout's content panel. */
@Serializable
data class WorkflowsRoute(val accountId: String)

/** One saved workflow, read-only: its inputs, and its steps with their transitions. */
@Serializable
data class WorkflowRoute(val accountId: String, val workflowId: String)

/**
 * The definition editor: a new workflow, [workflowId] changed, or a new one that starts as a copy of [copyOf] (its name ends with
 * [copySuffix], after a space). The unsaved edits are kept on the device under the workflow's id (or under "new").
 */
@Serializable
data class WorkflowEditRoute(val accountId: String, val workflowId: String? = null, val copyOf: String? = null, val copySuffix: String = "(copy)")

/** The form that starts a run of [workflowId]: its inputs and, when needed, a profile. */
@Serializable
data class WorkflowStartRoute(val accountId: String, val workflowId: String)

/** One run: its timeline of visits, the session and the wait it is blocked on, and its failure. */
@Serializable
data class WorkflowRunRoute(val accountId: String, val runId: String)

/** The content panel of the wide layout: the active account's full session list, with its filters. */
@Serializable
object SessionsListRoute

/** The content panel of the wide layout: the account and the app's settings (the Account tab of a phone). */
@Serializable
object AccountRoute
