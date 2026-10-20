plugins {
    alias(libs.plugins.jvm) apply false
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.ktor) apply false
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
}
