package social.hotmess.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.audiencekit.android.AudienceKitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import social.hotmess.android.location.LocationProvider
import social.hotmess.android.push.PushRegistrar
import social.hotmess.android.session.BrandStore
import social.hotmess.android.session.SessionStore
import social.hotmess.core.HotMessApi

/** The app's services, built once. Screens reach them through [LocalAppGraph][social.hotmess.android.ui.LocalAppGraph]. */
class AppGraph(context: Context, val configuration: AppConfiguration = AppConfiguration()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val audienceKit = AudienceKitClient(context, configuration.audienceKit)
    val api = HotMessApi(audienceKit)
    val brand = BrandStore(audienceKit, scope)
    val push = PushRegistrar(context, api, scope)
    val session = SessionStore(context, api, audienceKit, brand, configuration, push, scope)
    val location = LocationProvider(context, api, configuration, scope)
}

class HotMessApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        graph = AppGraph(this)

        graph.brand.load()
        graph.scope.launch { graph.session.start() }

        // Follow the device only while the app is on screen, like the iOS app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (graph.session.isSignedIn) graph.location.start()
            }

            override fun onStop(owner: LifecycleOwner) {
                graph.location.stop()
            }
        })
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as HotMessApplication).graph
