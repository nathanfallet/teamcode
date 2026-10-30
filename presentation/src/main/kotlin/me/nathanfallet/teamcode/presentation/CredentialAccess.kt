package me.nathanfallet.teamcode.presentation

import me.nathanfallet.teamcode.domain.mcp.McpGateway
import me.nathanfallet.teamcode.domain.team.CredentialAction

private val cancelPath = Regex("/api/v1/requests/[0-9a-f-]{36}/cancel")
private val skillPublishPath = Regex("/api/v1/skills/[a-z0-9-]{1,64}/versions")
private val skillPinPath = Regex("/api/v1/skills/[a-z0-9-]{1,64}/pin")
private val skillArtifactPath = Regex("/api/v1/skill-delivery/[a-z0-9-]{1,64}/[^/]{1,64}")
private val hookPublishPath = Regex("/api/v1/hooks/[a-z0-9-]{1,64}/versions")
private val hookArtifactPath = Regex("/api/v1/hook-delivery/[a-z0-9-]{1,64}/[^/]{1,64}")
private val teamReadPath = Regex("/api/v1/(quotas|accounts|pools/budgets|pools/size|members/[a-zA-Z0-9-]{1,36}/capacity)")
private const val CLOUD_ID = "[a-zA-Z0-9-]{1,36}"
/** Session, transcript, input and transfer reads open to `cloud:read`; clusters, settings and the access policy are not listed. */
private val cloudReadPath = Regex("/api/v1/cloud/(profiles|files/policy|sessions|sessions/$CLOUD_ID(/executions|/events|/events/stream|/messages|/messages/$CLOUD_ID)?|events/stream|transfers|transfers/$CLOUD_ID)")
private val cloudWritePost = Regex("/api/v1/cloud/(sessions|sessions/$CLOUD_ID/(pause|resume|transfer|messages|files|interrupt|tasks/stop|permissions|attachments|attachments/$CLOUD_ID/renew)|transfers/$CLOUD_ID/(accept|decline|cancel))")
private val webhookRead = Regex("/api/v1/webhooks(/$CLOUD_ID(/deliveries)?)?")
private val webhookWrite = Regex("/api/v1/webhooks(/$CLOUD_ID(/test|/rotate-secret)?)?")
private const val ARTIFACT_ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
/** The publication policy, listing and reading artifacts; the storage and settings routes are not listed (they stay interactive). */
private val artifactRead = Regex("/api/v1/artifacts(/policy|/$ARTIFACT_ID(/versions(/[0-9]{1,9}(/file)?)?|/members)?)?")
/** Publishing and inviting; making public (`/access`) and deleting are not listed, so no key can do either. */
private val artifactWritePost = Regex("/api/v1/artifacts/(uploads|$ARTIFACT_ID/(versions|members))")
private val artifactChunk = Regex("/api/v1/artifacts/uploads/$ARTIFACT_ID/blobs/[0-9a-f]{64}/[0-9]{1,6}")
// Never `.` or `..`: a segment a proxy could collapse into the artifact itself, which no key deletes.
private val artifactUnshare = Regex("/api/v1/artifacts/$ARTIFACT_ID/members/(?!\\.{1,2}$)[A-Za-z0-9._@-]{1,64}")
/** Automation reads, dry runs and draft previews; a real test needs `cloud:write`, which the service checks. Writes of definitions stay interactive. */
private val automationRead = Regex("/api/v1/automations(/$CLOUD_ID(/runs(/$CLOUD_ID)?)?)?")
private val automationReadPost = Regex("/api/v1/automations/(preview|$CLOUD_ID/run)")
/** Waits: reads, and the answer / cancel / creation writes, whose rules the service enforces. */
private val waitRead = Regex("/api/v1/waits(/$CLOUD_ID)?")
private val waitWrite = Regex("/api/v1/waits(/$CLOUD_ID/(answer|cancel))?")
/** Workflow reads only; saving and deleting definitions stay interactive. */
private val workflowRead = Regex("/api/v1/workflows(/(settings|runs|runs/$CLOUD_ID|$CLOUD_ID))?")
/** A run is delivered into with `cloud:write`: the starter's agent acts with their credential. */
private val workflowDeliver = Regex("/api/v1/workflows/runs/$CLOUD_ID/deliver")
/** The agent of a workflow step delivers by session: `cloud:write`, or an execution credential's `workflow:session`, which the run service binds to its session. */
private val workflowSessionDeliver = "/api/v1/workflows/session/deliver"
/** The Stop hook of a workflow step asks whether its session still has to deliver or ask: the same credentials as the delivery. */
private val workflowSessionPending = "/api/v1/workflows/session/pending"
private val automationCancel = Regex("/api/v1/automations/$CLOUD_ID/runs/$CLOUD_ID/cancel")
private val cloudSessionPath = Regex("/api/v1/cloud/sessions/$CLOUD_ID")
private val cloudWriteDelete = Regex("/api/v1/cloud/sessions/$CLOUD_ID(/attachments/$CLOUD_ID)?")

/** Classify transport operations; unrecognized/new endpoints require an interactive login. */
internal fun credentialAction(method: String, path: String, inferencePaths: Set<String>): CredentialAction = when {
    method == "GET" && path == "/api/v1/capabilities" -> CredentialAction.DISCOVERY
    method == "GET" && path == "/api/v1/auth/me" -> CredentialAction.IDENTITY
    method == "POST" && path == "/api/v1/auth/logout" -> CredentialAction.LOGOUT
    method == "POST" && path in inferencePaths -> CredentialAction.INFERENCE
    method == "POST" && cancelPath.matches(path) -> CredentialAction.INFERENCE
    method == "POST" && path.startsWith("$MCP_PATH/") && McpGateway.SERVER_NAME.matches(path.removePrefix("$MCP_PATH/")) -> CredentialAction.MCP
    method == "GET" && path == MCP_SERVERS_PATH -> CredentialAction.MCP
    method == "GET" && path == "/api/v1/mcp/usage" -> CredentialAction.USAGE_READ
    method == "GET" && path == "/api/v1/capacity" -> CredentialAction.USAGE_READ
    // Team-wide reads that an interactive administrator has: only a `team:read` key (an administrator's) passes the service layer.
    method == "GET" && teamReadPath.matches(path) -> CredentialAction.TEAM_READ
    method == "GET" && path == "/api/v1/pools/accounts" -> CredentialAction.USAGE_READ
    method == "GET" && path == "/api/v1/usage/tokens" -> CredentialAction.USAGE_READ
    method == "GET" && path == "/api/v1/policy/api-prices" -> CredentialAction.USAGE_READ
    method == "GET" && (path == "/api/v1/runs" || Regex("/api/v1/runs/[0-9a-f-]{36}").matches(path)) -> CredentialAction.USAGE_READ
    method == "POST" && (path == "/api/v1/runs" || Regex("/api/v1/runs/[0-9a-f-]{36}/(heartbeat|activity|end)").matches(path)) -> CredentialAction.INFERENCE
    method == "GET" && path == "/api/v1/directives/current" -> CredentialAction.GUIDANCE
    method == "POST" && path in setOf("/api/v1/directives/current", "/api/v1/directives/claim", "/api/v1/directives/receipt") -> CredentialAction.GUIDANCE
    method == "POST" && skillPublishPath.matches(path) -> CredentialAction.SKILLS_PUBLISH
    // Pinning shares the publication right; unpinning and the rest of the catalog stay interactive.
    method == "PUT" && skillPinPath.matches(path) -> CredentialAction.SKILLS_PUBLISH
    method == "GET" && path == "/api/v1/skill-delivery" -> CredentialAction.SKILLS_READ
    method == "GET" && skillArtifactPath.matches(path) -> CredentialAction.SKILLS_READ
    // A hook key publishes only: pinning a hook stays with interactive administrators, since it runs code on members' computers.
    method == "POST" && hookPublishPath.matches(path) -> CredentialAction.HOOKS_PUBLISH
    method == "GET" && path == "/api/v1/hook-delivery" -> CredentialAction.HOOKS_READ
    method == "GET" && hookArtifactPath.matches(path) -> CredentialAction.HOOKS_READ
    method == "GET" && cloudReadPath.matches(path) -> CredentialAction.CLOUD_READ
    // Webhooks are team-wide: the service accepts an administrator's login or `admin:cloud` key and refuses the rest.
    method == "GET" && webhookRead.matches(path) -> CredentialAction.CLOUD_READ
    (method == "POST" || method == "PUT" || method == "DELETE") && webhookWrite.matches(path) -> CredentialAction.CLOUD_WRITE
    method == "GET" && automationRead.matches(path) -> CredentialAction.CLOUD_READ
    method == "GET" && workflowRead.matches(path) -> CredentialAction.CLOUD_READ
    method == "POST" && workflowDeliver.matches(path) -> CredentialAction.CLOUD_WRITE
    method == "GET" && path == workflowSessionPending -> CredentialAction.WORKFLOW_DELIVER
    method == "POST" && path == workflowSessionDeliver -> CredentialAction.WORKFLOW_DELIVER
    method == "GET" && waitRead.matches(path) -> CredentialAction.WAITS_READ
    method == "POST" && waitWrite.matches(path) -> CredentialAction.WAITS_WRITE
    method == "POST" && automationReadPost.matches(path) -> CredentialAction.CLOUD_READ
    method == "POST" && automationCancel.matches(path) -> CredentialAction.CLOUD_WRITE
    method == "POST" && cloudWritePost.matches(path) -> CredentialAction.CLOUD_WRITE
    method == "DELETE" && cloudWriteDelete.matches(path) -> CredentialAction.CLOUD_WRITE
    method == "PATCH" && cloudSessionPath.matches(path) -> CredentialAction.CLOUD_WRITE
    method == "GET" && artifactRead.matches(path) -> CredentialAction.ARTIFACTS_READ
    method == "POST" && artifactWritePost.matches(path) -> CredentialAction.ARTIFACTS_WRITE
    method == "PUT" && artifactChunk.matches(path) -> CredentialAction.ARTIFACTS_WRITE
    method == "DELETE" && artifactUnshare.matches(path) -> CredentialAction.ARTIFACTS_WRITE
    else -> CredentialAction.INTERACTIVE
}
