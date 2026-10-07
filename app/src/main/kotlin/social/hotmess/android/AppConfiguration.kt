package social.hotmess.android

import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.AudienceKitEnvironment

/** The settings each build type bakes in (see app/build.gradle.kts), like the iOS xcconfig files. */
data class AppConfiguration(
    val baseUrl: String = BuildConfig.API_BASE_URL,
    val audienceHost: String = BuildConfig.AUDIENCE_HOST,
    val audienceId: String? = BuildConfig.AUDIENCE_ID.ifBlank { null },
    val facebookAppId: String = BuildConfig.FACEBOOK_APP_ID,
    /** Staging builds tell the API so, which then signs people in with Hot Mess's staging Facebook app. */
    val environment: AudienceKitEnvironment =
        AudienceKitEnvironment.entries.firstOrNull { it.value == BuildConfig.AUDIENCEKIT_ENVIRONMENT }
            ?: AudienceKitEnvironment.PRODUCTION,
    val beaconUuid: String? = BuildConfig.BEACON_UUID.ifBlank { null },
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    /** Staging and debug builds, which get testing aids such as pretending to be at a venue. */
    val isTestBuild: Boolean = BuildConfig.DEBUG || environment != AudienceKitEnvironment.PRODUCTION,
) {
    /** How the AudienceKit SDK reaches the Hot Mess audience. */
    val audienceKit: AudienceKitConfiguration
        get() = AudienceKitConfiguration(
            baseUrl = baseUrl,
            host = audienceHost,
            audienceId = audienceId,
            facebookAppId = facebookAppId,
            environment = environment,
        )

    /** Which Facebook app the build signs in with, for the Me screen. */
    val facebookEnvironment: String
        get() = when (facebookAppId) {
            "1168782378316790" -> "production"
            "713525445368431" -> "AudienceKit platform"
            "1660272792277019" -> "staging"
            "842337999153841" -> "development"
            else -> facebookAppId
        }

    /** The API serves every user's avatar from the same path. */
    fun avatarUrl(userId: String): String = "${baseUrl.trimEnd('/')}/users/${userId.uppercase()}/picture"
}
