plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}
// The member's local TeamCode configuration (config.json, installation-id, config.lock), shared by the native CLI and
// the JVM desktop app so both read and write the same files with the same code.
kotlin {
    jvmToolchain(21)
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    macosArm64(); linuxX64(); linuxArm64()
    mingwX64 { binaries.all { linkerOpts("-ladvapi32") } } // Token and security descriptor calls of the config lock.
    sourceSets {
        macosMain.get().kotlin.srcDir("src/posixMain/kotlin")
        linuxMain.get().kotlin.srcDir("src/posixMain/kotlin")
        macosTest.get().kotlin.srcDir("src/posixTest/kotlin")
        linuxTest.get().kotlin.srcDir("src/posixTest/kotlin")
        commonMain.dependencies {
            implementation(libs.serialization)
            implementation(libs.ktor.http)
            implementation(libs.okio)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { runtimeOnly(libs.junit) }
    }
}
