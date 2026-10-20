import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.maven)
}

mavenPublishing {
    // Empty javadoc jars, as in kourier: sources are published, API docs live in the repository.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set(project.name)
        description.set("Shared API contract of TeamCode: resources and serializable models.")
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

val generatedVersionDirectory = layout.buildDirectory.dir("generated/teamcode-version")
val generateBuildInfo by tasks.registering {
    inputs.property("version", project.version.toString())
    outputs.dir(generatedVersionDirectory)
    doLast {
        val output = generatedVersionDirectory.get().file("me/nathanfallet/teamcode/api/BuildInfo.kt").asFile
        output.parentFile.mkdirs()
        output.writeText("package me.nathanfallet.teamcode.api\n\nobject BuildInfo { const val VERSION: String = \"${project.version}\" }\n")
    }
}
kotlin {
    jvmToolchain(21)
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    macosArm64(); macosX64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        commonMain { kotlin.srcDir(generateBuildInfo) }
        commonMain.dependencies {
            api(libs.serialization)
            api(libs.ktor.resources)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        jvmTest.dependencies { runtimeOnly(libs.junit) }
    }
}
