package me.nathanfallet.teamcode.cli

import kotlinx.serialization.SerializationException
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.platform.ProcessStartException
import me.nathanfallet.teamcode.client.OAuthEnrollmentException
import me.nathanfallet.teamcode.client.ReleaseDownloadException
import me.nathanfallet.teamcode.client.TeamCodeException

/**
 * What the CLI prints when a command fails: [message] says what happened and what to do; [diagnostic] (printed only
 * with `--debug` or `TEAMCODE_DEBUG=1`) names the error type, status and safe codes.
 *
 * Both are built only from values TeamCode validates: HTTP status, API error codes, `Retry-After`, request ids,
 * class names and the configured server origin. Exception messages, response bodies, tokens and exception chains
 * from TLS, JSON, files or remote servers are never printed: they may carry credentials. The one exception is the
 * release client's [ReleaseDownloadException.detail]: its requests carry no credential, and the detail is reduced to
 * a class name and a short message whose URLs keep only their origin.
 */
internal class CliFailure(val message: String, val diagnostic: String)

internal const val DEBUG_HINT = "Run it again with `teamcode --debug <command>` (or TEAMCODE_DEBUG=1) to see the error type."

internal fun describeFailure(error: Throwable, serverUrl: String?): CliFailure {
    val server = serverUrl?.let { "TeamCode server at $it" } ?: "TeamCode server"
    val network = networkFailure(error)
    val message = when {
        error is CliUserException -> error.message
        error is ProcessStartException -> error.toUserError().message
        error is TeamCodeException -> httpMessage(error, server)
        error is OAuthEnrollmentException -> oauthMessage(error)
        error is CliOperationException -> operationMessage(error.code, error.detail)
        error is ReleaseDownloadException -> releaseFailureMessage(error.code, error.detail)
        network != null -> networkMessage(network, server)
        error.isA("SerializationException", "NoTransformationFoundException", "JsonConvertException") || error is SerializationException ->
            "The $server answered something this CLI ($VERSION) cannot read. It may run another TeamCode version, or a proxy or login page answered instead. " +
                "Check the server URL, then update the CLI or the server so their versions match."
        error.isA("FileNotFoundException", "NoSuchFileException", "AccessDeniedException", "FileSystemException") || error is okio.IOException ->
            "Cannot read or write a private TeamCode file. Check that your user owns ${configLocation()} and can read and write it, then try again."
        else -> "TeamCode command failed unexpectedly. $DEBUG_HINT"
    }
    return CliFailure(message, diagnostic(error, network))
}

/** Network failures by family; detected from ktor's common types and each engine's error names, never printed. */
internal typealias NetworkFailure = me.nathanfallet.teamcode.client.NetworkFailure

private fun httpMessage(error: TeamCodeException, server: String): String {
    val code = error.code
    val tag = "HTTP ${error.statusCode}${code?.let { ", $it" } ?: ""}"
    val retry = error.retryAfterSeconds?.let { " Retry in ${duration(it)}." } ?: ""
    val request = error.requestId?.let { " Request id: $it." } ?: ""
    when (code) {
        "invalid_credentials" -> return "Sign-in failed ($tag): the username or password is incorrect."
        "max_subscription_required" -> return "This account has no Claude Max subscription ($tag). Use --provider claude, or sign in with a Max account."
        "provider_verification_failed" -> return "The provider did not accept the new sign-in ($tag). Run `teamcode accounts add` again."
        "profile_not_found" -> return "No cloud profile you can use has this id or name ($tag). Run `teamcode cloud profiles` to list them; " +
            "an administrator adds --member <id> to list another member's."
        "artifact_storage_missing" -> return "Artifacts are not available on this server ($tag): no storage is configured. An administrator sets it up in Dashboard → Settings → Artifacts."
        "artifact_storage_unverified" -> return "Artifacts are not available on this server ($tag): the storage has not passed its connection test since it changed. " +
            "An administrator tests it in Dashboard → Settings → Artifacts."
        "artifacts_disabled" -> return "Artifacts are turned off on this server ($tag). An administrator turns them on in Dashboard → Settings → Artifacts."
        "artifact_rate_limited" -> return "You have published the team's hourly maximum of artifact versions ($tag).$retry"
        "artifact_uploads_busy" -> return "Too many artifact publications are in progress or unfinished ($tag). Finish them or wait for them to expire.$retry"
        "artifact_quota_exceeded" -> return "This publication would exceed an artifact quota of the team ($tag). Delete older artifacts (teamcode artifacts delete), or ask an administrator."
        "artifact_busy" -> return "A file of this publication is being cleaned up from the artifact storage ($tag). Publish again in a minute."
        "artifact_upload_not_found", "artifact_blob_missing" -> return "The upload expired or is incomplete ($tag). Publish again."
        "artifact_publish_forbidden" -> return "Only administrators publish artifacts in this team ($tag)."
        "artifact_not_writable" -> return "Only the owner and invited editors can publish or invite on this artifact ($tag). Ask its owner to share it with you as an editor."
        "artifact_access_forbidden", "artifact_delete_forbidden" -> return "Only the artifact's owner or an administrator can do this ($tag)."
        "artifact_public_sharing_off" -> return "Public links are turned off for this team ($tag). An administrator can allow them in Dashboard → Settings → Artifacts."
        "artifact_public_forbidden" -> return "The team's settings do not let you make this artifact public ($tag). Ask an administrator."
        "artifact_public_interactive" -> return "A publication never makes an artifact public ($tag). Use teamcode artifacts access ARTIFACT public, signed in as its owner."
        "artifact_member_not_found" -> return "No active member matches, or the member is not invited ($tag). Use a member id, username or linked GitHub login."
        "not_available_yet" -> return "This is not available yet ($tag): automations with a remote action are planned, and this server only runs cloud ones. " +
            "Use --action cloud with a cloud profile (teamcode cloud profiles)."
        "stale_version" -> return "What you are changing was modified since this command read it ($tag). Run the command again."
        "automation_limit" -> return "You own the most automations the team allows ($tag). Delete one first: teamcode automations delete ID --yes."
        "invalid_automation" -> return "The server rejected this automation definition ($tag). Check the cron expression (five fields, runs at least 5 minutes apart), " +
            "the IANA timezone, and that every {{name}} in the prompt is a --var or a run variable (run.id, run.date, run.time, run.timezone, run.source, automation.id, automation.name)."
        "payload_required" -> return "This webhook automation needs a payload to be tested ($tag). Pass --payload FILE or --payload-stdin."
        "payload_not_accepted" -> return "A cron automation takes no payload ($tag). Run the test without --payload and --payload-stdin."
        "invalid_payload" -> return "The payload does not validate against the automation's variables ($tag). Run it with --dry-run to see which paths and rules fail."
        "automation_disabled" -> return "The automation is paused ($tag). Arm it with teamcode automations enable ID."
        "profile_unassigned" -> return "The session owner is not assigned to this cloud profile ($tag). Run `teamcode cloud profiles` to list your usable profiles " +
            "(an administrator adds --member <owner id> for another member's), or ask a team administrator to assign it."
    }
    return when (error.statusCode) {
        0 -> "The connection to the $server closed before the answer was complete. Check your network, then try again."
        in 300..399 -> "The $server answered with a redirect ($tag), which TeamCode never follows so your credential stays on this origin. " +
            "Check the server URL: use the final https:// origin, then run `teamcode login --server <url>`."
        401 -> "Your TeamCode session has expired or was revoked ($tag). Run `teamcode login` to sign in again."
        403 -> forbiddenMessage(code, tag)
        404 -> if (code == null)
            "The $server does not know this request ($tag): it may run an older TeamCode version than this CLI ($VERSION). Update the server, or use a CLI that matches it."
            else "Not found ($tag): check the id or name you passed; the list commands show valid ids."
        408 -> "The $server timed out waiting for the request ($tag). Try again."
        409 -> "The request conflicts with the current state on the server ($tag). Check the current state, then try again.$retry"
        413 -> "The request is larger than the server accepts ($tag)."
        400, 422 -> "The server rejected the request as invalid ($tag). Check the command's arguments with --help."
        429 -> "The server refused the request for now: too many requests, or no allowance or capacity is available ($tag).${retry.ifEmpty { " Wait a moment, then try again." }}"
        in 500..599 -> "The $server is unavailable or failed ($tag).${retry.ifEmpty { " Try again in a moment." }}$request If it persists, contact your TeamCode administrator" +
            (if (error.requestId != null) " with the request id." else ".")
        else -> "The $server refused the request ($tag)."
    }
}

private fun forbiddenMessage(code: String?, tag: String): String = when (code) {
    "forbidden" -> "TeamCode refused this operation ($tag): it needs a team administrator, or your member is disabled. " +
        "Ask an administrator to run it or to change your role."
    "owner_required" -> "Only the team owner can do this ($tag). Ask the owner to run it."
    "credential_scope" -> "The configured credential cannot do this ($tag): it is a service key without the needed scope, or the operation needs an interactive sign-in. " +
        "Run `teamcode login` with your own account, or use a key with the required scope."
    "password_change_required" -> "Your temporary password must be changed first ($tag). Run `teamcode login` to choose a new one."
    "cloud_permission_denied", "on_behalf_forbidden", "transfer_forbidden" ->
        "Your role does not allow this cloud session operation ($tag). Ask a team administrator."
    "model_not_allowed", "effort_not_allowed", "thinking_budget_not_allowed", "image_not_allowed" ->
        "The team policy does not allow this model or setting ($tag). Choose another one, or ask an administrator."
    "group_paused", "run_paused" -> "Your group or run is paused ($tag). Ask a team administrator."
    "owner_unavailable" -> "The session owner is disabled or has not finished setting up their password ($tag)."
    else -> "TeamCode refused this operation ($tag). Ask a team administrator for access."
}

private fun oauthMessage(error: OAuthEnrollmentException): String {
    val provider = error.provider
    val again = "Run `teamcode accounts add` again and paste the complete " +
        (if (provider == "Codex") "localhost:1455/auth/callback URL from the browser address bar." else "code#state shown by Claude.")
    return when (error.reason) {
        OAuthEnrollmentException.Reason.INVALID_PASTE -> "The pasted $provider sign-in code is incomplete or not the expected one. $again"
        OAuthEnrollmentException.Reason.STATE_MISMATCH -> "The pasted $provider sign-in code belongs to another sign-in attempt. $again"
        OAuthEnrollmentException.Reason.PROVIDER_REFUSED -> "$provider refused the sign-in in the browser. $again"
        OAuthEnrollmentException.Reason.EXCHANGE_REJECTED ->
            "$provider did not accept the sign-in code (HTTP ${error.status}): it expired, was already used or is incomplete. $again"
        OAuthEnrollmentException.Reason.EXCHANGE_UNAVAILABLE ->
            "The $provider sign-in service is busy or unavailable (HTTP ${error.status}). Wait a moment, then run `teamcode accounts add` again."
        OAuthEnrollmentException.Reason.EXCHANGE_UNREACHABLE -> "Cannot reach the $provider sign-in service. Check your network, then run `teamcode accounts add` again."
        OAuthEnrollmentException.Reason.INVALID_TOKEN_RESPONSE -> "The $provider sign-in service returned an unexpected answer. Run `teamcode accounts add` again later."
    }
}

private fun operationMessage(code: String, detail: String?): String = when {
    code.startsWith("release_") -> releaseFailureMessage(code, detail)
    code.startsWith("configuration_write_") || code.startsWith("windows_private_file_") ->
        "Cannot write a private TeamCode file ($code). Check that your user owns ${configLocation()} and that no other user can access it, then try again."
    code.startsWith("desktop_") -> desktopFailureMessage(code)
    code.startsWith("automatic_") ->
        "Automatic update setup failed ($code). Check `teamcode update --help`, then try again; the current installation is unchanged."
    else -> "TeamCode command failed ($code). $DEBUG_HINT"
}

/** `teamcode ui`: the previously installed desktop app, if any, is always left in place. */
internal fun desktopFailureMessage(code: String): String = when {
    code == "desktop_signature_invalid" ->
        "The downloaded TeamCode Desktop is not signed by TeamCode's publisher ($code). Nothing was installed; download it from the GitHub release page."
    code == "desktop_version_mismatch" || code == "desktop_package_layout" || code == "desktop_package_invalid" ->
        "The TeamCode Desktop package is not the expected one ($code). Nothing was installed."
    code == "desktop_launch_failed" -> "TeamCode Desktop is installed but could not be opened ($code). Open it from your applications."
    code == "desktop_restart_timeout" -> "TeamCode Desktop did not quit in time for its update ($code). Quit it, then run teamcode ui --yes."
    code.startsWith("desktop_msiexec_") -> "The Windows installer of TeamCode Desktop failed ($code). Run teamcode ui again, or install the .msi from the release page."
    else -> "TeamCode Desktop could not be installed ($code). The previous installation, if any, is unchanged. $DEBUG_HINT"
}

private fun networkMessage(failure: NetworkFailure, server: String): String = when (failure) {
    NetworkFailure.DNS -> "Cannot find the $server: its host name does not resolve. Check the server URL, your DNS and your network or VPN."
    NetworkFailure.REFUSED -> "Cannot reach the $server: the connection was refused or there is no route. Check the URL, that the server is running, and your network or VPN."
    NetworkFailure.TIMEOUT -> "The $server did not answer in time. Check your network or VPN, then try again."
    NetworkFailure.TLS -> "TLS error with the $server: its certificate is not trusted, expired or does not match the host name, or it does not speak HTTPS on this port. " +
        "Check the URL and the server's certificate; a proxy that intercepts HTTPS can also cause this."
    NetworkFailure.CLOSED -> "The connection to the $server closed before the answer was complete. Check your network, then try again."
    NetworkFailure.PROXY -> "The proxy between this computer and the $server refused or failed the connection. Check your proxy settings (HTTPS_PROXY on Linux) and that it allows this server."
    NetworkFailure.HEADERS -> "The $server, or a proxy in front of it, answered with headers this CLI cannot read (for example a header line over 8 KB). Check the server URL and any proxy in between."
    NetworkFailure.OTHER -> "Cannot reach the $server. Check the URL, your network and that the server is running."
}

internal fun networkFailure(error: Throwable): NetworkFailure? {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    if (chain.any { it is TeamCodeException || it is CliUserException || it is OAuthEnrollmentException || it is ReleaseDownloadException }) return null
    return me.nathanfallet.teamcode.client.networkFailure(error)
}

private fun Throwable.isA(vararg names: String) =
    generateSequence(this) { it.cause }.take(8).any { it::class.simpleName in names }

private fun diagnostic(error: Throwable, network: NetworkFailure?): String = buildString {
    append("Debug: type=").append(error::class.simpleName ?: "unknown")
    when (error) {
        is TeamCodeException -> {
            append(" status=").append(error.statusCode)
            error.code?.let { append(" code=").append(it) }
            error.retryAfterSeconds?.let { append(" retry_after=").append(it) }
            error.requestId?.let { append(" request_id=").append(it) }
        }
        is OAuthEnrollmentException -> { append(" reason=").append(error.reason.name.lowercase()); error.status?.let { append(" status=").append(it) } }
        is CliOperationException -> { append(" code=").append(error.code); error.detail?.let { append(" cause=\"").append(it).append('"') } }
        is ReleaseDownloadException -> { append(" code=").append(error.code); error.detail?.let { append(" cause=\"").append(it).append('"') } }
        is ProcessStartException -> append(" reason=").append(error.reason.name)
        else -> {}
    }
    network?.let { append(" network=").append(it.name.lowercase()) }
    val causes = generateSequence(error.cause) { it.cause }.take(7).mapNotNull { it::class.simpleName }.toList()
    if (causes.isNotEmpty()) append(" causes=").append(causes.joinToString(">"))
    append(" version=").append(VERSION)
}

private fun duration(seconds: Long) = when {
    seconds < 120 -> "$seconds seconds"
    seconds < 7_200 -> "${(seconds + 59) / 60} minutes"
    else -> "${(seconds + 3_599) / 3_600} hours"
}

private fun configLocation() = try { configPath().substringBeforeLast('/') } catch (_: Exception) { "the TeamCode configuration directory" }

/** The one-line command that reinstalls the latest release with the platform's own downloader (curl or .NET), not this client. */
internal const val INSTALL_SCRIPT_COMMAND = "curl -fsSL https://github.com/nathanfallet/teamcode/releases/latest/download/install.sh | sh"
private const val REINSTALL = "reinstall with the install script, which downloads with your system's curl: `$INSTALL_SCRIPT_COMMAND` " +
    "(on Windows, the install.ps1 command in the README); it keeps your login and settings"
private const val TEST_HOSTS = "test access with `curl -sSI https://api.github.com` and `curl -sSI https://release-assets.githubusercontent.com` " +
    "(any HTTP status line means the host is reachable)"

/**
 * Actionable text for release failures: what happened, the short technical cause when there is one, and what to do. The code
 * stays in the output for diagnostics, support and scripts (the smoke scripts match on it).
 */
internal fun releaseFailureMessage(code: String, detail: String? = null): String {
    val cause = releaseCause(detail)
    return when (code) {
        "release_installation_busy" ->
            "TeamCode installation is busy ($code): an agent session started by TeamCode is still running, or the installation is being modified. Close all agent sessions, then run the command again (for example `teamcode update`)."
        "release_writer_busy" ->
            "TeamCode installation is busy ($code): another TeamCode update or installation change is in progress. Wait for it to finish, then run the command again."
        "release_timeout", "release_operation_timeout" ->
            "The TeamCode release download did not finish in time ($code): GitHub stopped answering, or the connection is too slow for the download.$cause " +
                "Check your network or VPN, then run the command again. On a slow or unstable connection, $REINSTALL."
        "release_dns_failure" ->
            "Cannot find GitHub's release servers ($code): api.github.com or release-assets.githubusercontent.com does not resolve.$cause " +
                "Check your DNS, network or VPN, $TEST_HOSTS, then run the command again."
        "release_tls_failure" ->
            "The secure connection to GitHub's release servers failed ($code): the certificate is not trusted or has expired, or a proxy or antivirus intercepts HTTPS.$cause " +
                "Check the system date and the CA certificates (the ca-certificates package on Linux) and any HTTPS-inspecting proxy, $TEST_HOSTS, then run the command again."
        "release_proxy_failure" ->
            "The proxy between this computer and GitHub refused or failed the connection ($code).$cause " +
                "Check your proxy settings (HTTPS_PROXY on Linux) and that the proxy allows api.github.com and release-assets.githubusercontent.com, then run the command again."
        "release_connection_failure" ->
            "Cannot connect to GitHub's release servers ($code): the connection was refused or reset, or there is no route.$cause " +
                "Check your network, VPN, firewall or proxy, $TEST_HOSTS, then run the command again."
        "release_connection_closed" ->
            "The connection to GitHub closed before the release download finished ($code).$cause Run the command again; if it keeps failing, check your network or VPN, or $REINSTALL."
        "release_network_failure" ->
            "Cannot reach GitHub's release servers ($code).$cause Check your network, VPN or proxy and $TEST_HOSTS. If curl works, $REINSTALL."
        "release_response_invalid" ->
            "GitHub's release servers answered something this TeamCode version cannot read ($code).$cause A proxy, a captive portal or a change on GitHub can cause this; " +
                "$REINSTALL."
        "release_rate_limited" ->
            "GitHub refused the request for now: too many unauthenticated API requests from your network, 60 per hour per IP address ($code).$cause " +
                "Wait for the limit to reset, then run the command again, or $REINSTALL."
        "release_write_failure" ->
            "Cannot save the downloaded TeamCode release on this computer ($code): the disk may be full, or your user cannot write to the TeamCode installation folder.$cause " +
                "Free some disk space (`df -h` shows it on macOS and Linux), check that your user owns the TeamCode installation folder, then run the command again."
        "release_unexpected_failure" ->
            "The TeamCode release download failed unexpectedly ($code).$cause Run the command again with `teamcode --debug` and report its output; meanwhile, $REINSTALL."
        "release_not_managed" ->
            "This TeamCode CLI is not a managed installation ($code): it was copied by hand or built from source, so it cannot update itself. " +
                "Install it once with the install script (see the README), which keeps your login and settings; later updates then work."
        "release_target_missing" ->
            "This TeamCode release has no build for this computer ($code). macOS on Intel is no longer supported: the last release with an Intel build is 1.0.1 " +
                "(https://github.com/nathanfallet/teamcode/releases/tag/1.0.1). On any other system, check the version with `teamcode update --check`."
        "release_http_404" ->
            "This TeamCode release or one of its files is not published on GitHub ($code). Check the version with `teamcode update --check`."
        else -> if (code.startsWith("release_http_5"))
            "GitHub's release servers are unavailable ($code). Try again in a few minutes."
        else
            "TeamCode release operation failed ($code).$cause Check connectivity, release files, private permissions and platform crypto libraries."
    }
}

/** " Cause: <detail>." for a non-blank detail, with no doubled final punctuation; empty otherwise. */
internal fun releaseCause(detail: String?): String {
    val text = detail?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
    return " Cause: $text" + if (text.last() in ".…!?") "" else "."
}
