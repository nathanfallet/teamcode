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

// versionCode from the TeamCode version: (major * 1 000 000 + minor * 1 000 + patch) * 100 + the store upload counter.
// fastlane passes the whole code as `companionVersionCode` (docs/companion.md, Distribution); other builds use counter 0.
val semver = Regex("""(\d+)\.(\d+)\.(\d+).*""").matchEntire(project.version.toString())!!.groupValues.drop(1).map(String::toInt)
val companionVersionCode = providers.gradleProperty("companionVersionCode").map(String::toInt)
    .orElse(maxOf(1, (semver[0] * 1_000_000 + semver[1] * 1_000 + semver[2]) * 100)).get()
// The upload key stays on the maintainer's Mac (fastlane/keystore.jks, git-ignored) with its passwords in SIGNING_*.
val uploadKeystore = rootProject.file("fastlane/keystore.jks")

android {
    namespace = "me.nathanfallet.teamcode.companion.android"
    compileSdk = libs.versions.android.appCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "me.nathanfallet.teamcode"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = companionVersionCode
        versionName = project.version.toString()
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}" }
    }
    signingConfigs {
        if (uploadKeystore.exists()) {
            create("release") {
                storeFile = uploadKeystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        // Without the upload key the release build is unsigned, and the fastlane lane refuses to run.
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
