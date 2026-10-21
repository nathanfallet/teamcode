package me.nathanfallet.teamcode.infrastructure.webhooks

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService
import me.nathanfallet.teamcode.domain.webhooks.SendResult
import me.nathanfallet.teamcode.domain.webhooks.WebhookSender

/**
 * POSTs one delivery. Redirects are not followed, the response body is never read (only its status), and the target
 * is checked again here: a DNS name resolving to a link-local, unspecified or metadata address is refused, as for a
 * Kubernetes API host. The check cannot pin the address the client then dials. Errors come back as short codes, since
 * the network stack's messages may echo the URL.
 */
class HttpWebhookSender(
    private val http: HttpClient,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : WebhookSender {
    override suspend fun send(url: String, headers: Map<String, String>, body: String): SendResult {
        if (!CloudAdministrationService.allowedApiHost(url)) return SendResult(null, "host_not_allowed")
        val host = try { Url(url).host.removePrefix("[").removeSuffix("]") } catch (_: Exception) { return SendResult(null, "invalid_url") }
        val addresses = try { withContext(Dispatchers.IO) { resolver(host) } } catch (error: CancellationException) { throw error } catch (_: Exception) { return SendResult(null, "dns") }
        if (addresses.isEmpty() || addresses.any { !CloudAdministrationService.allowedResolvedAddress(it.address) }) return SendResult(null, "host_not_allowed")
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
            SendResult(null, when { "Timeout" in name -> "timeout"; "SSL" in name || "Handshake" in name || "Certificate" in name -> "tls"; else -> "connect" })
        }
    }
}
