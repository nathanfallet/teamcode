// The TeamCode Push relay (docs/notifications.md, "Relay protocol"): a small Kotlin/Native server, run on Cloud Run under
// the Firebase project of the published apps. It depends on :api only (the `PushRelay` wire models): no database, no
// domain, no encryption key. The JVM target runs the tests in ordinary CI; linuxX64 is the binary that is deployed.
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}
kotlin {
    jvmToolchain(21)
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    listOf(linuxX64(), macosArm64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "me.nathanfallet.teamcode.push.main"
            baseName = "teamcode-push"
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.api)
            implementation(libs.serialization)
            implementation(libs.coroutines)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.client.engine.defaults)
            implementation(libs.ktor.client.json)
            implementation(libs.ktor.json)
            implementation(libs.flareon.core)
            implementation(libs.flareon.messaging)
            implementation(libs.cryptography.core)
        }
        jvmMain.dependencies { implementation(libs.cryptography.provider.jdk) }
        nativeMain.dependencies { implementation(libs.cryptography.provider.openssl3.prebuilt) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.server.tests)
        }
        jvmTest.dependencies { runtimeOnly(libs.junit) }
    }
}
// flareon is built with cryptography 0.4.0; one version of its modules (0.6.0, which has Ed25519) for the whole binary.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "dev.whyoleg.cryptography") useVersion(libs.versions.cryptography.get())
    }
}
