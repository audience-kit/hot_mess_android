package social.hotmess.android.push

import com.google.firebase.messaging.FirebaseMessagingService
import social.hotmess.android.appGraph

/** Firebase hands new tokens here; notifications themselves are shown by Firebase. */
class HotMessMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val graph = applicationContext.appGraph
        if (graph.session.isSignedIn) graph.push.send(token)
    }
}
