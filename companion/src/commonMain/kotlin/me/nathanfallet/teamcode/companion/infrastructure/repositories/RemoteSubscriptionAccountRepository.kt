package me.nathanfallet.teamcode.companion.infrastructure.repositories

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.api.models.AccountView
import me.nathanfallet.teamcode.client.AccountEnrollmentAccess
import me.nathanfallet.teamcode.client.AccountType
import me.nathanfallet.teamcode.client.OAuthEnrollmentException
import me.nathanfallet.teamcode.client.OAuthSignIn
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.client.accountEnrollmentAccess
import me.nathanfallet.teamcode.companion.domain.models.AddedSubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.EnrollmentAccess
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccountType
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionSignIn
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.domain.services.SecureRandom

/**
 * `teamcode accounts add` on the device: the account types, the PKCE sign-in and the enrollment check are the typed
 * client's, shared with the CLI. The provider sign-in runs in the system browser and its token exchange from this
 * device ([engine], no TeamCode credential sent); only the resulting credentials go to the server.
 */
class RemoteSubscriptionAccountRepository(
    private val calls: AccountCalls,
    private val features: FeaturesRepository,
    private val engine: HttpClientEngineFactory<*>,
    private val random: SecureRandom,
) : SubscriptionAccountRepository {
    override val types: List<SubscriptionAccountType> = AccountType.entries.map { SubscriptionAccountType(it.flag, it.label, it.supported) }

    override suspend fun access(accountId: String): EnrollmentAccess {
        val announced = features.features(accountId)
        return calls.withAccount(accountId) { client ->
            when (accountEnrollmentAccess(announced, client::accountEnrollmentPolicy) { client.me().role }) {
                AccountEnrollmentAccess.ALLOWED -> EnrollmentAccess.ALLOWED
                AccountEnrollmentAccess.ADMINISTRATORS_ONLY -> EnrollmentAccess.ADMINISTRATORS_ONLY
                AccountEnrollmentAccess.SESSION_CANNOT_ENROLL -> EnrollmentAccess.SESSION_CANNOT_ENROLL
            }
        }
    }

    override suspend fun accounts(accountId: String): List<SubscriptionAccount> = calls.withAccount(accountId) { client -> client.accounts().map { it.domain() } }

    override fun startSignIn(accountId: String, type: SubscriptionAccountType): SubscriptionSignIn {
        val provider = AccountType.entries.firstOrNull { it.flag == type.id }?.enrollment ?: throw CompanionException.UnavailableAccountType()
        val signIn = OAuthSignIn.start(provider, random::nextBytes)
        return SubscriptionSignIn(type, provider.displayName, signIn.authorizationUrl, provider.pasteInstructions) { paste ->
            val input = try {
                signIn.exchange(paste) { configure -> HttpClient(engine) { configure(this) } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: OAuthEnrollmentException) {
                // TeamCode's own sentence; the paste, the provider's answer and the cause are never kept.
                throw CompanionException.ProviderSignIn(restart = error.reason != OAuthEnrollmentException.Reason.INVALID_PASTE, error.message ?: "Sign-in failed.")
            }
            calls.withAccount(accountId) { client ->
                val result = try {
                    client.enrollAccount(input)
                } catch (error: TeamCodeException) {
                    val code = error.code
                    if (error.statusCode == 403 && code in REFUSALS) throw CompanionException.EnrollmentRefused(code!!)
                    throw error
                }
                AddedSubscriptionAccount(result.created, result.account.domain())
            }
        }
    }

    private companion object {
        val REFUSALS = setOf(CompanionException.EnrollmentRefused.ENROLLMENT_FORBIDDEN, "account_in_use")
    }
}

internal fun AccountView.domain() = SubscriptionAccount(id, label, provider, state, disabled)
