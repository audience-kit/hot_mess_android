package social.hotmess.android.ui.components

import android.app.UiModeManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import social.hotmess.android.ui.theme.PhotoColors
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ImageTone
import social.hotmess.core.PhotoTone
import social.hotmess.core.ToneRect

// The Compose side of the photo brightness check: reading a photo's pixels through Coil, and the
// colours a PhotoTone stands for. The decision itself is ImageTone in core.

/** `on-photo` white or `photo-ink`, for the text over the photo. */
val PhotoTone.textColor: Color
    get() = if (text == PhotoTone.Text.LIGHT) PhotoColors.onPhoto else PhotoColors.photoInk

/** `scrim` behind light text, `scrim-light` behind dark. */
val PhotoTone.scrimColor: Color
    get() = if (text == PhotoTone.Text.LIGHT) PhotoColors.scrim else PhotoColors.scrimLight

/** The colour of the back button and actions over the photo. */
val PhotoTone.barContentColor: Color
    get() = if (lightBar) PhotoColors.onPhoto else PhotoColors.photoInk

/** The top fade's colour: dark under white bar icons, light under dark ones. */
val PhotoTone.barScrimColor: Color
    get() = if (lightBar) PhotoColors.scrim else PhotoColors.scrimLight

/** What the viewer's accessibility settings ask of text over photos. */
@Immutable
data class ToneSettings(val target: Double, val scrimFloor: Double)

/**
 * The contrast target and scrim floor for this device: 7:1 and a 0.3 floor when the system's high
 * contrast text or contrast setting is on, else 4.5:1 and no floor. Android has no Reduce
 * Transparency switch, so only contrast raises the floor.
 */
@Composable
fun rememberToneSettings(): ToneSettings {
    val context = LocalContext.current
    return remember(context) {
        val high = highContrast(context)
        ToneSettings(ImageTone.target(high), ImageTone.scrimFloor(high))
    }
}

private fun highContrast(context: Context): Boolean {
    val highTextContrast = try {
        Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    } catch (e: Exception) {
        false
    }
    val contrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.contrast ?: 0f
    } else {
        0f
    }
    return highTextContrast || contrast >= 0.5f
}

/** Tones already decided, so returning to a screen doesn't flicker between styles while it re-reads the photo. */
private val toneCache = LruCache<String, PhotoTone>(200)

/** The longest side of the copy of a photo that's analysed; text bands are shrunk to 32 columns anyway. */
private const val ANALYSIS_SIZE = 256

/**
 * Decides how text is drawn over [url] when it fills a view of [viewSize] pixels with
 * `ContentScale.Crop`. [band] is the area behind the text and [top] the status bar area (pass
 * [band] again where there's no status bar), both in the view's pixels.
 *
 * Starts as [PhotoTone.PLACEHOLDER] (white text over the dark `photo-placeholder`, the right guess
 * for most nightlife photos), or as the last tone decided for this photo, and updates once a small
 * software copy of the photo has loaded and been read. With no [url] it's the tone for the accent
 * gradient that stands in for the photo. [kind] separates cached tones for the same photo drawn in
 * differently shaped places.
 */
@Composable
fun rememberPhotoTone(url: String?, viewSize: IntSize, band: Rect?, top: Rect? = band, kind: String = ""): PhotoTone {
    val context = LocalContext.current
    val settings = rememberToneSettings()
    val cacheKey = url?.let { "$kind|$it|${settings.target}|${settings.scrimFloor}" }
    // With no photo the accent gradient shows: white (on-accent) on the light accent, ink on the
    // dark theme's brighter one.
    val accentTone = if (tokens.isDark) PhotoTone(PhotoTone.Text.DARK, scrim = 0.0, lightBar = false, barScrim = 0.0) else PhotoTone.PLACEHOLDER
    var tone by remember(url) { mutableStateOf(cacheKey?.let(toneCache::get) ?: PhotoTone.PLACEHOLDER) }
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) {
        bitmap = url?.let { loadForAnalysis(context, it) }
    }

    LaunchedEffect(url, bitmap, viewSize, band, top, settings) {
        if (url == null) {
            tone = PhotoTone.PLACEHOLDER
            return@LaunchedEffect
        }
        val image = bitmap ?: return@LaunchedEffect
        if (viewSize.width <= 0 || viewSize.height <= 0 || band == null || band.height <= 0f) return@LaunchedEffect
        val decided = withContext(Dispatchers.Default) {
            analyze(image, viewSize, top ?: band, band, settings)
        } ?: return@LaunchedEffect
        tone = decided
        cacheKey?.let { toneCache.put(it, decided) }
    }
    return if (url == null) accentTone else tone
}

/** A small, software (readable) copy of the photo, through the app's Coil cache. */
private suspend fun loadForAnalysis(context: Context, url: String): Bitmap? = try {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(ANALYSIS_SIZE)
        .allowHardware(false)
        .build()
    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private fun analyze(bitmap: Bitmap, viewSize: IntSize, top: Rect, band: Rect, settings: ToneSettings): PhotoTone? {
    val topLuminances = luminances(bitmap, top, viewSize) ?: return null
    val bandLuminances = luminances(bitmap, band, viewSize) ?: return null
    return ImageTone.decide(topLuminances, bandLuminances, settings.target, settings.scrimFloor)
}

/** The luminance of each pixel of [rect] (in the view), shrunk to 32 columns. */
private fun luminances(bitmap: Bitmap, rect: Rect, viewSize: IntSize): List<Double>? {
    val area = ImageTone.imageRect(
        ToneRect(rect.left.toDouble(), rect.top.toDouble(), rect.width.toDouble(), rect.height.toDouble()),
        imageWidth = bitmap.width,
        imageHeight = bitmap.height,
        viewWidth = viewSize.width.toDouble(),
        viewHeight = viewSize.height.toDouble(),
    ) ?: return null

    return try {
        val cropped = Bitmap.createBitmap(bitmap, area.left.toInt(), area.top.toInt(), area.width.toInt(), area.height.toInt())
        val columns = ImageTone.COLUMNS
        val rows = ImageTone.rows(area.width, area.height)
        val scaled = Bitmap.createScaledBitmap(cropped, columns, rows, true)
        val pixels = IntArray(columns * rows)
        scaled.getPixels(pixels, 0, columns, 0, 0, columns, rows)
        pixels.map(ImageTone::luminance)
    } catch (e: IllegalArgumentException) {
        null
    }
}
