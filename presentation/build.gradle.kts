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

// Local, content-free UI inspection; never connects to a provider or operator database.
tasks.register<JavaExec>("previewForecastDashboard") {
    group = "verification"
    description = "Render the real forecast dashboard with synthetic observations into build/preview/forecasts.html"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nathanfallet.teamcode.presentation.ForecastPreviewKt")
    args(layout.buildDirectory.file("preview/forecasts.html").get().asFile.absolutePath)
}
tasks.register<JavaExec>("previewAccountsDashboard") {
    group = "verification"
    description = "Render the real subscription accounts page with synthetic pending accounts into build/preview/accounts.html"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nathanfallet.teamcode.presentation.AccountsPreviewKt")
    args(layout.buildDirectory.file("preview/accounts.html").get().asFile.absolutePath)
}
