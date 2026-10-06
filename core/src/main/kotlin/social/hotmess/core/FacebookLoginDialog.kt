package social.hotmess.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Facebook's OAuth dialog, opened in a Custom Tab, which returns a code for the API to exchange.
 *
 * The Facebook Android SDK can't do this for a Business-type Facebook app: such an app only accepts
 * a dialog opened with its Login for Business `config_id`, which the SDK never sends. The redirect
 * goes to `fbconnect://cct.<package name>`, the one the SDK's own Custom Tab login uses, which the
 * Facebook app's Android platform must list.
 */
class FacebookLoginDialog(
    val appId: String,
    /** The Login for Business configuration. Without one the dialog asks for [permissions] instead. */
    private val configId: String?,
    private val permissions: List<String>,
    packageName: String,
) {
    val redirectUri: String = "fbconnect://cct.$packageName"

    /** The dialog URL. [state] ties the redirect to this request. */
    fun url(state: String): String {
        val query = buildList {
            add("client_id" to appId)
            add("redirect_uri" to redirectUri)
            add("response_type" to "code")
            add("state" to state)
            if (configId != null) add("config_id" to configId) else add("scope" to permissions.joinToString(","))
        }
        return "https://www.facebook.com/v21.0/dialog/oauth?" +
            query.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
    }

    /** Whether [url] is this dialog's redirect, rather than some other link into the app. */
    fun isRedirect(url: String): Boolean = url.startsWith(redirectUri)

    sealed interface Result {
        data class Code(val code: String) : Result
        data object Cancelled : Result
        data class Failed(val reason: String) : Result
    }

    /** Reads the code from the redirect, or the reason Facebook gave instead. */
    fun result(callback: String, state: String): Result {
        val uri = runCatching { URI(callback) }.getOrNull() ?: return Result.Failed("Facebook sent back a link we couldn't read.")
        // Facebook sends the parameters in the query, and sometimes the fragment too.
        val items = parameters(uri.rawQuery) + parameters(uri.rawFragment)
        fun value(name: String) = items.firstOrNull { it.first == name }?.second

        val reason = value("error_description") ?: value("error_message") ?: value("error")
        if (reason != null) {
            return if (value("error_reason") == "user_denied") Result.Cancelled else Result.Failed(reason)
        }
        if (value("state") != state) return Result.Failed("The login response didn't match the request.")
        val code = value("code")?.takeIf { it.isNotEmpty() } ?: return Result.Failed("Facebook didn't send a login code.")
        return Result.Code(code)
    }

    private fun parameters(raw: String?): List<Pair<String, String>> =
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.map { item ->
            val name = item.substringBefore('=')
            val value = item.substringAfter('=', "")
            URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }
}
