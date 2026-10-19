plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.ktor)
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("me.nathanfallet.teamcode.ApplicationKt") }
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
    testImplementation(libs.ktor.server.tests)
    testRuntimeOnly(libs.junit)
}
tasks.test { useJUnitPlatform() }
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
