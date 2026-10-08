package social.hotmess.android

import android.content.Intent
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signs in with Facebook as a test user, from a signed-out app to the tabs, and checks Me shows the
 * test user. Like the iOS app's FacebookSignInUITests, it walks whatever Facebook shows and fails
 * with the page's reason when Facebook refuses.
 *
 * The test user comes from the `fbEmail`, `fbPassword` and optional `fbName` runner arguments (see
 * scripts/facebook-signin-test.sh); without them the test is skipped. The optional `fbAppId` is the
 * Facebook app the test users belong to, which has to be the build's. Sign-in opens in an ephemeral
 * Chrome Custom Tab, so whoever is signed in to Facebook in Chrome itself doesn't matter.
 */
@RunWith(AndroidJUnit4::class)
class FacebookSignInTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app = instrumentation.targetContext.packageName

    private val email = argument("fbEmail")
    private val password = argument("fbPassword")
    private val name = argument("fbName")
    private val testUsersAppId = argument("fbAppId")

    @Before
    fun wake() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
    }

    @Test
    fun signsInAsTheTestUser() {
        assumeTrue(
            "Set FB_TEST_ANDROID_EMAIL and FB_TEST_ANDROID_PASSWORD to a Facebook test user to sign in",
            email != null && password != null,
        )
        // Facebook test users belong to one Facebook app and can't log in to another.
        if (testUsersAppId != null && testUsersAppId != BuildConfig.FACEBOOK_APP_ID) {
            fail(
                "The test users belong to Facebook app $testUsersAppId, but this build signs in with " +
                    "${BuildConfig.FACEBOOK_APP_ID}. Add them to that app, or use test users of it.",
            )
        }

        launch()
        signOutIfSignedIn()
        val signIn = device.wait(Until.findObject(inApp().text("Continue with Facebook")), TIMEOUT)
        assertNotNull("The sign-in screen didn't appear", signIn)
        signIn.click()

        walkFacebook()

        device.findObject(inApp().text("Me")).click()
        if (name != null) {
            assertNotNull(
                "Signed in, but Me doesn't show $name. Chrome may be signed in to Facebook as someone else.",
                device.wait(Until.findObject(inApp().text(name)), TIMEOUT),
            )
        }
    }

    /** Taps through Facebook until Hot Mess shows its tabs, or fails with what stopped it. */
    private fun walkFacebook() {
        // Facebook sometimes comes back to an empty login form, so log in again, a few times at most.
        var logins = 0
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (device.hasObject(inApp().text("Now"))) return

            device.findObject(inApp().text("We couldn't sign you in"))?.let {
                fail("Hot Mess couldn't sign in: ${dialogText()}")
            }
            device.findObject(By.text(FACEBOOK_ERROR))?.let { fail("Facebook refused the login: ${it.text}") }

            // Chrome's first-run screen, when Chrome hasn't been opened on this phone before.
            device.findObject(By.text(CHROME_FIRST_RUN))?.let {
                it.click()
                device.waitForIdle()
                continue
            }

            device.findObject(By.text(CONTINUE_AS))?.let { button ->
                val account = CONTINUE_AS.matcher(button.text).run { if (find()) group(1)?.trim() else null }
                if (name != null && account != null && !account.equals(name, ignoreCase = true)) {
                    device.findObject(By.text(ANOTHER_ACCOUNT))?.let {
                        it.click()
                        device.waitForIdle()
                        continue
                    }
                    fail("Facebook offered to continue as $account, not $name. Log out of facebook.com in Chrome first.")
                }
                // The heading asks "Continue as …?"; its button only says Continue.
                (device.findObject(By.clickable(true).text(CONTINUE)) ?: button).click()
                device.waitForIdle()
                continue
            }

            // "Email or mobile number required" over that empty form.
            device.findObject(By.clickable(true).text(OK))?.let {
                it.click()
                device.waitForIdle()
                continue
            }

            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            if (logins < 3 && fields.size >= 2 && fields.first().text != email) {
                fields.first().fill(email!!)
                fields.last().fill(password!!)
                (device.findObject(By.clickable(true).text(LOG_IN)) ?: device.findObject(By.desc(LOG_IN)))?.click()
                    ?: device.pressEnter()
                logins++
                device.waitForIdle()
                continue
            }

            // "Save your login info?" after logging in, and other offers to skip.
            (device.findObject(By.text(NOT_NOW)) ?: device.findObject(By.desc(NOT_NOW)))?.let {
                it.click()
                device.waitForIdle()
                continue
            }

            // The consent page after logging in: "Continue", or "Continue as …" handled above.
            device.findObject(By.clickable(true).text(CONTINUE))?.let {
                it.click()
                device.waitForIdle()
                continue
            }
            Thread.sleep(1_000)
        }
        fail("Hot Mess didn't reach its tabs after Facebook")
    }

    private fun launch() {
        val context = instrumentation.context
        val intent = context.packageManager.getLaunchIntentForPackage(app)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertNotNull("Hot Mess didn't open", device.wait(Until.hasObject(By.pkg(app).depth(0)), TIMEOUT))
    }

    /** Starts from the sign-in screen: an earlier run, or a person, may have left the app signed in. */
    private fun signOutIfSignedIn() {
        val signedIn = device.wait(
            Until.findObject(inApp().text(Pattern.compile("Now|Continue with Facebook"))),
            TIMEOUT,
        )
        if (signedIn?.text != "Now") return
        device.findObject(inApp().text("Me")).click()
        device.wait(Until.findObject(inApp().text("Sign out")), TIMEOUT).click()
        // The confirmation's button, the last "Sign out" on screen.
        device.wait(Until.findObjects(inApp().text("Sign out")), TIMEOUT).last().click()
    }

    private fun dialogText(): String =
        device.findObjects(inApp().clazz("android.widget.TextView")).joinToString(" ") { it.text.orEmpty() }

    private fun inApp(): BySelector = By.pkg(app)

    private fun UiObject2.fill(value: String) {
        click()
        text = value
    }

    private fun argument(key: String): String? =
        InstrumentationRegistry.getArguments().getString(key)?.takeIf { it.isNotEmpty() }
            ?.let { String(Base64.decode(it, Base64.DEFAULT)) }

    private companion object {
        const val TIMEOUT = 20_000L
        // Login for Business ends on "… has been connected to AudienceKit" with Got it.
        val CONTINUE: Pattern = Pattern.compile("(?i)continue|got it")
        val CONTINUE_AS: Pattern = Pattern.compile("(?i)continue as (.+?)\\??$")
        val OK: Pattern = Pattern.compile("(?i)ok")
        val NOT_NOW: Pattern = Pattern.compile("(?i)not now")
        val LOG_IN: Pattern = Pattern.compile("(?i)log ?in")
        val ANOTHER_ACCOUNT: Pattern = Pattern.compile("(?i)not you\\??|(log in|use) (to |with )?(another|a different) (account|profile)|switch accounts?")
        val CHROME_FIRST_RUN: Pattern = Pattern.compile("(?i)use without an account|stay signed out|no,? thanks|accept & continue")
        val FACEBOOK_ERROR: Pattern = Pattern.compile("(?i).*(went wrong|not allowed by the application configuration|app not active|invalid app id).*")
    }
}
