plugins { alias(libs.plugins.jvm); alias(libs.plugins.serialization) }
kotlin { jvmToolchain(21) }
dependencies {
    api(projects.api)
    api(projects.domain)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.json)
    implementation(libs.ktor.server.errors)
    implementation(libs.ktor.json)
    testImplementation(projects.client)
    testImplementation(libs.ktor.server.tests)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.client.cio)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit)
}
tasks.test { useJUnitPlatform() }
