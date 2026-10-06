package social.hotmess.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiencekit.Coordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.AppConfiguration
import social.hotmess.android.session.SessionStore
import social.hotmess.core.AppLocale
import social.hotmess.core.HotMessApi

enum class LocationAccess { NOT_REQUESTED, ALLOWED, DENIED }

/**
 * Where the device is, which of the audience's locales that is, and which venue beacon is nearest.
 * Reports each new position to the API (`reportLocation`), as the iOS app does.
 */
class LocationProvider(
    private val context: Context,
    private val api: HotMessApi,
    configuration: AppConfiguration,
    private val scope: CoroutineScope,
) {
    private val _coordinates = MutableStateFlow<Coordinates?>(null)
    val coordinates: StateFlow<Coordinates?> = _coordinates.asStateFlow()

    private val _locale = MutableStateFlow(restoreLocale())
    val locale: StateFlow<AppLocale?> = _locale.asStateFlow()

    private val _access = MutableStateFlow(currentAccess(asked = false))
    val access: StateFlow<LocationAccess> = _access.asStateFlow()

    private val manager = context.getSystemService(LocationManager::class.java)
    private val beacons = configuration.beaconUuid?.let { BeaconScanner(context, it, ::onBeacon) }
    private var isMonitoring = false

    private val listener = LocationListener { location -> update(location) }

    /** Starts following the device when it may; the screens ask for permission first. */
    fun start() {
        if (!hasLocationPermission()) return
        _access.value = LocationAccess.ALLOWED
        beginMonitoring()
    }

    fun stop() {
        if (!isMonitoring) return
        manager?.removeUpdates(listener)
        beacons?.stop()
        isMonitoring = false
    }

    /** Called with the answer to the permission prompt. */
    fun onPermissionResult() {
        _access.value = currentAccess(asked = true)
        start()
    }

    /** Asks the API which locale the current position belongs to. */
    suspend fun refreshLocale() {
        try {
            val resolved = api.closestLocale(_coordinates.value) ?: return
            if (resolved.id == _locale.value?.id) return
            _locale.value = resolved
            persist(resolved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HotMess", "Locale lookup failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginMonitoring() {
        if (isMonitoring) return
        val manager = manager ?: return
        isMonitoring = true

        // Like significant-change monitoring on iOS: a fix every few minutes or 100 metres.
        val provider = listOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).filterNotNull().firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        try {
            provider?.let { manager.requestLocationUpdates(it, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, listener, Looper.getMainLooper()) }
            listOfNotNull(provider, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                ?.let(::update)
        } catch (e: SecurityException) {
            Log.w("HotMess", "Location unavailable: ${e.message}")
        }

        beacons?.start()
        scope.launch { refreshLocale() }
    }

    private fun update(location: Location) {
        val previous = _coordinates.value
        _coordinates.value = Coordinates(
            latitude = location.latitude,
            longitude = location.longitude,
            beaconMajor = previous?.beaconMajor,
            beaconMinor = previous?.beaconMinor,
        )
        scope.launch {
            refreshLocale()
            reportPosition()
        }
    }

    private fun onBeacon(major: Int, minor: Int) {
        // Only attach beacon identifiers to a position we actually have.
        val position = _coordinates.value ?: return
        if (position.beaconMajor == major && position.beaconMinor == minor) return
        _coordinates.value = position.copy(beaconMajor = major, beaconMinor = minor)
        scope.launch { reportPosition() }
    }

    private suspend fun reportPosition() {
        val position = _coordinates.value ?: return
        try {
            api.reportLocation(position)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HotMess", "Location report failed: ${e.message}")
        }
    }

    private fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun currentAccess(asked: Boolean): LocationAccess = when {
        hasLocationPermission() -> LocationAccess.ALLOWED
        asked || preferences.getBoolean(ASKED_KEY, false) -> LocationAccess.DENIED
        else -> LocationAccess.NOT_REQUESTED
    }.also { if (asked) preferences.edit().putBoolean(ASKED_KEY, true).apply() }

    private val preferences get() = context.getSharedPreferences(SessionStore.PREFERENCES, Context.MODE_PRIVATE)

    /** Remembered so a cold start has a locale before the first fix. */
    private fun restoreLocale(): AppLocale? {
        val id = preferences.getString(LOCALE_ID_KEY, null) ?: return null
        val name = preferences.getString(LOCALE_NAME_KEY, null) ?: return null
        return AppLocale(id, name)
    }

    private fun persist(locale: AppLocale) {
        preferences.edit().putString(LOCALE_ID_KEY, locale.id).putString(LOCALE_NAME_KEY, locale.name).apply()
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 5 * 60 * 1000L
        private const val UPDATE_DISTANCE_M = 100f
        private const val LOCALE_ID_KEY = "localeId"
        private const val LOCALE_NAME_KEY = "localeName"
        private const val ASKED_KEY = "locationAsked"
    }
}
