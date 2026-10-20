package me.nathanfallet.teamcode.cli

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.service.*
import kotlin.test.*

class BootServiceTest {
    private val id = "a".repeat(32)
    private val token = "b".repeat(64)
    private fun mac(atBoot: Boolean = true, name: String? = "fixture", registration: String = "/Library/LaunchDaemons/me.nathanfallet.teamcode.$id.plist") = ServiceSettings(
        id, 8789, token, "/opt/team code/teamcode", "/Users/fixture/config & space/config.json", registration,
        "macos", "501", atBoot, name, "/Users/fixture")

    @Test fun bootDaemonKeepsTheUserIdentityAndBindsHeadlessConfigurationWithoutSecrets() {
        val definition = serviceDefinition(mac())
        assertTrue(definition.startsWith("<?xml"))
        assertTrue(definition.contains("<key>UserName</key><string>fixture</string>"))
        assertTrue(definition.contains("<key>HOME</key><string>/Users/fixture</string>"))
        assertTrue(definition.contains("<key>TEAMCODE_CONFIG</key><string>/Users/fixture/config &amp; space/config.json</string>"))
        assertTrue(definition.contains("<key>ExitTimeOut</key><integer>660</integer>"))
        assertFalse(definition.contains(token))
        assertFalse(serviceDefinition(mac(false)).contains("<key>UserName</key>"))
    }

    @Test fun privilegedMacPathsAndIdentitiesAreBoundToTheGeneratedLabel() {
        assertFailsWith<IllegalArgumentException> { mac(registration = "/Library/LaunchDaemons/unrelated.plist") }
        assertFailsWith<IllegalArgumentException> { mac(name = null) }
        assertFailsWith<IllegalArgumentException> { mac(name = "root\nAnotherKey") }
        assertFailsWith<IllegalArgumentException> { ServiceSettings(id, 8789, token, "/opt/teamcode", "/tmp/config.json",
            "/Library/LaunchDaemons/me.nathanfallet.teamcode.$id.plist", "macos", "0", true, "root", "/var/root") }
    }

    @Test fun savedBootIdentityCannotBeChangedToAnotherAccount() {
        val identity = me.nathanfallet.teamcode.cli.platform.ServicePlatform("macos", "/opt/teamcode", "501", "fixture", "/Users/fixture")
        requireBootServiceIdentity(mac(), identity)
        assertFailsWith<IllegalArgumentException> { requireBootServiceIdentity(mac(name = "root"), identity) }
        assertFailsWith<IllegalArgumentException> { requireBootServiceIdentity(mac(), identity.copy(userId = "0")) }
        assertFailsWith<IllegalArgumentException> { requireBootServiceIdentity(mac(), identity.copy(userHome = "/different")) }
    }

    @Test fun legacySettingsRetainLoginStartupAndTheirOriginalManifest() {
        val record = Serialization.json.encodeToJsonElement(ServiceSettings.serializer(), mac(false)).jsonObject
        val legacy = JsonObject(record - setOf("atBoot", "userName", "userHome"))
        val decoded = Serialization.json.decodeFromJsonElement(ServiceSettings.serializer(), legacy)
        assertFalse(decoded.atBoot); assertNull(decoded.userName)
        assertEquals(serviceDefinition(mac(false)), serviceDefinition(decoded))
    }

    @Test fun windowsBootUsesPasswordLogonAndNeverInteractiveTokenOrS4u() {
        val settings = ServiceSettings(id, 8789, token, "C:\\Team Code\\teamcode.exe", "C:\\Users\\fixture\\.config\\teamcode\\config.json",
            "C:\\Users\\fixture\\.config\\teamcode\\service-task.xml", "windows", "S-1-5-21-123-456-789-1001", true)
        val definition = serviceDefinition(settings)
        assertTrue(definition.contains("<BootTrigger>")); assertFalse(definition.contains("<LogonTrigger>"))
        assertTrue(definition.contains("<LogonType>Password</LogonType>")); assertTrue(definition.contains("<RunLevel>LeastPrivilege</RunLevel>"))
        assertFalse(definition.contains("InteractiveToken")); assertFalse(definition.contains("S4U")); assertFalse(definition.contains(token))
        val script = windowsBootRegistrationScript()
        assertTrue(script.contains("-AsSecureString")); assertTrue(script.contains("ZeroFreeBSTR"))
        assertTrue(script.contains("WindowsBuiltInRole")); assertTrue(script.contains("RegisterTask("))
        val ownership = windowsBootTaskCheck()
        assertTrue(ownership.contains("LogonType -ne 1")); assertTrue(ownership.contains("RunLevel -ne 0"))
        assertTrue(ownership.contains("Type -ne 8")); assertTrue(ownership.contains("TEAMCODE_SERVICE_ARGUMENTS"))
    }
}
