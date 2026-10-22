plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

dependencies {
    implementation(projects.companion)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

// versionCode from the TeamCode version: major * 1 000 000 + minor * 1 000 + patch (store builds add their counter).
val semver = Regex("""(\d+)\.(\d+)\.(\d+).*""").matchEntire(project.version.toString())!!.groupValues.drop(1).map(String::toInt)

android {
    namespace = "me.nathanfallet.teamcode.companion.android"
    compileSdk = libs.versions.android.appCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "me.nathanfallet.teamcode"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = maxOf(1, semver[0] * 1_000_000 + semver[1] * 1_000 + semver[2])
        versionName = project.version.toString()
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}" }
    }
    buildTypes {
        // Release signing (upload key, SIGNING_* variables) comes with store distribution; nothing secret is in the repository.
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
