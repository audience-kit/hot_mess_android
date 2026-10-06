package social.hotmess.android

import com.audiencekit.AudienceKitConfiguration

/** The settings each build type bakes in (see app/build.gradle.kts), like the iOS xcconfig files. */
data class AppConfiguration(
    val baseUrl: String = BuildConfig.API_BASE_URL,
    val audienceHost: String = BuildConfig.AUDIENCE_HOST,
    val audienceId: String? = BuildConfig.AUDIENCE_ID.ifBlank { null },
    val facebookAppId: String = BuildConfig.FACEBOOK_APP_ID,
    val beaconUuid: String? = BuildConfig.BEACON_UUID.ifBlank { null },
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
) {
    /** How the AudienceKit SDK reaches the Hot Mess audience. */
    val audienceKit: AudienceKitConfiguration
        get() = AudienceKitConfiguration(
            baseUrl = baseUrl,
            host = audienceHost,
            audienceId = audienceId,
            facebookAppId = facebookAppId,
        )

    /** Which Facebook app the build signs in with, for the Me screen. */
    val facebookEnvironment: String
        get() = when (facebookAppId) {
            "1168782378316790" -> "production"
            "713525445368431" -> "AudienceKit platform"
            "915436455177328" -> "staging"
            "842337999153841" -> "development"
            else -> "unknown"
        }

    /** The API serves every user's avatar from the same path. */
    fun avatarUrl(userId: String): String = "${baseUrl.trimEnd('/')}/users/${userId.uppercase()}/picture"
}
