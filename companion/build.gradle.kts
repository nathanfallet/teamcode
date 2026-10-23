import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

kotlin {
    jvmToolchain(21)
    // Desktop (macOS, Windows, Linux); its entry point is jvmMain's main().
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Companion"
            isStatic = true
        }
    }
    androidLibrary {
        namespace = "me.nathanfallet.teamcode.companion"
        compileSdk = libs.versions.android.appCompileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        androidResources { enable = true }
        withHostTest { isIncludeAndroidResources = true }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.client)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.phosphor.icons)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)
            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.serialization)
            implementation(libs.multiplatform.settings)
            implementation(libs.markdown.renderer.m3)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.android)
        }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
            implementation(libs.ktor.client.java)
            implementation(libs.java.keyring)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.ui.test)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.junit)
            implementation(libs.compose.ui.test)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "me.nathanfallet.teamcode.companion.resources"
}

compose.desktop {
    application {
        mainClass = "me.nathanfallet.teamcode.companion.MainKt"
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
            modules("java.instrument", "java.management", "java.net.http", "java.prefs", "jdk.crypto.ec", "jdk.security.auth", "jdk.unsupported")
        }
    }
}
