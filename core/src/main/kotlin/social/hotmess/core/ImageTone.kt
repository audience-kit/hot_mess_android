package social.hotmess.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How a photo's text and bars are drawn over it: the design system's brightness check (PhotoCard,
 * HeroHeader), shared by every photo surface.
 */
data class PhotoTone(
    /** The colour of the title and metadata: `on-photo` white, or `photo-ink` when [text] is [Text.DARK]. */
    val text: Text,
    /** Opacity of the gradient behind the text: `scrim` (black) under light text, `scrim-light` under dark. */
    val scrim: Double,
    /** Whether the status bar and top bar icons over the photo should be white. */
    val lightBar: Boolean,
    /** Opacity of a fade under the status bar, for photos where neither bar style reaches 3:1. */
    val barScrim: Double,
) {
    enum class Text { LIGHT, DARK }

    companion object {
        /**
         * White text on the dark placeholder: right for most nightlife photos, and for the accent
         * gradient shown when there is no photo.
         */
        val PLACEHOLDER = PhotoTone(Text.LIGHT, scrim = 0.0, lightBar = true, barScrim = 0.0)
    }
}

/** A rectangle in pixels or dp, for mapping a view's text band onto a photo. */
data class ToneRect(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height
}

/**
 * Reads how bright a photo is behind its text and status bar, and picks a text colour and the
 * smallest scrim that keeps it readable (a port of the iOS app's ImageTone).
 *
 * Each area is shrunk to 32 pixels wide and judged by its 10th and 90th percentile luminance rather
 * than its average, so one spotlight right behind a letter still counts. Light text wins whenever it
 * reaches the target; dark text only when the photo is bright enough to need no scrim. Anything
 * busier gets light text over a dark scrim.
 */
object ImageTone {
    /** Relative luminance of `photo-ink` (#24161d), the dark text colour. */
    const val INK_LUMINANCE = 0.0103

    /** `opacity-scrim-max`: past this the photo is mostly hidden, so the scrim stops here. */
    const val SCRIM_MAX = 0.75

    /** `opacity-scrim-floor`: the least scrim with Increase Contrast or Reduce Transparency. */
    const val SCRIM_FLOOR = 0.3

    /** The text contrast target: 4.5:1, or 7:1 with high contrast text. */
    const val TARGET = 4.5
    const val TARGET_HIGH_CONTRAST = 7.0

    /** Columns each sampled area is shrunk to. */
    const val COLUMNS = 32

    /** The target for the viewer's contrast setting. */
    fun target(highContrast: Boolean): Double = if (highContrast) TARGET_HIGH_CONTRAST else TARGET

    /** The scrim floor for the viewer's contrast and transparency settings. */
    fun scrimFloor(highContrast: Boolean, reduceTransparency: Boolean = false): Double =
        if (highContrast || reduceTransparency) SCRIM_FLOOR else 0.0

    /** The pure decision, given relative luminances (0 to 1) sampled from each area. */
    fun decide(top: List<Double>, band: List<Double>, target: Double = TARGET, scrimFloor: Double = 0.0): PhotoTone {
        val sortedBand = band.sorted()
        val darkest = percentile(sortedBand, 0.1)
        val brightest = percentile(sortedBand, 0.9)

        val text: PhotoTone.Text
        var scrim: Double
        if (contrast(1.0, brightest) >= target) {
            text = PhotoTone.Text.LIGHT
            scrim = 0.0
        } else if (contrast(darkest, INK_LUMINANCE) >= target) {
            text = PhotoTone.Text.DARK
            scrim = 0.0
        } else {
            // A black scrim at opacity a scales luminance by (1 - a); solve for the brightest pixel
            // landing exactly on the target against white.
            text = PhotoTone.Text.LIGHT
            scrim = scrimFor(brightest, target)
        }
        scrim = min(max(scrim, scrimFloor), SCRIM_MAX)

        val sortedTop = top.sorted()
        val white = contrast(1.0, percentile(sortedTop, 0.75))
        val black = contrast(percentile(sortedTop, 0.25), 0.0)

        return PhotoTone(
            text = text,
            scrim = scrim,
            lightBar = white >= black,
            barScrim = if (max(white, black) < 3) 0.3 else 0.0,
        )
    }

    /** `1 − (1.05 / target − 0.05) / brightest`: the black scrim that brings [brightest] to [target] against white. */
    fun scrimFor(brightest: Double, target: Double): Double =
        if (brightest <= 0) 0.0 else 1 - (1.05 / target - 0.05) / brightest

    /** WCAG relative luminance of an 8-bit sRGB colour. */
    fun luminance(red: Int, green: Int, blue: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    /** Luminance of a packed ARGB pixel, as Android's `Bitmap.getPixels` returns them. */
    fun luminance(argb: Int): Double = luminance((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    fun contrast(first: Double, second: Double): Double = (max(first, second) + 0.05) / (min(first, second) + 0.05)

    /**
     * Maps [rect] in a view of [viewWidth] × [viewHeight] to the pixels of an image of [imageWidth] ×
     * [imageHeight] drawn aspect-fill (ContentScale.Crop) and centred in it, clipped to the image and
     * rounded out to whole pixels. Null when nothing is left.
     */
    fun imageRect(rect: ToneRect, imageWidth: Int, imageHeight: Int, viewWidth: Double, viewHeight: Double): ToneRect? {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
        val originX = (viewWidth - imageWidth * scale) / 2
        val originY = (viewHeight - imageHeight * scale) / 2

        val left = max(0.0, (rect.left - originX) / scale)
        val top = max(0.0, (rect.top - originY) / scale)
        val right = min(imageWidth.toDouble(), (rect.right - originX) / scale)
        val bottom = min(imageHeight.toDouble(), (rect.bottom - originY) / scale)
        val l = kotlin.math.floor(left)
        val t = kotlin.math.floor(top)
        val r = kotlin.math.ceil(right)
        val b = kotlin.math.ceil(bottom)
        if (r - l < 1 || b - t < 1) return null
        return ToneRect(l, t, r - l, b - t)
    }

    /** Rows to shrink an area of [width] × [height] to at [COLUMNS] wide, keeping its shape (at least 4). */
    fun rows(width: Double, height: Double, columns: Int = COLUMNS): Int =
        max(4, (columns * height / width).roundToInt())

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        return sorted[min(sorted.size - 1, (fraction * sorted.size).toInt())]
    }
}
