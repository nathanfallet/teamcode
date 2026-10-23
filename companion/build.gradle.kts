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
    // Desktop (macOS, Windows, Linux): the shared app on the JVM, packaged by :companion-desktop.
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
            // The real server application, for discovery and sign-in over a socket (GatewayDiscoveryTest).
            implementation(projects.presentation)
            implementation(libs.ktor.server.cio)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "me.nathanfallet.teamcode.companion.resources"
}
