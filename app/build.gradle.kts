import java.time.Instant
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
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

// A ValueSource makes Gradle recheck the clock even when configuration caching is enabled.
// Seconds since 2026-01-01 UTC keep local and CI builds ordered without modifying tracked files.
abstract class AutomaticBuildNumber : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = (Instant.now().epochSecond - 1767225600L).toString()
}

val buildNumberText = providers.gradleProperty("hotmess.buildNumber")
    .orElse(providers.environmentVariable("BUILD_NUMBER"))
    .orElse(providers.of(AutomaticBuildNumber::class) {})
    .get()
val buildNumber = buildNumberText.toIntOrNull()
    ?.takeIf { it in 1..2100000000 }
    ?: error("Build number must be an integer between 1 and 2100000000; got '$buildNumberText'")

/** The settings that differ between Debug, Staging and Release, like the iOS app's xcconfig files. */
data class Environment(
    val name: String,
    val apiBaseUrl: String,
    val audienceId: String,
    val facebookAppId: String,
    /**
     * Staging calls the next preview (api.next.audiencekit.com), which runs the API as staging and signs people in
     * with Hot Mess's staging Facebook app. Debug runs against a local API.
     */
    val audienceKitEnvironment: String = "production",
    /** The Login for Business configuration, for a Business-type Facebook app. */
    val facebookLoginConfigId: String = "",
    /**
     * Opens Facebook's dialog in an ephemeral Custom Tab, which doesn't share Chrome's cookies, so
     * whoever is signed in to facebook.com in Chrome doesn't get in the sign-in test's way.
     */
    val privateSignIn: Boolean = false,
    /**
     * Names the Hot Mess audience to the API for sign-in and branding: its console host on that API's platform.
     * hotmess.admin.audiencekit.com resolves by subdomain; switch to hotmess.social once that domain is verified.
     */
    val audienceHost: String = "hotmess.admin.audiencekit.com",
)

val environments = mapOf(
    // The emulator reaches the host's localhost at 10.0.2.2.
    "debug" to Environment("debug", "http://10.0.2.2:3000", "", "842337999153841"),
    "staging" to Environment("staging", "https://api.next.audiencekit.com", "", "1660272792277019", "staging",
        audienceHost = "hotmess.admin.next.audiencekit.com"),
    // Staging, signing in with the AudienceKit platform app instead: Meta won't make new Facebook test users or
    // add the ones there are to another app, so this is the only app the sign-in test's users work on.
    "signInTest" to Environment("signInTest", "https://api.next.audiencekit.com", "", "713525445368431", "staging", "4085560021745660", privateSignIn = true,
        audienceHost = "hotmess.admin.next.audiencekit.com"),
    "release" to Environment("release", "https://api.audiencekit.com", "b0f8b66a-e636-495d-9475-0f5317ea08e0", "1168782378316790"),
)

android {
    namespace = "social.hotmess.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "social.hotmess.android"
        minSdk = 28
        targetSdk = 36
        versionCode = buildNumber
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The Facebook sign-in test's test user, from the environment (scripts/facebook-signin-test.sh
        // loads them). Base64 so a password's symbols survive `am instrument`'s command line.
        mapOf(
            "fbEmail" to "FB_TEST_ANDROID_EMAIL",
            "fbPassword" to "FB_TEST_ANDROID_PASSWORD",
            "fbName" to "FB_TEST_ANDROID_NAME",
            "fbAppId" to "FB_TEST_APP_ID",
        ).forEach { (argument, variable) ->
            providers.environmentVariable(variable).orNull?.takeIf { it.isNotEmpty() }?.let {
                testInstrumentationRunnerArguments[argument] = Base64.getEncoder().encodeToString(it.toByteArray())
            }
        }

        // Venues' iBeacons; their major value names a locale.
        buildConfigField("String", "BEACON_UUID", "\"1422F585-E729-49E3-9E1F-B943DE12BAA9\"")
        // Push needs a Firebase app. Leave these empty and the app runs without push.
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${secret("firebase.projectId")}\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${secret("firebase.applicationId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${secret("firebase.apiKey")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${secret("firebase.senderId")}\"")
    }

    // Release builds are signed with the Play upload key, kept outside the repository: `hotmess.upload.storeFile`,
    // `.storePassword`, `.keyAlias` and `.keyPassword` in local.properties. Without them Release is unsigned.
    // Google Play re-signs what it ships with the app signing key it manages.
    val uploadStoreFile = secret("upload.storeFile")
    if (uploadStoreFile.isNotEmpty()) {
        signingConfigs.create("upload") {
            storeFile = file(uploadStoreFile.replaceFirst(Regex("^~"), System.getProperty("user.home")))
            storePassword = secret("upload.storePassword")
            keyAlias = secret("upload.keyAlias").ifEmpty { "upload" }
            keyPassword = secret("upload.keyPassword").ifEmpty { secret("upload.storePassword") }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".development"
            versionNameSuffix = "-development"
        }
        getByName("release") {
            // The Play listing's package.
            applicationIdSuffix = ".app"
            signingConfigs.findByName("upload")?.let { signingConfig = it }
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
        create("signInTest") {
            initWith(getByName("staging"))
            applicationIdSuffix = ".signintest"
            versionNameSuffix = "-signintest"
        }
    }

    // UI tests sign in against the real API as Facebook test users, which only work on the platform
    // app, so they run on Sign-in Test: Staging on that app.
    testBuildType = "signInTest"
    // It looks like Staging.
    sourceSets.getByName("signInTest").res.srcDir("src/staging/res")

    buildTypes.configureEach {
        val environment = environments.getValue(name)
        buildConfigField("String", "API_BASE_URL", "\"${environment.apiBaseUrl}\"")
        buildConfigField("String", "AUDIENCE_HOST", "\"${environment.audienceHost}\"")
        buildConfigField("String", "AUDIENCE_ID", "\"${environment.audienceId}\"")
        buildConfigField("String", "FACEBOOK_APP_ID", "\"${environment.facebookAppId}\"")
        buildConfigField("String", "FACEBOOK_LOGIN_CONFIG_ID", "\"${environment.facebookLoginConfigId}\"")
        buildConfigField("boolean", "PRIVATE_SIGN_IN", environment.privateSignIn.toString())
        buildConfigField("String", "AUDIENCEKIT_ENVIRONMENT", "\"${environment.audienceKitEnvironment}\"")
        resValue(
            "string",
            "app_name",
            when (environment.name) {
                "debug" -> "Hot Mess Dev"
                "staging" -> "Hot Mess Staging"
                "signInTest" -> "Hot Mess Sign-in Test"
                else -> "Hot Mess"
            },
        )
        manifestPlaceholders["facebookAppId"] = environment.facebookAppId
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
    // Cover charge: Stripe's payment sheet or Square's card entry takes the payment, and Door mode scans
    // passes with the camera.
    implementation(libs.stripe.android)
    implementation(libs.square.card.entry)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.uiautomator)
}
