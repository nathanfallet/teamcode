package me.nathanfallet.teamcode.companion

import me.nathanfallet.teamcode.companion.domain.models.AddedSubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.EnrollmentAccess
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccountType
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionSignIn
import me.nathanfallet.teamcode.companion.domain.repositories.SubscriptionAccountRepository

fun subscription(id: String = "acc1", state: String = "READY") = SubscriptionAccount(id, "Team Max (ada@example.com)", "anthropic", state, disabled = false)

/** The client's menu, as the real repository maps it. */
val subscriptionTypes = listOf(
    SubscriptionAccountType("anthropic-subscription", "Anthropic subscription (Claude Pro, Max, Team, Enterprise)", true),
    SubscriptionAccountType("codex-subscription", "Codex subscription (ChatGPT Plus, Pro, Team)", true),
    SubscriptionAccountType("anthropic-api", "Anthropic API key (Claude Console, console.anthropic.com)", false),
)

/** Subscription accounts answered by the fields below; every call is recorded in [calls] (never a pasted code). */
class FakeSubscriptionAccountRepository : SubscriptionAccountRepository {
    val calls = mutableListOf<String>()
    var access = EnrollmentAccess.ALLOWED
    var pool = listOf(subscription())
    var attempts = 0
    /** The answer to a pasted code; [paste] is the code the member typed. */
    var completion: (paste: String) -> AddedSubscriptionAccount = { AddedSubscriptionAccount(created = true, subscription("acc2")) }
    val pastes = mutableListOf<String>()

    override val types = subscriptionTypes
    override suspend fun access(accountId: String): EnrollmentAccess { calls += "access"; return access }
    override suspend fun accounts(accountId: String): List<SubscriptionAccount> { calls += "accounts"; return pool }
    override fun startSignIn(accountId: String, type: SubscriptionAccountType): SubscriptionSignIn {
        val attempt = ++attempts
        calls += "start ${type.id} $attempt"
        return SubscriptionSignIn(type, "Claude", "https://provider.example/authorize?attempt=$attempt", "Paste the code.") { paste ->
            calls += "complete $attempt"
            pastes += paste
            completion(paste)
        }
    }
}
