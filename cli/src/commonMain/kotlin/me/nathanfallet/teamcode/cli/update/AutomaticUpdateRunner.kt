package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import me.nathanfallet.teamcode.client.ReleaseDownloadException
import kotlin.time.Clock

/** One bounded scheduled attempt. Active-agent locks defer activation, rather than stopping an agent. */
internal class AutomaticUpdateRunner(private val policy: AutomaticUpdateStore, private val platform: ServicePlatform,
    private val disabled: Boolean = false, private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    suspend fun run(fetch: suspend () -> ReleaseCheck?, install: suspend (ReleaseCheck, AutomaticUpdateSettings) -> InstalledVersion?): AutomaticUpdateResult? {
        if (disabled || platform.userId == "0") return null
        val settings = policy.settings()?.takeIf { it.enabled } ?: return null
        require(settings.os == platform.os && settings.userId == platform.userId)
        val started = now()
        val lock = acquireInstallationLock(policy.workerLock.toString(), exclusive = true)
            ?: return AutomaticUpdateResult(attemptedAtMillis = started, outcome = "DEFERRED", diagnostic = "automatic_worker_busy")
        lock.use {
            policy.record(AutomaticUpdateResult(attemptedAtMillis = started, outcome = "STARTED"))
            val result = try {
                withTimeoutOrNull(15 * 60_000L) {
                    val candidate = fetch()
                    if (candidate == null || !candidate.updateAvailable || '-' in candidate.release.manifest.version)
                        AutomaticUpdateResult(attemptedAtMillis = started, outcome = "NO_UPDATE")
                    else {
                        val selected = install(candidate, settings)
                        AutomaticUpdateResult(attemptedAtMillis = started, outcome = if (selected == null) "NO_UPDATE" else "UPDATED",
                            version = candidate.release.manifest.version)
                    }
                } ?: AutomaticUpdateResult(attemptedAtMillis = started, outcome = "FAILED", diagnostic = "automatic_update_timeout")
            } catch (error: CancellationException) { throw error }
            catch (error: CliOperationException) {
                AutomaticUpdateResult(attemptedAtMillis = started,
                    outcome = if (error.code in setOf("release_writer_busy", "release_installation_busy")) "DEFERRED" else "FAILED", diagnostic = error.code)
            } catch (error: ReleaseDownloadException) {
                AutomaticUpdateResult(attemptedAtMillis = started, outcome = "FAILED", diagnostic = error.code)
            } catch (_: Exception) {
                AutomaticUpdateResult(attemptedAtMillis = started, outcome = "FAILED", diagnostic = "automatic_update_failed")
            }
            policy.record(result)
            return result
        }
    }
}
