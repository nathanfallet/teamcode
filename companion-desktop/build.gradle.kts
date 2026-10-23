import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// The desktop app (macOS, Windows, Linux): the shared app of :companion, plus what only makes sense on a member's
// computer — the CLI's account, finding the CLI, sessions on this device and their supervision, the menu bar icon.
kotlin { jvmToolchain(21) }

dependencies {
    implementation(projects.companion)
    implementation(projects.config)
    implementation(projects.client)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.phosphor.icons)
    implementation(libs.coroutines.swing)
    implementation(libs.multiplatform.settings)
    implementation(libs.serialization)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.ui.test)
    testImplementation(libs.multiplatform.settings.test)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit)
}

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "me.nathanfallet.teamcode.companion.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "TeamCode"
            // Signed with a Developer ID and notarized only on a Mac that provides them (docs/companion.md, Distribution);
            // Windows installers stay unsigned in v1.
            macOS {
                bundleID = "me.nathanfallet.teamcode.desktop"
                providers.environmentVariable("TEAMCODE_MACOS_SIGNING_IDENTITY").orNull?.let { name ->
                    signing {
                        sign.set(true)
                        identity.set(name)
                    }
                }
                providers.environmentVariable("TEAMCODE_NOTARIZATION_APPLE_ID").orNull?.let { account ->
                    notarization {
                        appleID.set(account)
                        password.set(providers.environmentVariable("TEAMCODE_NOTARIZATION_PASSWORD"))
                        teamID.set(providers.environmentVariable("TEAMCODE_NOTARIZATION_TEAM_ID"))
                    }
                }
            }
            // Installers take MAJOR.MINOR.PATCH only, and macOS refuses a major version of 0: the TeamCode version
            // without its pre-release suffix, with 0.x packaged as 1.0.0.
            packageVersion = project.version.toString().substringBefore('-').let { if (it.startsWith("0.")) "1.0.0" else it }
            // The JDK modules the bundled runtime needs (from `suggestRuntimeModules`): preferences, the Java HTTP
            // engine and the keyring's native access.
            // jdk.crypto.ec is a service module the scan cannot see: without it, TLS to ECDSA servers fails.
            // jdk.security.auth: the CLI's config.json is written owner-only on Windows (NTSystem, :config).
            modules("java.instrument", "java.management", "java.net.http", "java.prefs", "jdk.crypto.ec", "jdk.security.auth", "jdk.unsupported")
        }
    }
}
