package me.nathanfallet.teamcode.cli

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
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
 * from TLS, JSON, files or remote servers are never printed: they may carry credentials.
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
        error is CliOperationException -> operationMessage(error.code)
        error is ReleaseDownloadException ->
            if (error.code == "release_network_failure") "Cannot reach the TeamCode release server (${error.code}). Check your network, then try again."
            else releaseFailureMessage(error.code)
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
internal enum class NetworkFailure { DNS, REFUSED, TIMEOUT, TLS, CLOSED, OTHER }

private fun httpMessage(error: TeamCodeException, server: String): String {
    val code = error.code
    val tag = "HTTP ${error.statusCode}${code?.let { ", $it" } ?: ""}"
    val retry = error.retryAfterSeconds?.let { " Retry in ${duration(it)}." } ?: ""
    val request = error.requestId?.let { " Request id: $it." } ?: ""
    return when (error.statusCode) {
        0 -> "The connection to the $server closed before the answer was complete. Check your network, then try again."
        in 300..399 -> "The $server answered with a redirect ($tag), which TeamCode never follows so your credential stays on this origin. " +
            "Check the server URL: use the final https:// origin, then run `teamcode login --server <url>`."
        401 -> when (code) {
            "invalid_credentials" -> "Sign-in failed ($tag): the username or password is incorrect."
            else -> "Your TeamCode session has expired or was revoked ($tag). Run `teamcode login` to sign in again."
        }
        403 -> forbiddenMessage(code, tag)
        404 -> if (code == null)
            "The $server does not know this request ($tag): it may run an older TeamCode version than this CLI ($VERSION). Update the server, or use a CLI that matches it."
            else "Not found ($tag): check the id or name you passed; the list commands show valid ids."
        408 -> "The $server timed out waiting for the request ($tag). Try again."
        409 -> "The request conflicts with the current state on the server ($tag). Check the current state, then try again.$retry"
        413 -> "The request is larger than the server accepts ($tag)."
        400, 422 -> when (code) {
            "invalid_credentials" -> "Sign-in failed ($tag): the username or password is incorrect."
            "max_subscription_required" -> "This account has no Claude Max subscription ($tag). Use --provider claude, or sign in with a Max account."
            "provider_verification_failed" -> "The provider did not accept the new sign-in ($tag). Run `teamcode accounts add` again."
            else -> "The server rejected the request as invalid ($tag). Check the command's arguments with --help."
        }
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
    "group_paused", "run_paused", "profile_unassigned" -> "Your group or run is paused or not assigned ($tag). Ask a team administrator."
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
        OAuthEnrollmentException.Reason.EXCHANGE_UNREACHABLE -> "Cannot reach the $provider sign-in service. Check your network, then run `teamcode accounts add` again."
        OAuthEnrollmentException.Reason.INVALID_TOKEN_RESPONSE -> "The $provider sign-in service returned an unexpected answer. Run `teamcode accounts add` again later."
    }
}

private fun operationMessage(code: String): String = when {
    code.startsWith("release_") -> releaseFailureMessage(code)
    code.startsWith("configuration_write_") || code.startsWith("windows_private_file_") ->
        "Cannot write a private TeamCode file ($code). Check that your user owns ${configLocation()} and that no other user can access it, then try again."
    code.startsWith("automatic_") ->
        "Automatic update setup failed ($code). Check `teamcode update --help`, then try again; the current installation is unchanged."
    else -> "TeamCode command failed ($code). $DEBUG_HINT"
}

private fun networkMessage(failure: NetworkFailure, server: String): String = when (failure) {
    NetworkFailure.DNS -> "Cannot find the $server: its host name does not resolve. Check the server URL, your DNS and your network or VPN."
    NetworkFailure.REFUSED -> "Cannot reach the $server: the connection was refused or there is no route. Check the URL, that the server is running, and your network or VPN."
    NetworkFailure.TIMEOUT -> "The $server did not answer in time. Check your network or VPN, then try again."
    NetworkFailure.TLS -> "TLS error with the $server: its certificate is not trusted, expired or does not match the host name, or it does not speak HTTPS on this port. " +
        "Check the URL and the server's certificate; a proxy that intercepts HTTPS can also cause this."
    NetworkFailure.CLOSED -> "The connection to the $server closed before the answer was complete. Check your network, then try again."
    NetworkFailure.OTHER -> "Cannot reach the $server. Check the URL, your network and that the server is running."
}

internal fun networkFailure(error: Throwable): NetworkFailure? {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    if (chain.any { it is TeamCodeException || it is CliUserException || it is OAuthEnrollmentException || it is ReleaseDownloadException }) return null
    // Types first (JVM and ktor), then each native engine's error text. Names and messages are inspected, never printed.
    val names = chain.map { it::class.simpleName.orEmpty() }
    fun named(vararg candidates: String) = names.any { name -> candidates.any(name::contains) }
    val byType = when {
        named("SSL", "Tls", "TLS", "Certificate") -> NetworkFailure.TLS
        named("UnknownHost", "UnresolvedAddress") -> NetworkFailure.DNS
        chain.any { it is ConnectTimeoutException || it is SocketTimeoutException || it is HttpRequestTimeoutException } || named("Timeout") -> NetworkFailure.TIMEOUT
        named("ConnectException", "NoRouteToHost", "PortUnreachable") -> NetworkFailure.REFUSED
        named("EOFException", "ClosedReceiveChannel", "ClosedByteChannel") -> NetworkFailure.CLOSED
        else -> null
    }
    if (byType != null) return byType
    val engine = chain.any { it is kotlinx.io.IOException } && !error.isFileError() || named(*ENGINE_NAMES)
    if (!engine) return null
    val text = chain.joinToString("\n") { it.message.orEmpty() }
    fun mentions(fragments: List<String>) = fragments.any { text.contains(it) }
    return when {
        mentions(TLS_TEXT) -> NetworkFailure.TLS
        mentions(DNS_TEXT) -> NetworkFailure.DNS
        mentions(TIMEOUT_TEXT) -> NetworkFailure.TIMEOUT
        mentions(REFUSED_TEXT) -> NetworkFailure.REFUSED
        else -> NetworkFailure.OTHER
    }
}

private fun Throwable.isFileError() = isA("FileNotFoundException", "NoSuchFileException", "AccessDeniedException", "FileSystemException")
private fun Throwable.isA(vararg names: String) =
    generateSequence(this) { it.cause }.take(8).any { it::class.simpleName in names }

// Engines: OkHttp (JVM tests), Curl (Linux), Darwin/NSURLSession (macOS) and WinHTTP (Windows). Case-sensitive on purpose:
// a lowercase host name such as `tls.example.com` in an engine message must not look like a TLS failure.
private val ENGINE_NAMES = arrayOf("Curl", "Darwin", "WinHttp", "Socket", "Connect")
private val TLS_TEXT = listOf("SSL", "TLS", "certificate", "Certificate", "security error", "Code=-1200", "Code=-1201", "Code=-1202", "Code=-1203",
    "Code=-1204", "Code=-1205", "Code=-1206", "Code=-2000", "12175", "12037", "12038", "12044", "12045", "12057", "12169")
private val DNS_TEXT = listOf("resolve host", "Could not resolve", "Couldn't resolve", "could not be resolved", "Unable to resolve", "nodename nor servname",
    "Name or service not known", "Code=-1003", "Code=-1006", "12007")
private val TIMEOUT_TEXT = listOf("timed out", "Timeout was reached", "Code=-1001", "12002")
private val REFUSED_TEXT = listOf("Couldn't connect", "Could not connect", "Connection refused", "Failed to connect", "could not be established",
    "Code=-1004", "Code=-1009", "12029")

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
        is CliOperationException -> append(" code=").append(error.code)
        is ReleaseDownloadException -> append(" code=").append(error.code)
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
