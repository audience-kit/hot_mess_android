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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import social.hotmess.android.location.LocationProvider
import social.hotmess.android.payments.SquareCardEntry
import social.hotmess.android.push.PushRegistrar
import social.hotmess.android.session.AuthState
import social.hotmess.android.session.BrandStore
import social.hotmess.android.session.SessionStore
import social.hotmess.core.HotMessApi
import social.hotmess.core.PassBook

/** The app's services, built once. Screens reach them through [LocalAppGraph][social.hotmess.android.ui.LocalAppGraph]. */
class AppGraph(context: Context, val configuration: AppConfiguration = AppConfiguration()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val audienceKit = AudienceKitClient(context, configuration.audienceKit)
    val api = HotMessApi(audienceKit)
    val brand = BrandStore(audienceKit, scope)
    val push = PushRegistrar(context, api, scope)
    val session = SessionStore(context, api, audienceKit, configuration, push, scope)
    val location = LocationProvider(context, api, configuration, scope)

    /** The user's cover passes, kept so an opened pass still works with no signal at the door. */
    val passes = PassBook()

    /** Square's card entry, for Square venues' cover; MainActivity passes it card entry's result. */
    val squareCardEntry = SquareCardEntry()

    init {
        scope.launch {
            session.state.collect { if (it == AuthState.SignedOut) passes.clear() }
        }
    }

    private val _pingUpdates = MutableSharedFlow<String?>(extraBufferCapacity = 8)

    /** A Ping push arrived or was tapped, with its Ping's ID; Now reloads to show it. */
    val pingUpdates: SharedFlow<String?> = _pingUpdates.asSharedFlow()

    fun pingUpdated(pingId: String?) {
        _pingUpdates.tryEmit(pingId)
    }
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
