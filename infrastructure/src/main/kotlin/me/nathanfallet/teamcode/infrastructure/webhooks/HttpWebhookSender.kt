package me.nathanfallet.teamcode.infrastructure.webhooks

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.net.InetAddress
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService
import me.nathanfallet.teamcode.domain.webhooks.SendResult
import me.nathanfallet.teamcode.domain.webhooks.WebhookSender
import me.nathanfallet.teamcode.infrastructure.network.PinnedDnsRefusal

/**
 * POSTs one delivery. Redirects are not followed, the response body is never read (only its status), and the target
 * is checked again here: a DNS name resolving to an address [addressAllowed] refuses gets `host_not_allowed` without a
 * connection. [http] must dial the address it checks too ([webhookAddressPolicy] in its engine's
 * [me.nathanfallet.teamcode.infrastructure.network.PinnedDns] resolver), so a name that changes its answer between the
 * two lookups is refused at the connection (`connect`). Errors come back as short codes, since the network stack's
 * messages may echo the URL.
 */
class HttpWebhookSender(
    private val http: HttpClient,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val allowPrivateTargets: Boolean = false,
) : WebhookSender {
    private val addressAllowed = webhookAddressPolicy(allowPrivateTargets)

    override suspend fun send(url: String, headers: Map<String, String>, body: String): SendResult {
        // Also for subscriptions created before the operator withdrew the opt-in, or by an older server.
        if (!(if (allowPrivateTargets) CloudAdministrationService.allowedApiHost(url) else CloudAdministrationService.publicApiHost(url)))
            return SendResult(null, "host_not_allowed")
        val host = try { Url(url).host.removePrefix("[").removeSuffix("]") } catch (_: Exception) { return SendResult(null, "invalid_url") }
        // Resolution cannot be interrupted, so it runs apart and is abandoned after a deadline.
        val addresses = try { withTimeout(5_000) { async(Dispatchers.IO) { resolver(host) }.await() } }
            catch (error: TimeoutCancellationException) { return SendResult(null, "dns") } catch (error: CancellationException) { throw error } catch (_: Exception) { return SendResult(null, "dns") }
        if (addresses.isEmpty() || addresses.any { !addressAllowed(it.address) }) return SendResult(null, "host_not_allowed")
        return try {
            val response = http.post(url) {
                headers.forEach { (name, value) -> header(name, value) }
                setBody(body)
            }
            SendResult(response.status.value, null)
        } catch (error: CancellationException) { throw error }
        catch (_: HttpRequestTimeoutException) { SendResult(null, "timeout") }
        catch (error: Exception) {
            val name = error::class.simpleName.orEmpty()
            SendResult(null, when { generateSequence<Throwable>(error) { it.cause }.take(8).any { it is PinnedDnsRefusal } -> "host_not_allowed"; "Timeout" in name -> "timeout"; "SSL" in name || "Handshake" in name || "Certificate" in name -> "tls"; else -> "connect" })
        }
    }
}

/** The addresses a webhook may connect to: public ones, or with the operator's opt-in any but link-local and metadata. */
fun webhookAddressPolicy(allowPrivateTargets: Boolean): (ByteArray) -> Boolean =
    if (allowPrivateTargets) CloudAdministrationService::allowedResolvedAddress else CloudAdministrationService::publicAddress
