plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.ktor)
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("me.nathanfallet.teamcode.ApplicationKt") }

// Multi-platform images can only be pushed to a registry; tarball and local builds pass a single platform.
val imagePlatforms = providers.gradleProperty("imagePlatforms").orElse("amd64,arm64").get().split(',').map { it.trim() }.filter { it.isNotEmpty() }
// Image tags come from the workflow as a comma-separated list; the first one names the image.
val imageTags = providers.gradleProperty("imageTags").orElse("latest").get().split(',').map { it.trim() }.filter { it.isNotEmpty() }
ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_21)
        localImageName.set("teamcode")
        imageTag.set(imageTags.first())
        portMappings.set(listOf(io.ktor.plugin.features.DockerPortMapping(8080, 8080, io.ktor.plugin.features.DockerPortMappingProtocol.TCP)))
        externalRegistry.set(
            io.ktor.plugin.features.DockerImageRegistry.dockerHub(
                appName = provider { "teamcode" },
                username = providers.environmentVariable("DOCKER_HUB_USERNAME").orElse("nathanfallet"),
                password = providers.environmentVariable("DOCKER_HUB_PASSWORD")
            )
        )
    }
}
jib {
    from {
        image = "eclipse-temurin:21-jre-jammy"
        platforms {
            imagePlatforms.forEach { arch -> platform { architecture = arch; os = "linux" } }
        }
    }
    to { tags = imageTags.drop(1).toSet() }
    container {
        user = "10001:10001"
        workingDirectory = "/opt/teamcode"
        environment = mapOf("TEAMCODE_HOST" to "0.0.0.0")
        labels.set(mapOf(
            "org.opencontainers.image.source" to "https://github.com/nathanfallet/teamcode",
            "org.opencontainers.image.licenses" to "Apache-2.0"
        ))
    }
}
dependencies {
    implementation(projects.api)
    implementation(projects.domain)
    implementation(projects.infrastructure)
    implementation(projects.presentation)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.logback)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testRuntimeOnly(libs.junit)
}
tasks.test { useJUnitPlatform() }

// Explicit local browser acceptance; ordinary CI keeps its existing Linux JVM checks.
tasks.register<JavaExec>("smokeAgentRuns") {
    group = "verification"
    description = "Verify actual native Claude/Codex launch metadata against owned loopback/MySQL fixtures"
    dependsOn(tasks.testClasses)
    if (!providers.gradleProperty("teamcodeCliBinary").isPresent) dependsOn(":cli:linkReleaseExecutableMacosArm64")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nathanfallet.teamcode.AgentRunNativeFixtureKt")
    args(rootProject.layout.projectDirectory.file("scripts/smoke-agent-runs.py").asFile.absolutePath,
        providers.gradleProperty("teamcodeCliBinary").getOrElse(rootProject.file("cli/build/bin/macosArm64/releaseExecutable/teamcode.kexe").absolutePath),
        layout.buildDirectory.file("preview/agent-runs-native.log").get().asFile.absolutePath)
}
tasks.register<JavaExec>("smokeForecastBrowser") {
    group = "verification"
    description = "Verify live forecasts with owned MySQL/Netty/Chrome fixtures (Node 22+ and Chrome required)"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nathanfallet.teamcode.ForecastBrowserFixtureKt")
    args(rootProject.layout.projectDirectory.file("scripts/smoke-forecast-browser.mjs").asFile.absolutePath,
        layout.buildDirectory.file("preview/forecasts-live.png").get().asFile.absolutePath)
}
ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_21)
        localImageName.set("teamcode")
        findProperty("imageTag")?.let { imageTag.set(it.toString()) }
        externalRegistry.set(io.ktor.plugin.features.DockerImageRegistry.externalRegistry(
            username = provider { "nathanfallet" },
            password = providers.environmentVariable("DOCKER_HUB_PASSWORD"),
            project = provider { "teamcode" },
            namespace = provider { "nathanfallet" },
        ))
    }
}
tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
}
