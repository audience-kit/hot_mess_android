// The Kotlin and Android Gradle plugins must load in the same (root) classloader: AGP 9's built-in
// Kotlin support reaches into KGP. The Android plugin is only put on the classpath when there's an
// Android SDK (the same check as settings.gradle.kts), so building :core never needs Google's Maven.
buildscript {
    val localProperties = rootDir.resolve("local.properties")
    val hasAndroidSdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { System.getenv(it) != null } ||
        (localProperties.exists() && localProperties.readText().contains("sdk.dir"))
    if (hasAndroidSdk) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            // Keep in step with `agp` in gradle/libs.versions.toml.
            classpath("com.android.tools.build:gradle:9.3.3")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
