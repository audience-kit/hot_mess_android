# Hot Mess for Android

The Hot Mess app for Android: what's on near you, venues, people, event RSVPs, venue chat and paying
cover. It
matches the iOS app (audience-kit/hot_mess_ios) and is built with Jetpack Compose on the
AudienceKit Kotlin SDK, styled with the AudienceKit design system's `hot_mess` preset.

## Layout

- `core/`: plain Kotlin. The GraphQL documents, models, API calls, deep links, formatting and the
  venue chat protocol. Its tests run without an Android SDK: `./gradlew :core:test`.
- `app/`: the Android app. Theme and components (`ui/theme`, `ui/components`), screens
  (`ui/screens`), sign-in and branding (`session`), location and beacons (`location`), push (`push`),
  and paying cover with Stripe's payment sheet (`payments`).
- `design/`: the script that draws the launcher icons (the iOS "misprint" mark: Production blue, staging green, debug purple; `python3 design/make_launcher_icons.py`, needs cairosvg and Pillow).

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

3. To sign Release for Google Play, point `local.properties` at the upload keystore (kept outside the
   repository, never committed). Google Play manages the app signing key and re-signs what it ships.

   ```properties
   hotmess.upload.storeFile=~/.config/audience-kit/hotmess-upload.p12
   hotmess.upload.keyAlias=upload
   hotmess.upload.storePassword=...
   ```

   Then `./gradlew :app:bundleRelease` writes `app/build/outputs/bundle/release/app-release.aab`.

4. Open the project in Android Studio, or run `./gradlew installDebug`.

## Environments

| Build type | API | Facebook app | Application id |
| --- | --- | --- | --- |
| `debug` | `http://10.0.2.2:3000` (the API on your machine, from the emulator) | 842337999153841 | `social.hotmess.android.development` |
| `staging` | `https://api-staging.audiencekit.com` | 1660272792277019 | `social.hotmess.android.staging` |
| `release` | `https://api.audiencekit.com` | 1168782378316790 | `social.hotmess.android.app` |
| `signInTest` | `https://api-staging.audiencekit.com` | 713525445368431 (AudienceKit platform) | `social.hotmess.android.signintest` |

Hot Mess is a consumer app: sign-in is classic Facebook Login on the build's Facebook app, asking
for `public_profile`, `email` and `user_friends`, like the iOS app. It opens Facebook's OAuth dialog
in a Custom Tab, which redirects to `fbconnect://cct.<application id>` with a code that the API
exchanges. Each Facebook app needs an Android platform listing this app's package names and key
hashes, and the redirect has to be accepted as a Valid OAuth Redirect URI.

## Cover charge

Venues that take cover in the app show "Pay cover" on their page, their events tonight and Now.
`buyCover` starts the payment, Stripe's payment sheet (with Google Pay) takes it as a direct charge on
the venue's own Stripe account, and `confirmCover` turns the pass on. A pass's QR code is made on the
phone from its secret every 30 seconds (`CoverPass` in the SDK), so it works with no signal at the
door. Passes are on the Me tab. People who work a venue's door get Door mode there (and on the venue's
page), which scans passes with the camera.

## Facebook sign-in test

`FacebookSignInTest` (`app/src/androidTest`) signs in on a connected phone as a Facebook test user and
checks the Me tab shows them. `scripts/facebook-signin-test.sh` loads `FB_TEST_ANDROID_EMAIL`,
`FB_TEST_ANDROID_PASSWORD` and `FB_TEST_ANDROID_NAME` from `~/.config/audience-kit/fb_test_users.env`;
without them it's skipped. The test users belong to the AudienceKit platform app (713525445368431),
and Meta won't add them to another app or make new ones, so connected tests run on `signInTest`:
Staging, signing in with that app. It's a Business-type app, so that build opens the dialog with its
Login for Business configuration and redirects to `fb713525445368431://authorize/`, like the iOS app. With `FB_TEST_APP_ID` set to the app the users belong to, the test stops
early when that isn't the build's.
That build signs in through an ephemeral Custom Tab, which doesn't share Chrome's cookies, so it
doesn't matter who is signed in to facebook.com in Chrome (Chrome 136 or later). Like any
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
