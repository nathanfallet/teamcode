package me.nathanfallet.teamcode.cli.service

import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.withTimeout
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import me.nathanfallet.teamcode.cli.platform.servicePlatform
import me.nathanfallet.teamcode.cli.platform.runInteractiveProcess
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import okio.FileSystem
import okio.Path.Companion.toPath

/** Only fixed OS tools receive elevation; the proxy continues running as its configuration owner. */
internal data class ServiceManagerInvocation(val command: String, val arguments: List<String>)

/** A saved boot registration cannot redirect privileged setup to another OS account. */
internal fun requireBootServiceIdentity(settings: ServiceSettings, identity: ServicePlatform = servicePlatform()) {
    require(settings.os == identity.os && settings.userId == identity.userId)
    if (settings.os == "macos") require(settings.userName == identity.userName && settings.userHome == identity.userHome)
}

internal suspend fun installServiceRegistration(settings: ServiceSettings) {
    if (!settings.atBoot || settings.os != "macos") { writeRegistration(settings); return }
    if (ownsRegistration(settings)) return
    val directory = FileSystem.SYSTEM.metadataOrNull("/Library/LaunchDaemons".toPath())
    require(directory?.isDirectory == true && directory.symlinkTarget == null)
    val staging = settings.config.toPath().parent!!.resolve("boot-registration-${randomHex(8)}.plist")
    writePrivateFile(staging.toString(), serviceDefinition(settings))
    try {
        privilegedServiceOperation(ServiceManagerInvocation("/usr/bin/sudo", listOf("/usr/bin/install", "-o", "root", "-g", "wheel", "-m", "0644", staging.toString(), settings.registration)))
        if (!ownsRegistration(settings)) throw CliktError("The installed boot registration differs from this configuration.")
    } finally { FileSystem.SYSTEM.delete(staging, mustExist = false) }
}

internal suspend fun removeServiceRegistration(settings: ServiceSettings) {
    require(ownsRegistration(settings))
    if (settings.atBoot && settings.os == "macos") {
        privilegedServiceOperation(ServiceManagerInvocation("/usr/bin/sudo", listOf("/bin/rm", "--", settings.registration)))
    } else FileSystem.SYSTEM.delete(settings.registration.toPath())
}

internal suspend fun enableLinuxBootStartup(settings: ServiceSettings) {
    require(settings.os == "linux" && settings.atBoot && settings.userId != "0")
    // Linger is shared user-manager policy. Never disable it during TeamCode uninstall.
    privilegedServiceOperation(ServiceManagerInvocation("/usr/bin/sudo", listOf("/usr/bin/loginctl", "enable-linger", settings.userId)))
}

private suspend fun privilegedServiceOperation(invocation: ServiceManagerInvocation) {
    val result = withTimeout(180_000) { runInteractiveProcess(invocation.command, invocation.arguments, emptyMap()) }
    if (result != 0) throw CliktError("OS authorization failed. Retry service install after checking administrator access.")
}

/** Task Scheduler protects the password; it never enters TeamCode files, environment or arguments. */
internal fun windowsBootRegistrationScript(): String = """
    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}passwordPointer = [IntPtr]::Zero
    try {
        ${'$'}identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        ${'$'}principal = New-Object Security.Principal.WindowsPrincipal(${'$'}identity)
        if (-not ${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Elevation required' }
        if (${'$'}identity.User.Value -ne ${'$'}env:TEAMCODE_SERVICE_USER) { throw 'Different identity' }
        ${'$'}scheduler = New-Object -ComObject 'Schedule.Service'
        ${'$'}scheduler.Connect()
        ${'$'}xml = Get-Content -LiteralPath ${'$'}env:TEAMCODE_SERVICE_REGISTRATION -Raw
        ${'$'}secret = Read-Host 'Windows account password for startup before login (not your PIN or TeamCode password)' -AsSecureString
        ${'$'}passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR(${'$'}secret)
        ${'$'}password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(${'$'}passwordPointer)
        ${'$'}null = ${'$'}scheduler.GetFolder('\').RegisterTask(${'$'}env:TEAMCODE_SERVICE_TASK, ${'$'}xml, 2, ${'$'}identity.Name, ${'$'}password, 1, ${'$'}null)
        exit 0
    } catch {
        [Console]::Error.WriteLine('Boot task registration failed. Check elevation, Windows credentials and local/domain task policy.')
        exit 2
    } finally {
        ${'$'}password = ${'$'}null
        if (${'$'}passwordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR(${'$'}passwordPointer) }
        if (${'$'}secret) { ${'$'}secret.Dispose() }
    }
""".trimIndent()

/** Refuse control of a boot task whose effective OS identity, command or trigger changed. */
internal fun windowsBootTaskCheck(): String = """
    ${'$'}definition = ${'$'}task.Definition
    ${'$'}owner = ${'$'}definition.Principal.UserId
    if (-not ${'$'}owner.StartsWith('S-1-')) {
        ${'$'}owner = (New-Object Security.Principal.NTAccount(${'$'}owner)).Translate([Security.Principal.SecurityIdentifier]).Value
    }
    if (${'$'}owner -ne ${'$'}env:TEAMCODE_SERVICE_USER -or ${'$'}definition.Principal.LogonType -ne 1 -or ${'$'}definition.Principal.RunLevel -ne 0) { exit 5 }
    if (${'$'}definition.Triggers.Count -ne 1 -or ${'$'}definition.Triggers.Item(1).Type -ne 8) { exit 5 }
    if (${'$'}definition.Actions.Count -ne 1 -or ${'$'}definition.Actions.Item(1).Type -ne 0) { exit 5 }
    if (${'$'}definition.Actions.Item(1).Path -ne ${'$'}env:TEAMCODE_SERVICE_EXECUTABLE -or ${'$'}definition.Actions.Item(1).Arguments -cne ${'$'}env:TEAMCODE_SERVICE_ARGUMENTS) { exit 5 }
""".trimIndent()
