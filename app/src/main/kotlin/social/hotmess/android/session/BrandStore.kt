package social.hotmess.android.session

import android.util.Log
import com.audiencekit.AudienceKitClient
import com.audiencekit.Branding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The audience's name, tagline and accent overrides from AudienceKit branding. Public, so it loads before sign-in. */
class BrandStore(private val audienceKit: AudienceKitClient, private val scope: CoroutineScope) {
    private val _branding = MutableStateFlow<Branding?>(null)
    val branding: StateFlow<Branding?> = _branding.asStateFlow()

    fun load() {
        scope.launch {
            try {
                _branding.value = audienceKit.branding()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HotMess", "Branding unavailable: ${e.message}")
            }
        }
    }
}
