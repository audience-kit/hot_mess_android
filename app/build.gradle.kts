import java.util.Base64
import java.util.Properties

// AGP 9 compiles Kotlin itself (built-in Kotlin), so there's no separate Kotlin Android plugin.
plugins {
    id("com.android.application") // on the root build classpath; see ../build.gradle.kts
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Secrets that must not be committed (Firebase's app settings) come from local.properties or Gradle
// properties, e.g. `hotmess.firebase.apiKey=…`.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String): String =
    providers.gradleProperty("hotmess.$name").orNull ?: localProperties.getProperty("hotmess.$name") ?: ""

/** The settings that differ between Debug, Staging and Release, like the iOS app's xcconfig files. */
data class Environment(
    val name: String,
    val apiBaseUrl: String,
    val audienceId: String,
    val facebookAppId: String,
)

val environments = mapOf(
    // The emulator reaches the host's localhost at 10.0.2.2.
    "debug" to Environment("debug", "http://10.0.2.2:3000", "", "842337999153841"),
    "staging" to Environment("staging", "https://api.audiencekit.com", "", "915436455177328"),
    "release" to Environment("release", "https://api.audiencekit.com", "b0f8b66a-e636-495d-9475-0f5317ea08e0", "1168782378316790"),
)

android {
    namespace = "social.hotmess.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "social.hotmess.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The Facebook sign-in test's test user, from the environment (scripts/facebook-signin-test.sh
        // loads them). Base64 so a password's symbols survive `am instrument`'s command line.
        mapOf(
            "fbEmail" to "FB_TEST_ANDROID_EMAIL",
            "fbPassword" to "FB_TEST_ANDROID_PASSWORD",
            "fbName" to "FB_TEST_ANDROID_NAME",
        ).forEach { (argument, variable) ->
            providers.environmentVariable(variable).orNull?.takeIf { it.isNotEmpty() }?.let {
                testInstrumentationRunnerArguments[argument] = Base64.getEncoder().encodeToString(it.toByteArray())
            }
        }

        // Names the Hot Mess audience to the AudienceKit API for sign-in and branding.
        // hotmess.admin.audiencekit.com resolves by subdomain; switch to hotmess.social once that
        // domain is verified.
        buildConfigField("String", "AUDIENCE_HOST", "\"hotmess.admin.audiencekit.com\"")
        // Venues' iBeacons; their major value names a locale.
        buildConfigField("String", "BEACON_UUID", "\"1422F585-E729-49E3-9E1F-B943DE12BAA9\"")
        // Push needs a Firebase app. Leave these empty and the app runs without push.
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${secret("firebase.projectId")}\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${secret("firebase.applicationId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${secret("firebase.apiKey")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${secret("firebase.senderId")}\"")
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".development"
            versionNameSuffix = "-development"
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // UI tests sign in against the real API, and the test users only work on the Facebook app the
    // Hot Mess branding names, so they run on Staging rather than Debug's local API.
    testBuildType = "staging"

    buildTypes.configureEach {
        val environment = environments.getValue(name)
        buildConfigField("String", "API_BASE_URL", "\"${environment.apiBaseUrl}\"")
        buildConfigField("String", "AUDIENCE_ID", "\"${environment.audienceId}\"")
        buildConfigField("String", "FACEBOOK_APP_ID", "\"${environment.facebookAppId}\"")
        resValue(
            "string",
            "app_name",
            when (environment.name) {
                "debug" -> "Hot Mess Dev"
                "staging" -> "Hot Mess Staging"
                else -> "Hot Mess"
            },
        )
        manifestPlaceholders["usesCleartextTraffic"] = (environment.name == "debug").toString()
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.audiencekit.android)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.browser)
    // Facebook Login brings an old Fragment; the Activity Result API needs 1.3 or later.
    implementation(libs.androidx.fragment)
    implementation(libs.maplibre)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.uiautomator)
}
