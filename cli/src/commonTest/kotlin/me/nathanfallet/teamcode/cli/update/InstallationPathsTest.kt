package me.nathanfallet.teamcode.cli.update

import kotlin.test.*

class InstallationPathsTest {
    @Test fun explicitRootAndDataHomesDoNotRequireAnUnrelatedHomeVariable() {
        assertEquals("/explicit/app", defaultInstallationPaths("/explicit/app", "linux-x64") { error("Explicit root must not read the environment") }.root.toString())
        assertEquals("/data/teamcode", defaultInstallationPaths(target = "linux-arm64") { if (it == "XDG_DATA_HOME") "/data" else null }.root.toString())
        assertEquals("/data/TeamCode", defaultInstallationPaths(target = "macos-arm64") { if (it == "XDG_DATA_HOME") "/data" else null }.root.toString())
    }
    @Test fun platformDefaultsKeepPayloadsSeparateFromConfiguration() {
        val env: (String) -> String? = { if (it == "HOME") "/users/member" else null }
        assertEquals("/users/member/.local/share/teamcode", defaultInstallationPaths(target = "linux-x64", env = env).root.toString())
        assertEquals("/users/member/Library/Application Support/TeamCode", defaultInstallationPaths(target = "macos-x64", env = env).root.toString())
        assertFailsWith<IllegalArgumentException> { defaultInstallationPaths("relative", "linux-x64", env) }
    }
}
