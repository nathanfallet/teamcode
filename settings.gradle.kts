rootProject.name = "teamcode"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    repositories {
        // Google's repository serves only the Android plugin, AndroidX and Google's Android groups (never com.google.* libraries
        // such as Guava or protobuf, which keep resolving from Maven Central), for the mobile targets of :api and :client and the companion app.
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google.android"); includeGroupAndSubgroups("com.google.testing.platform") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google.android"); includeGroupAndSubgroups("com.google.testing.platform") } }
        mavenCentral()
    }
}
include(":api", ":client", ":domain", ":infrastructure", ":presentation", ":app", ":cli", ":companion", ":companion-android")
