@file:OptIn(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}
val releasePublicKeys = rootProject.fileTree("release/trust") { include("*.pem") }
val generatedReleaseTrust = layout.buildDirectory.dir("generated/release-trust")
// The Apple Team ID that signs TeamCode Desktop for macOS (-PteamcodeMacosTeamId=…, given by the release pipeline):
// `teamcode ui` installs a downloaded app only when its Developer ID signature names this team. Without it, the CLI
// installs the desktop app everywhere but on macOS, where it points at the release page instead. An empty value (the
// pipeline's repository variable when it is not set) counts as none.
val macosTeamId = providers.gradleProperty("teamcodeMacosTeamId").orNull?.takeIf { it.isNotBlank() }
    ?.also { require(Regex("[A-Z0-9]{10}").matches(it)) { "teamcodeMacosTeamId must be a 10-character Apple Team ID." } }
val generateReleaseTrust by tasks.registering {
    inputs.files(releasePublicKeys)
    inputs.property("macosTeamId", macosTeamId.orEmpty())
    outputs.dir(generatedReleaseTrust)
    doLast {
        fun der(tag: Int, content: ByteArray): ByteArray {
            val length = when {
                content.size < 128 -> byteArrayOf(content.size.toByte())
                content.size < 256 -> byteArrayOf(0x81.toByte(), content.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (content.size ushr 8).toByte(), content.size.toByte())
            }
            return byteArrayOf(tag.toByte()) + length + content
        }
        val identities = mutableSetOf<String>()
        val keys = releasePublicKeys.files.sortedBy { it.name }.map { file ->
            val text = file.readText()
            require(text.startsWith("-----BEGIN PUBLIC KEY-----") && text.trimEnd().endsWith("-----END PUBLIC KEY-----"))
            val spki = Base64.getMimeDecoder().decode(text.removePrefix("-----BEGIN PUBLIC KEY-----").substringBefore("-----END PUBLIC KEY-----"))
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki)) as RSAPublicKey
            require(key.encoded.contentEquals(spki)) { "Release roots must use canonical SubjectPublicKeyInfo encoding." }
            require(key.modulus.bitLength() == 3072 && key.publicExponent.toString() == "65537") { "Release roots must be RSA-3072 with exponent 65537." }
            val pkcs1 = der(0x30, der(0x02, key.modulus.toByteArray()) + der(0x02, key.publicExponent.toByteArray()))
            val id = MessageDigest.getInstance("SHA-256").digest(spki).joinToString("") { "%02x".format(it) }
            require(identities.add(id)) { "Release trust roots must be distinct." }
            "        ReleasePublicKey(\"${Base64.getEncoder().encodeToString(spki)}\", \"${Base64.getEncoder().encodeToString(pkcs1)}\"), // $id"
        }
        require(keys.size in 1..8) { "Ship at least one pinned release root." }
        val output = generatedReleaseTrust.get().file("me/nathanfallet/teamcode/cli/update/ShippedReleaseKeys.kt").asFile
        output.parentFile.mkdirs()
        output.writeText("package me.nathanfallet.teamcode.cli.update\n\ninternal object ShippedReleaseKeys {\n    val keys = listOf(\n${keys.joinToString("\n")}\n    )\n}\n")
        generatedReleaseTrust.get().file("me/nathanfallet/teamcode/cli/ui/DesktopPublisher.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package me.nathanfallet.teamcode.cli.ui\n\ninternal object DesktopPublisher {\n    val MACOS_TEAM_ID: String? = ${macosTeamId?.let { "\"$it\"" } ?: "null"}\n}\n")
        }
    }
}
kotlin {
    jvmToolchain(21)
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        if (!target.name.startsWith("mingw")) {
            target.compilations.getByName("main").cinterops.create("process") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/process.def"))
                includeDirs(project.file("src/nativeInterop/cinterop"))
            }
        } else {
            target.compilations.getByName("main").cinterops.create("windows") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/windows.def"))
                includeDirs(project.file("src/nativeInterop/cinterop"))
            }
        }
        if (target.name.startsWith("linux")) {
            // Keeps OpenSSL (linked by Ktor's Curl engine) usable until exit; see tls_exit.def.
            target.compilations.getByName("main").cinterops.create("tlsExit") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/tls_exit.def"))
            }
            // Test-only destructor that checks the TLS library is still usable once atexit() handlers have run.
            target.compilations.getByName("test").cinterops.create("tlsExitProbe") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/tls_exit_probe.def"))
            }
        }
        target.binaries.executable {
            entryPoint = "me.nathanfallet.teamcode.cli.main"
            baseName = "teamcode"
        }
        target.binaries.configureEach {
            if (target.name.startsWith("linux") && buildType.name == "DEBUG") {
                // Clikt 5.1.0 caches export the same internal symbol twice on Linux.
                // Re-evaluate this workaround on the next Kotlin/Clikt update.
                disableNativeCache(
                    version = DisableCacheInKotlinVersion.`2_4_20`,
                    reason = "Clikt 5.1.0 duplicate selfAndAncestors symbol in Linux static caches",
                )
            }
        }
    }
    sourceSets {
        // Shared POSIX implementation compiled with each platform's own process interop.
        macosMain.get().kotlin.srcDir("src/posixMain/kotlin")
        linuxMain.get().kotlin.srcDir("src/posixMain/kotlin")
        // Tests that need Unix sockets: the cloud runner only ever runs in a Linux pod, never on Windows.
        macosTest.get().kotlin.srcDir("src/posixTest/kotlin")
        linuxTest.get().kotlin.srcDir("src/posixTest/kotlin")
        commonMain { kotlin.srcDir(generateReleaseTrust) }
        commonMain.dependencies {
            implementation(projects.client)
            implementation(projects.config)
            implementation(libs.clikt)
            implementation(libs.mordant)
            implementation(libs.okio)
            implementation(libs.coroutines)
            implementation(libs.ktor.client.engine.defaults)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test); implementation(libs.ktor.client.mock) }
        jvmTest.dependencies {
            runtimeOnly(libs.junit)
            implementation(libs.ktor.server.tests)
            implementation(libs.ktor.client.mock)
        }
    }
}
