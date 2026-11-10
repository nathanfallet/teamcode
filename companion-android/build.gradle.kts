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
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

// versionCode from the TeamCode version: (major * 1 000 000 + minor * 1 000 + patch) * 100 + the store upload counter.
// fastlane passes the whole code as `companionVersionCode` (docs/companion.md, Distribution); other builds use counter 0.
val semver = Regex("""(\d+)\.(\d+)\.(\d+).*""").matchEntire(project.version.toString())!!.groupValues.drop(1).map(String::toInt)
val companionVersionCode = providers.gradleProperty("companionVersionCode").map(String::toInt)
    .orElse(maxOf(1, (semver[0] * 1_000_000 + semver[1] * 1_000 + semver[2]) * 100)).get()
// Play's ceiling, also checked by the fastlane lane: above it the code would wrap around instead of failing.
check(semver[0] <= 20 && companionVersionCode in 1..2_100_000_000) { "versionCode $companionVersionCode is outside 1..2100000000 (TeamCode major versions up to 20)." }
// The upload key stays on the maintainer's Mac (fastlane/keystore.jks, git-ignored) with its passwords in SIGNING_*.
val uploadKeystore = rootProject.file("fastlane/keystore.jks")

// Push (docs/notifications.md, "Push"): the Firebase project of the published app, read from google-services.json when the
// file is there. Without it the app builds and runs without push. The Google Services plugin is not used: these four
// values are all FCM needs, and the app initializes Firebase with them (CompanionApplication).
val firebase: Map<String, String> = file("google-services.json").takeIf { it.exists() }?.let { json ->
    @Suppress("UNCHECKED_CAST")
    val root = groovy.json.JsonSlurper().parse(json) as Map<String, Any?>
    val project = root["project_info"] as Map<String, Any?>
    val client = (root["client"] as List<Map<String, Any?>>).firstOrNull {
        ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "me.nathanfallet.teamcode"
    } ?: error("google-services.json has no Android app me.nathanfallet.teamcode.")
    mapOf(
        "FIREBASE_APP_ID" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"].toString(),
        "FIREBASE_API_KEY" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"]).toString(),
        "FIREBASE_PROJECT_ID" to project["project_id"].toString(),
        "FIREBASE_SENDER_ID" to project["project_number"].toString(),
    )
} ?: emptyMap()

android {
    namespace = "me.nathanfallet.teamcode.companion.android"
    compileSdk = libs.versions.android.appCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "me.nathanfallet.teamcode"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = companionVersionCode
        versionName = project.version.toString()
        for (name in listOf("FIREBASE_APP_ID", "FIREBASE_API_KEY", "FIREBASE_PROJECT_ID", "FIREBASE_SENDER_ID"))
            buildConfigField("String", name, "\"${firebase[name].orEmpty()}\"")
    }
    buildFeatures { buildConfig = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}" }
    }
    signingConfigs {
        // The key and its passwords together: a keystore alone leaves the release build unsigned, as documented.
        val signing = listOf("SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD").map { System.getenv(it) }
        if (uploadKeystore.exists() && signing.none { it.isNullOrEmpty() }) {
            create("release") {
                storeFile = uploadKeystore
                storePassword = signing[0]
                keyAlias = signing[1]
                keyPassword = signing[2]
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
