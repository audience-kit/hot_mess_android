package social.hotmess.core

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FacebookLoginDialogTest {
    private val dialog = FacebookLoginDialog(
        appId = "1168782378316790",
        permissions = listOf("public_profile", "email", "user_friends"),
        packageName = "social.hotmess.android.staging",
    )

    private fun query(url: String) = URI(url).query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun opensTheDialogAskingForThePermissions() {
        val url = dialog.url(state = "s-1")

        assertTrue(url.startsWith("https://www.facebook.com/v21.0/dialog/oauth?"))
        assertEquals(
            mapOf(
                "client_id" to "1168782378316790",
                "redirect_uri" to "fbconnect://cct.social.hotmess.android.staging",
                "response_type" to "code",
                "state" to "s-1",
                "scope" to "public_profile,email,user_friends",
            ),
            query(url),
        )
    }

    @Test
    fun opensABusinessAppsDialogWithItsLoginConfiguration() {
        val business = FacebookLoginDialog(
            appId = "713525445368431",
            permissions = listOf("public_profile", "email", "user_friends"),
            packageName = "social.hotmess.android.signintest",
            configId = "4085560021745660",
        )

        assertEquals(
            mapOf(
                "client_id" to "713525445368431",
                "redirect_uri" to "fb713525445368431://authorize/",
                "response_type" to "code",
                "state" to "s-1",
                "config_id" to "4085560021745660",
            ),
            query(business.url(state = "s-1")),
        )
        assertTrue(business.isRedirect("fb713525445368431://authorize/?code=abc&state=s-1"))
    }

    @Test
    fun readsTheCodeFromTheRedirect() {
        val result = dialog.result("fbconnect://cct.social.hotmess.android.staging?code=abc%2B1&state=s-1#_=_", "s-1")

        assertEquals(FacebookLoginDialog.Result.Code("abc+1"), result)
    }

    @Test
    fun readsParametersFromTheFragment() {
        val result = dialog.result("fbconnect://cct.social.hotmess.android.staging#code=abc&state=s-1", "s-1")

        assertEquals(FacebookLoginDialog.Result.Code("abc"), result)
    }

    @Test
    fun reportsFacebooksReason() {
        val result = dialog.result(
            "fbconnect://cct.social.hotmess.android.staging?error=access_denied&error_description=App+not+set+up&state=s-1",
            "s-1",
        )

        assertEquals(FacebookLoginDialog.Result.Failed("App not set up"), result)
    }

    @Test
    fun aDenialIsACancellation() {
        val result = dialog.result(
            "fbconnect://cct.social.hotmess.android.staging?error=access_denied&error_reason=user_denied&state=s-1",
            "s-1",
        )

        assertEquals(FacebookLoginDialog.Result.Cancelled, result)
    }

    @Test
    fun rejectsARedirectForAnotherRequest() {
        val result = dialog.result("fbconnect://cct.social.hotmess.android.staging?code=abc&state=other", "s-1")

        assertEquals(FacebookLoginDialog.Result.Failed("The login response didn't match the request."), result)
    }

    @Test
    fun recognisesItsRedirect() {
        assertTrue(dialog.isRedirect("fbconnect://cct.social.hotmess.android.staging?code=abc"))
        assertFalse(dialog.isRedirect("hotmess://venues/1"))
    }
}
