pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // Square's In-App Payments SDK, for Square venues' cover, is only published here.
        maven("https://sdk.squareup.com/public/android") {
            content { includeGroupByRegex("com\\.squareup\\.sdk.*") }
        }
    }
}

rootProject.name = "hot_mess_android"

// The AudienceKit Kotlin SDK (audience-kit/audience-kit, sdk/kotlin) isn't published to a Maven
// repository yet, so it's built from a checkout as a composite build. Point at one with
// `audiencekit.dir` in gradle.properties or local.properties, or AUDIENCEKIT_DIR; otherwise a clone
// next to this one (../audience-kit) or inside it (audience-kit/, which CI uses) is picked up.
val localProperties = java.util.Properties().apply {
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val audienceKitSdk = listOfNotNull(
    providers.gradleProperty("audiencekit.dir").orNull,
    localProperties.getProperty("audiencekit.dir"),
    System.getenv("AUDIENCEKIT_DIR"),
    "../audience-kit",
    "audience-kit",
).map { file(it).resolve("sdk/kotlin") }
    .firstOrNull { it.resolve("settings.gradle.kts").exists() }
    ?: error(
        "The AudienceKit Kotlin SDK wasn't found. Clone audience-kit/audience-kit next to this " +
            "repository, or set audiencekit.dir in local.properties.",
    )

// The SDK build only includes :audiencekit-android (and AGP only finds the Android SDK) through
// ANDROID_HOME or its own local.properties, so pass on an sdk.dir that's only set here.
localProperties.getProperty("sdk.dir")?.let { sdkDir ->
    val sdkLocalProperties = audienceKitSdk.resolve("local.properties")
    val existing = java.util.Properties().apply {
        sdkLocalProperties.takeIf { it.exists() }?.inputStream()?.use(::load)
    }
    if (existing.getProperty("sdk.dir") == null) {
        existing.setProperty("sdk.dir", sdkDir)
        sdkLocalProperties.outputStream().use { existing.store(it, "sdk.dir from hot_mess_android") }
    }
}
includeBuild(audienceKitSdk)

// The app's logic: models, the GraphQL documents, chat and formatting. Plain Kotlin/JVM, so its
// tests run with only a JDK.
include(":core")

// The Android app needs the Android SDK. Without one only :core builds; CI always has one.
val hasAndroidSdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { System.getenv(it) != null } ||
    localProperties.getProperty("sdk.dir") != null
if (hasAndroidSdk) include(":app")
