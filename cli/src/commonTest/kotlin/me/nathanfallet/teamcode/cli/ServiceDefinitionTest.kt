package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.service.ServiceSettings
import me.nathanfallet.teamcode.cli.service.serviceDefinition
import kotlin.test.*

class ServiceDefinitionTest {
    private fun definition(executable: String) = serviceDefinition(ServiceSettings(
        "a".repeat(32), 8789, "b".repeat(64), executable,
        "/tmp/teamcode/config.json", "/tmp/teamcode.service", "linux", "1000"))

    @Test fun ordinaryLinuxRegistrationsKeepTheirExistingCommandBytes() {
        assertEquals("ExecStart=\"/opt/team code/é/teamcode\" \"service\" \"serve\" \"--config\" \"/tmp/teamcode/config.json\"",
            definition("/opt/team code/é/teamcode").lineSequence().single { it.startsWith("ExecStart=") })
    }

    @Test fun executablePathsRejectedBySystemdArePassedAsAnEnvArgument() {
        for (path in listOf("/opt/O'Neill/teamcode", "/opt/a\"b/teamcode", "/opt/back\\slash/teamcode", "/opt/\$HOME/teamcode")) {
            val command = definition(path).lineSequence().single { it.startsWith("ExecStart=") }
            assertTrue(command.startsWith("ExecStart=\"/usr/bin/env\" \"--\" "), "Unsafe systemd executable token: $path")
            assertTrue(command.endsWith(" \"service\" \"serve\" \"--config\" \"/tmp/teamcode/config.json\""))
        }
    }
}
