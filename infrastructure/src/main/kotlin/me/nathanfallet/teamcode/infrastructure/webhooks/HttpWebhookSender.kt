package me.nathanfallet.teamcode.infrastructure.webhooks

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService
import me.nathanfallet.teamcode.domain.webhooks.SendResult
import me.nathanfallet.teamcode.domain.webhooks.WebhookSender
import me.nathanfallet.teamcode.infrastructure.network.PinnedDns
import me.nathanfallet.teamcode.infrastructure.network.PinnedDnsRefusal

/**
 * POSTs one delivery. Redirects are not followed and the response body is never read (only its status). The target is
 * checked twice, with one setting: its URL before anything (a literal private address of a subscription created while
 * the operator allowed it, say), then the address the connection dials, in the client's own resolver
 * ([PinnedDns]): one lookup, checked and dialled, so a name that changes its answer cannot pass with one and connect to
 * the other. Errors come back as short codes, since the network stack's messages may echo the URL.
 */
class HttpWebhookSender(
    private val allowPrivateTargets: Boolean = false,
    lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    requestTimeoutMillis: Long = 10_000,
) : WebhookSender, AutoCloseable {
    private val http = HttpClient(CIO) {
        followRedirects = false; expectSuccess = false
        engine { dnsResolver = PinnedDns.resolver(webhookAddressPolicy(allowPrivateTargets), lookup) }
        install(HttpTimeout) { connectTimeoutMillis = 5_000; this.requestTimeoutMillis = requestTimeoutMillis; socketTimeoutMillis = requestTimeoutMillis }
    }

    override suspend fun send(url: String, headers: Map<String, String>, body: String): SendResult {
        // Also for subscriptions created before the operator withdrew the opt-in, or by an older server.
        if (!(if (allowPrivateTargets) CloudAdministrationService.allowedApiHost(url) else CloudAdministrationService.publicApiHost(url)))
            return SendResult(null, HOST_NOT_ALLOWED)
        return try {
            val response = http.post(url) {
                headers.forEach { (name, value) -> header(name, value) }
                setBody(body)
            }
            SendResult(response.status.value, null)
        } catch (error: CancellationException) { throw error }
        catch (_: HttpRequestTimeoutException) { SendResult(null, "timeout") }
        catch (error: Exception) {
            val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            val name = error::class.simpleName.orEmpty()
            SendResult(null, when {
                causes.any { it is PinnedDnsRefusal } -> HOST_NOT_ALLOWED
                causes.any { it is UnknownHostException } -> "dns"
                "Timeout" in name -> "timeout"
                "SSL" in name || "Handshake" in name || "Certificate" in name -> "tls"
                else -> "connect"
            })
        }
    }

    override fun close() = http.close()

    companion object { const val HOST_NOT_ALLOWED = "host_not_allowed" }
}

/** The addresses a webhook may connect to: public ones, or with the operator's opt-in any but link-local and metadata. */
fun webhookAddressPolicy(allowPrivateTargets: Boolean): (ByteArray) -> Boolean =
    if (allowPrivateTargets) CloudAdministrationService::allowedResolvedAddress else CloudAdministrationService::publicAddress
