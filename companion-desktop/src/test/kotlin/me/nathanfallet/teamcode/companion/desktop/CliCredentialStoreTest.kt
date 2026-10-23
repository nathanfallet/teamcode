package me.nathanfallet.teamcode.companion.desktop

import java.io.File
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.infrastructure.credentials.MemoryCredentialStore
import me.nathanfallet.teamcode.config.CliConfig
import me.nathanfallet.teamcode.config.ConfigStore
import me.nathanfallet.teamcode.config.JvmPrivateFiles
import kotlin.test.*

/** The CLI's account reads and writes config.json; every other account stays in the keyring. */
class CliCredentialStoreTest {
    private fun cliStore(directory: File, origin: String? = "https://team.example"): Triple<CliCredentialStore, ConfigStore, MemoryCredentialStore> {
        val path = File(directory, "teamcode/config.json").path
        val keyring = MemoryCredentialStore()
        return Triple(CliCredentialStore(keyring, CliConfigFile(path)) { if (it == Account.CLI_ACCOUNT_ID) origin else null }, ConfigStore(path, JvmPrivateFiles), keyring)
    }

    @Test fun theCliAccountUsesConfigJsonAndOthersTheKeyring() = withTemporaryDirectory { directory -> runTest {
        val (store, cli, keyring) = cliStore(directory)
        assertNull(store.read(Account.CLI_ACCOUNT_ID))
        cli.save(CliConfig("https://team.example", "tc-cli"))
        assertEquals("tc-cli", store.read(Account.CLI_ACCOUNT_ID))
        store.write("a1", "tc-app")
        assertEquals("tc-app", keyring.read("a1"))
        assertEquals("tc-cli", cli.load()?.accessToken)
        // A sign-in again in the app replaces the CLI's session in the file, owner-only, as teamcode login does.
        store.write(Account.CLI_ACCOUNT_ID, "tc-new")
        assertEquals("tc-new", cli.load()?.accessToken)
        if (posix) assertEquals("rw-------", java.nio.file.Files.getPosixFilePermissions(File(cli.path).toPath()).let(java.nio.file.attribute.PosixFilePermissions::toString))
    } }

    @Test fun aTerminalLoginIsSeenAtTheNextRead() = withTemporaryDirectory { directory -> runTest {
        val (store, cli) = cliStore(directory)
        cli.save(CliConfig("https://team.example", "tc-1"))
        assertEquals("tc-1", store.read(Account.CLI_ACCOUNT_ID))
        cli.save(CliConfig("https://team.example", "tc-2-longer"))
        assertEquals("tc-2-longer", store.read(Account.CLI_ACCOUNT_ID))
    } }

    @Test fun aTokenForAnotherServerIsNeverHandedOut() = withTemporaryDirectory { directory -> runTest {
        val (store, cli) = cliStore(directory)
        cli.save(CliConfig("https://other.example", "tc-other"))
        assertFailsWith<IllegalStateException> { store.read(Account.CLI_ACCOUNT_ID) }
    } }

    @Test fun signingOutDeletesTheFileOnlyIfTheCliDidNotSignInAgain() = withTemporaryDirectory { directory -> runTest {
        val (store, cli) = cliStore(directory)
        cli.save(CliConfig("https://team.example", "tc-1"))
        store.read(Account.CLI_ACCOUNT_ID)
        // teamcode login in a terminal between the app's read and its sign-out: the new session stays.
        cli.save(CliConfig("https://team.example", "tc-2"))
        store.delete(Account.CLI_ACCOUNT_ID)
        assertEquals("tc-2", cli.load()?.accessToken)
        store.read(Account.CLI_ACCOUNT_ID)
        store.delete(Account.CLI_ACCOUNT_ID)
        assertNull(cli.load())
        assertTrue(File(cli.lockPath).exists(), "config.lock is never removed")
    } }
}
