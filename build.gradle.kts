plugins {
    alias(libs.plugins.jvm) apply false
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.maven) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}
val teamcodeVersion = providers.gradleProperty("teamcodeVersion").orElse("0.1.0-SNAPSHOT").get()
val versionNumber = "(?:0|[1-9][0-9]*)"
val prereleasePart = "(?:$versionNumber|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
require(teamcodeVersion.length <= 80 && Regex("""$versionNumber\.$versionNumber\.$versionNumber(?:-$prereleasePart(?:\.$prereleasePart)*)?""").matches(teamcodeVersion)) {
    "Use a semantic TeamCode version such as 0.1.0 or 0.1.0-alpha.1."
}
allprojects {
    group = "me.nathanfallet.teamcode"
    version = teamcodeVersion
    project.ext.set("url", "https://github.com/nathanfallet/teamcode")
    project.ext.set("license.name", "Apache 2.0")
    project.ext.set("license.url", "https://www.apache.org/licenses/LICENSE-2.0.txt")
    project.ext.set("developer.id", "nathanfallet")
    project.ext.set("developer.name", "Nathan Fallet")
    project.ext.set("developer.email", "contact@nathanfallet.me")
    project.ext.set("developer.url", "https://www.nathanfallet.me")
    project.ext.set("scm.url", "https://github.com/nathanfallet/teamcode.git")
    // Two test JVMs per module; with org.gradle.parallel, modules also test side by side.
    tasks.withType<Test>().configureEach { maxParallelForks = 2 }
}
