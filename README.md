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
2. Add the secrets the build needs to `local.properties` (never commit them):

   ```properties
   # Facebook Login client tokens, per build type
   hotmess.facebookClientToken.debug=...
   hotmess.facebookClientToken.staging=...
   hotmess.facebookClientToken.release=...

   # Firebase Cloud Messaging (optional; push is off without it)
   hotmess.firebase.projectId=...
   hotmess.firebase.applicationId=...
   hotmess.firebase.apiKey=...
   hotmess.firebase.senderId=...
   ```

   Without a client token the app runs, and sign-in says Facebook isn't set up for that build.
3. Open the project in Android Studio, or run `./gradlew installDebug`.

## Environments

| Build type | API | Facebook app | Application id |
| --- | --- | --- | --- |
| `debug` | `http://10.0.2.2:3000` (the API on your machine, from the emulator) | 842337999153841 | `social.hotmess.android.development` |
| `staging` | `https://api.audiencekit.com` | 915436455177328 | `social.hotmess.android.staging` |
| `release` | `https://api.audiencekit.com` | 1168782378316790 | `social.hotmess.android` |

Each Facebook app needs this app's package name and key hashes added under its Android platform.

## CI

`.github/workflows/build.yml` checks out audience-kit beside the app, so the repository needs an
`AUDIENCE_KIT_TOKEN` secret with read access to audience-kit/audience-kit.

## Credits

Figtree is by The Figtree Project Authors and licensed under the SIL Open Font License 1.1
(`design/Figtree-OFL.txt`). Map tiles are from
[OpenFreeMap](https://openfreemap.org), © OpenStreetMap contributors.
