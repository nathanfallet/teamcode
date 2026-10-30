// The MCP stdio host (docs/mcp.md): a small Ktor server that runs team MCP servers published on npm or PyPI as
// supervised child processes. It depends on :api only: no database, no domain, no encryption key.
plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ktor)
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("me.nathanfallet.teamcode.mcp.MainKt") }
dependencies {
    implementation(projects.api)
    implementation(libs.serialization)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.ktor.client.cio)
    testRuntimeOnly(libs.junit)
}
tasks.test {
    useJUnitPlatform()
    // The stdio fixture server is a Node.js script, as the real servers the host runs.
    environment("TEAMCODE_MCP_TEST_NODE", providers.environmentVariable("TEAMCODE_MCP_TEST_NODE").orElse("").get())
    // Opt-in: the real npm and PyPI packages (network). See docs/mcp.md, "Measurements".
    environment("TEAMCODE_MCP_REAL_PACKAGES", providers.environmentVariable("TEAMCODE_MCP_REAL_PACKAGES").orElse("").get())
}
tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
}
