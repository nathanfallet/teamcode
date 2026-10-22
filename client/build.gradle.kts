import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.maven)
    alias(libs.plugins.android.kmp.library)
}
mavenPublishing {
    // Empty javadoc jars, as in kourier: sources are published, API docs live in the repository.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set(project.name)
        description.set("Ktor client for the TeamCode API.")
        url.set(project.ext.get("url")?.toString())
        licenses {
            license {
                name.set(project.ext.get("license.name")?.toString())
                url.set(project.ext.get("license.url")?.toString())
            }
        }
        developers {
            developer {
                id.set(project.ext.get("developer.id")?.toString())
                name.set(project.ext.get("developer.name")?.toString())
                email.set(project.ext.get("developer.email")?.toString())
                url.set(project.ext.get("developer.url")?.toString())
            }
        }
        scm {
            url.set(project.ext.get("scm.url")?.toString())
        }
    }
}

kotlin {
    jvmToolchain(21)
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    macosArm64(); macosX64(); linuxX64(); linuxArm64(); mingwX64()
    // Mobile targets for the companion app; published with the others.
    iosArm64(); iosSimulatorArm64()
    androidLibrary {
        namespace = "me.nathanfallet.teamcode.client"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // D8 and minSdk 26 read Java 17 bytecode; the shared toolchain stays on 21.
        compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
        withHostTest {}
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.api)
            api(libs.ktor.client.core)
            implementation(libs.coroutines)
            implementation(libs.ktor.client.resources)
            implementation(libs.ktor.client.json)
            implementation(libs.ktor.json)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test); implementation(libs.ktor.client.mock) }
        jvmTest.dependencies { runtimeOnly(libs.junit) }
    }
}
