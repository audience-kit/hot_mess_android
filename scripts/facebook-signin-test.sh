#!/usr/bin/env bash
# Runs the Facebook sign-in UI test on the connected phone as the Android Facebook test user.
#
# The test user's FB_TEST_ANDROID_EMAIL, FB_TEST_ANDROID_PASSWORD and FB_TEST_ANDROID_NAME come from
# ~/.config/audience-kit/fb_test_users.env (or the file in FB_TEST_USERS_FILE), never from the
# repository. Without them the test is skipped.
set -euo pipefail

users_file="${FB_TEST_USERS_FILE:-$HOME/.config/audience-kit/fb_test_users.env}"
if [[ -f "$users_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$users_file"
    set +a
fi

cd "$(dirname "$0")/.."
exec ./gradlew :app:connectedAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=social.hotmess.android.FacebookSignInTest "$@"
