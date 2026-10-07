package social.hotmess.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageToneTest {
    @Test
    fun darkPhotosGetWhiteTextWithNoScrim() {
        val tone = ImageTone.decide(top = List(100) { 0.02 }, band = List(100) { 0.05 })

        assertEquals(PhotoTone.Text.LIGHT, tone.text)
        assertEquals(0.0, tone.scrim)
        assertTrue(tone.lightBar)
        assertEquals(0.0, tone.barScrim)
    }

    @Test
    fun brightPhotosGetInkWithNoScrim() {
        val tone = ImageTone.decide(top = List(100) { 0.9 }, band = List(100) { 0.85 })

        assertEquals(PhotoTone.Text.DARK, tone.text)
        assertEquals(0.0, tone.scrim)
        assertEquals(false, tone.lightBar)
    }

    @Test
    fun busyPhotosGetWhiteTextOverTheSmallestScrimThatReachesTheTarget() {
        // Half dark, half bright: neither text colour reaches 4.5:1 alone.
        val band = List(50) { 0.02 } + List(50) { 0.6 }
        val tone = ImageTone.decide(top = band, band = band)

        assertEquals(PhotoTone.Text.LIGHT, tone.text)
        val expected = 1 - (1.05 / 4.5 - 0.05) / 0.6
        assertEquals(expected, tone.scrim, 1e-9)
        // White over the scrimmed brightest pixel lands on the target.
        assertEquals(4.5, ImageTone.contrast(1.0, 0.6 * (1 - tone.scrim)), 1e-9)
    }

    @Test
    fun cityLightsAtNightGetAtLeastTheBusyScrim() {
        // A dark skyline with a few bright windows: dark enough to pass 4.5:1 by the old rule, but
        // letters crossing the lights still need a scrim.
        val band = List(90) { 0.01 } + List(10) { 0.9 }
        val tone = ImageTone.decide(top = band, band = band)

        assertEquals(PhotoTone.Text.LIGHT, tone.text)
        assertTrue(tone.scrim >= ImageTone.BUSY_SCRIM)
    }

    @Test
    fun theScrimIsClampedToTheMaximum() {
        val band = List(50) { 0.0 } + List(50) { 1.0 }
        val tone = ImageTone.decide(top = band, band = band, target = 7.0)

        assertEquals(ImageTone.SCRIM_MAX, tone.scrim)
    }

    @Test
    fun highContrastKeepsAFloorUnderTheText() {
        val tone = ImageTone.decide(
            top = List(100) { 0.02 },
            band = List(100) { 0.02 },
            target = ImageTone.target(highContrast = true),
            scrimFloor = ImageTone.scrimFloor(highContrast = true),
        )

        assertEquals(PhotoTone.Text.LIGHT, tone.text)
        assertEquals(0.3, tone.scrim)
        assertEquals(0.0, ImageTone.scrimFloor(highContrast = false, reduceTransparency = false))
        assertEquals(0.3, ImageTone.scrimFloor(highContrast = false, reduceTransparency = true))
    }

    @Test
    fun patchyStatusBarAreasGetATopFade() {
        // Dark and bright patches under the bar: white fails on the bright quarter, black on the dark one.
        val tone = ImageTone.decide(top = List(50) { 0.02 } + List(50) { 0.6 }, band = List(100) { 0.02 })

        assertEquals(0.3, tone.barScrim)
    }

    @Test
    fun inkLuminanceMatchesPhotoInk() {
        assertEquals(ImageTone.INK_LUMINANCE, ImageTone.luminance(0x24, 0x16, 0x1D), 1e-4)
        assertEquals(1.0, ImageTone.luminance(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(0.0, ImageTone.luminance(0xFF000000.toInt()), 1e-9)
    }

    @Test
    fun mapsAViewRectOntoAnAspectFilledImage() {
        // A 200 × 100 image filling a 100 × 100 view is scaled to 100 tall and cropped 50 each side.
        val rect = ImageTone.imageRect(ToneRect(0.0, 50.0, 100.0, 50.0), imageWidth = 200, imageHeight = 100, viewWidth = 100.0, viewHeight = 100.0)

        assertEquals(ToneRect(50.0, 50.0, 100.0, 50.0), rect)
        assertNull(ImageTone.imageRect(ToneRect(0.0, 200.0, 100.0, 50.0), 200, 100, 100.0, 100.0))
        assertEquals(16, ImageTone.rows(400.0, 100.0))
        assertEquals(4, ImageTone.rows(400.0, 10.0))
    }
}
