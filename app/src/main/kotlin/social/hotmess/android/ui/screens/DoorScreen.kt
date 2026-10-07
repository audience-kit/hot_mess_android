package social.hotmess.android.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiencekit.Admission
import com.audiencekit.CoverPass
import com.audiencekit.DoorNight
import com.audiencekit.DoorVenue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.RemoteImage
import social.hotmess.android.ui.components.RowButton
import social.hotmess.android.ui.components.Section
import social.hotmess.android.ui.openAppSettings
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessColors
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.HotMessApi
import social.hotmess.core.PassQr
import social.hotmess.core.ScanDebouncer
import social.hotmess.core.ScanTone
import social.hotmess.core.headline
import social.hotmess.core.tone
import java.util.concurrent.Executors

/**
 * Door mode, for the people working a venue's door: pick the venue, then scan passes with the camera.
 * Each scan fills the screen for two seconds, green to let them in, amber for re-entry and red for
 * anything else, over tonight's paid and checked-in counts.
 */
@Composable
fun DoorScreen(venueId: String?, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val loader = rememberLoader<List<DoorVenue>>("door-venues")
    val state by loader.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(venueId) }

    LaunchedEffect(Unit) { loader.load(Unit) { graph.api.doorVenues() } }

    val venues = state.valueOrNull.orEmpty()
    val selected = venues.firstOrNull { it.id == selectedId } ?: venues.singleOrNull()

    ScreenScaffold(title = selected?.name ?: "Door", onBack = navigator::back) {
        LoadStateView(state, onRetry = { loader.load(Unit) { graph.api.doorVenues() } }) { doors ->
            when {
                doors.isEmpty() -> Message(
                    Icons.Rounded.QrCodeScanner,
                    "No doors to work",
                    "Door mode is for venue staff. Ask the venue to add you to its door.",
                )
                selected == null -> Feed {
                    item {
                        Section("Which door?") {
                            CardRows(doors, divider = 72.dp) { door ->
                                RowButton({ selectedId = door.id }) {
                                    RemoteImage(door.photoUrl, Modifier.size(44.dp).clip(Radius.md))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(door.name.orEmpty(), style = HotMessType.subheading, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        door.door?.let { Text(it.summary, style = HotMessType.bodySmall, color = tokens.inkMuted) }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> Door(selected)
            }
        }
    }
}

/** "12 paid · 7 in". */
private val DoorNight.summary: String get() = "$paidCount paid · $checkedInCount in"

@Composable
private fun Door(venue: DoorVenue) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val model = viewModel(key = "door") { DoorModel(graph.api) }
    val door by model.door.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(venue.id) { model.start(venue.id, venue.door) }
    LaunchedEffect(result) { if (result != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    var cameraAllowed by remember { mutableStateOf(context.hasCameraPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        asked = true
    }
    LaunchedEffect(Unit) { if (!cameraAllowed && !asked) launcher.launch(Manifest.permission.CAMERA) }
    // Back from Settings with the camera allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { cameraAllowed = context.hasCameraPermission() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Count("Paid tonight", door?.paidCount, Modifier.weight(1f))
                Count("Checked in", door?.checkedInCount, Modifier.weight(1f))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (cameraAllowed) {
                    Scanner(onCode = model::onCode, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(240.dp)
                            .border(3.dp, Color.White.copy(alpha = 0.8f), Radius.lg),
                    )
                    Text(
                        "Point the camera at a pass",
                        style = HotMessType.label,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(Space.s6)
                            .background(Color.Black.copy(alpha = 0.5f), Radius.pill)
                            .padding(horizontal = Space.s4, vertical = Space.s2),
                    )
                } else {
                    Message(
                        Icons.Rounded.CameraAlt,
                        "Door mode needs the camera",
                        "Allow the camera to scan passes.",
                        action = if (asked) {
                            "Open settings" to { context.openAppSettings() }
                        } else {
                            "Allow camera" to { launcher.launch(Manifest.permission.CAMERA) }
                        },
                    )
                }
            }
        }

        AnimatedVisibility(result != null, enter = fadeIn(), exit = fadeOut()) {
            // Keep showing the last result while it fades out.
            var shown by remember { mutableStateOf<DoorResult?>(null) }
            result?.let { shown = it }
            shown?.let { ScanResultView(it, onDismiss = model::dismiss) }
        }
    }
}

@Composable
private fun Count(label: String, value: Int?, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(Radius.lg).background(tokens.surfaceRaised).padding(Space.s3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value?.toString() ?: "–", style = HotMessType.display, color = tokens.ink)
        Text(label, style = HotMessType.caption, color = tokens.inkMuted)
    }
}

/** A scan, full screen: the verdict in a colour you can read across the room, and who it is. */
@Composable
private fun ScanResultView(result: DoorResult, onDismiss: () -> Unit) {
    val (background, ink) = when (result.tone) {
        ScanTone.ADMIT -> HotMessColors.Light.presenceOnline to Color.White
        ScanTone.RE_ENTRY -> HotMessColors.Light.presencePush to HotMessColors.Light.ink
        ScanTone.REFUSE -> HotMessColors.Light.danger to Color.White
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .clickable(role = Role.Button, onClick = onDismiss)
            .padding(Space.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s4, Alignment.CenterVertically),
    ) {
        result.admission?.let { admission ->
            val name = admission.userName.orEmpty()
            if (admission.userPhotoUrl != null || name.isNotEmpty()) {
                Avatar(admission.userPhotoUrl, name, size = 120.dp)
            }
        }
        Text(
            result.headline,
            style = HotMessType.display.copy(fontSize = 56.sp, lineHeight = 60.sp),
            color = ink,
            textAlign = TextAlign.Center,
        )
        Text(result.message, style = HotMessType.title, color = ink, textAlign = TextAlign.Center)
    }
}

/** The camera, reading QR codes from its frames with ZXing. */
@Composable
private fun Scanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val analyzer = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            val cameraProvider = providerFuture.get()
            provider = cameraProvider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzer) { image ->
                val text = image.use { it.qrText() }
                if (text != null) main.execute { currentOnCode(text) }
            }
            cameraProvider.unbindAll()
            try {
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: IllegalArgumentException) {
                Log.w("Door", "No back camera to scan with", e)
            } catch (e: IllegalStateException) {
                Log.w("Door", "The camera couldn't start", e)
            }
        }, main)
        onDispose {
            disposed = true
            provider?.unbindAll()
            analyzer.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** The QR code in a camera frame, from its luminance plane. */
private fun ImageProxy.qrText(): String? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer.apply { rewind() }
    val bytes = ByteArray(plane.rowStride * height)
    buffer.get(bytes, 0, minOf(buffer.remaining(), bytes.size))
    return PassQr.decode(bytes, width, height, plane.rowStride)
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** What the door shows for a scan. */
data class DoorResult(val tone: ScanTone, val headline: String, val message: String, val admission: Admission? = null)

/**
 * Door mode's state: tonight's counts at the venue, and the scan on screen. Checks one pass at a time,
 * ignores a pass the camera is still pointed at, and refreshes the counts after each scan.
 */
class DoorModel(private val api: HotMessApi) : ViewModel() {
    private val _door = MutableStateFlow<DoorNight?>(null)
    val door: StateFlow<DoorNight?> = _door.asStateFlow()

    private val _result = MutableStateFlow<DoorResult?>(null)
    val result: StateFlow<DoorResult?> = _result.asStateFlow()

    private val debouncer = ScanDebouncer()
    private var venueId: String? = null
    private var checking = false
    private var hide: Job? = null

    fun start(venueId: String, door: DoorNight?) {
        if (this.venueId == venueId) return
        this.venueId = venueId
        _door.value = door
        refresh()
    }

    fun onCode(code: String) {
        val venueId = venueId ?: return
        if (checking || _result.value != null) return
        if (!debouncer.accept(code, SystemClock.elapsedRealtime())) return
        checking = true
        viewModelScope.launch {
            val shown = if (!CoverPass.isPassCode(code)) {
                DoorResult(ScanTone.REFUSE, "NOT A PASS", "That isn't a Hot Mess pass")
            } else {
                try {
                    val scan = api.scan(venueId, code)
                    DoorResult(scan.outcome.tone, scan.outcome.headline, scan.message, scan.admission)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DoorResult(ScanTone.REFUSE, "TRY AGAIN", ApiError.of(e).message ?: "We couldn't check that pass.")
                }
            }
            checking = false
            show(shown)
            refresh()
        }
    }

    fun dismiss() {
        hide?.cancel()
        _result.value = null
    }

    private fun show(result: DoorResult) {
        hide?.cancel()
        _result.value = result
        hide = viewModelScope.launch {
            delay(RESULT_MILLIS)
            _result.value = null
        }
    }

    private fun refresh() {
        val venueId = venueId ?: return
        viewModelScope.launch {
            try {
                api.door(venueId)?.let { _door.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the last counts; the next scan tries again.
            }
        }
    }

    private companion object {
        const val RESULT_MILLIS = 2_000L
    }
}
