# Hot Mess for Android

The Hot Mess app for Android: what's on near you, venues, people, event RSVPs and venue chat. It
matches the iOS app (audience-kit/hot_mess_ios) and is built with Jetpack Compose on the
AudienceKit Kotlin SDK, styled with the AudienceKit design system's `hot_mess` preset.

## Layout

- `core/`: plain Kotlin. The GraphQL documents, models, API calls, deep links, formatting and the
  venue chat protocol. Its tests run without an Android SDK: `./gradlew :core:test`.
- `app/`: the Android app. Theme and components (`ui/theme`, `ui/components`), screens
  (`ui/screens`), sign-in and branding (`session`), location and beacons (`location`), push (`push`).
- `design/`: the script that draws the launcher icons from the iOS silhouette.

## Setup

1. Clone [audience-kit](https://github.com/audience-kit/audience-kit) next to this repository
   (`../audience-kit`). The SDK in `sdk/kotlin` is included as a Gradle composite build. To keep it
   somewhere else, set `audiencekit.dir` in `local.properties` or `AUDIENCEKIT_DIR`.
2. Optionally, add Firebase's settings to `local.properties` for push (never commit them):

   ```properties
   hotmess.firebase.projectId=...
   hotmess.firebase.applicationId=...
   hotmess.firebase.apiKey=...
   hotmess.firebase.senderId=...
   ```

3. Open the project in Android Studio, or run `./gradlew installDebug`.

## Environments

| Build type | API | Facebook app | Application id |
| --- | --- | --- | --- |
| `debug` | `http://10.0.2.2:3000` (the API on your machine, from the emulator) | 842337999153841 | `social.hotmess.android.development` |
| `staging` | `https://api.audiencekit.com` | 915436455177328 | `social.hotmess.android.staging` |
| `release` | `https://api.audiencekit.com` | 1168782378316790 | `social.hotmess.android` |

Hot Mess is a consumer app: sign-in is classic Facebook Login on the build's Facebook app, asking
for `public_profile`, `email` and `user_friends`, like the iOS app. It opens Facebook's OAuth dialog
in a Custom Tab, which redirects to `fbconnect://cct.<application id>` with a code that the API
exchanges. Each Facebook app needs an Android platform listing this app's package names and key
hashes, and the redirect has to be accepted as a Valid OAuth Redirect URI.

## Facebook sign-in test

`FacebookSignInTest` (`app/src/androidTest`) signs in on a connected phone as a Facebook test user of
app 713525445368431 and checks the Me tab shows them. `scripts/facebook-signin-test.sh` loads
`FB_TEST_ANDROID_EMAIL`, `FB_TEST_ANDROID_PASSWORD` and `FB_TEST_ANDROID_NAME` from
`~/.config/audience-kit/fb_test_users.env` and runs it on the Staging build; without them it's
skipped. Sign-in uses Chrome's Facebook cookies, so log out of facebook.com in Chrome first. Like any
connected test run, it uninstalls the app when it finishes.

## CI

`.github/workflows/build.yml` checks out audience-kit beside the app. audience-kit is private, so
the repository has an `AUDIENCE_KIT_SSH_KEY` secret: the private half of a read-only deploy key on
audience-kit/audience-kit ("hot_mess_android CI (read-only)"). An `AUDIENCE_KIT_TOKEN` with read
access works too.

## Credits

Figtree is by The Figtree Project Authors and licensed under the SIL Open Font License 1.1
(`design/Figtree-OFL.txt`). Map tiles are from
[OpenFreeMap](https://openfreemap.org), © OpenStreetMap contributors.
