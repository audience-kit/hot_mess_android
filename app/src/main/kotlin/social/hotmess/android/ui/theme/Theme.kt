package social.hotmess.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.audiencekit.Branding
import com.audiencekit.DesignTokens
import com.audiencekit.RGBAColor
import social.hotmess.android.R

/**
 * The AudienceKit design system's tokens (https://claude.ai/artifact/F1mB4QrCQSwi7ZahwgPWgd) for the
 * `hot_mess` preset, read from the SDK's generated [DesignTokens] so they can't drift from tokens.json. The tenant layer (accent and spectrum) can be overridden by the audience's
 * branding; everything else is locked.
 */
@Immutable
data class HotMessColors(
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val controlFill: Color,
    val controlFillHover: Color,
    val pressOverlay: Color,
    val border: Color,
    val borderStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val accentInk: Color,
    val focus: Color,
    val spectrum: List<Color>,
    val onSpectrum: List<Color>,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val onDanger: Color,
    /** Presence dot: connected to chat now. */
    val presenceOnline: Color,
    /** Presence dot: not in chat, but reachable by push (drawn as a ring). */
    val presencePush: Color,
    /** The push ring's outline, so it reads on white in light themes. */
    val presencePushEdge: Color,
    val isDark: Boolean,
) {
    /** Applies the audience's overrides of the tenant tokens for this mode. */
    fun withBranding(theme: Branding.Theme?): HotMessColors {
        theme ?: return this
        val overrides = if (isDark) theme.dark else theme.light
        fun token(name: String, fallback: Color): Color =
            overrides[name]?.let(RGBAColor::fromHex)?.let { Color(it.toArgb()) } ?: fallback
        return copy(
            accent = token("accent", accent),
            accentStrong = token("accent-strong", accentStrong),
            accentSoft = token("accent-soft", accentSoft),
            onAccent = token("on-accent", onAccent),
            accentInk = token("accent-ink", accentInk),
            focus = token("focus", focus),
            spectrum = spectrum.mapIndexed { index, color -> token("spectrum-${index + 1}", color) },
            onSpectrum = onSpectrum.mapIndexed { index, color -> token("on-spectrum-${index + 1}", color) },
        )
    }

    companion object {
        val Light = from(DesignTokens.hotMess, isDark = false)

        val Dark = from(DesignTokens.hotMessDark, isDark = true)

        private fun from(t: DesignTokens.Colors, isDark: Boolean) = HotMessColors(
            surface = t.surface.color,
            surfaceRaised = t.surfaceRaised.color,
            surfaceSunken = t.surfaceSunken.color,
            controlFill = t.controlFill.color,
            controlFillHover = t.controlFillHover.color,
            pressOverlay = t.pressOverlay.color,
            border = t.border.color,
            borderStrong = t.borderStrong.color,
            ink = t.ink.color,
            inkMuted = t.inkMuted.color,
            accent = t.accent.color,
            accentStrong = t.accentStrong.color,
            accentSoft = t.accentSoft.color,
            onAccent = t.onAccent.color,
            accentInk = t.accentInk.color,
            focus = t.focus.color,
            spectrum = listOf(t.spectrum1, t.spectrum2, t.spectrum3, t.spectrum4, t.spectrum5, t.spectrum6).map { it.color },
            onSpectrum = listOf(t.onSpectrum1, t.onSpectrum2, t.onSpectrum3, t.onSpectrum4, t.onSpectrum5, t.onSpectrum6).map { it.color },
            success = t.success.color,
            successSoft = t.successSoft.color,
            warning = t.warning.color,
            warningSoft = t.warningSoft.color,
            danger = t.danger.color,
            dangerSoft = t.dangerSoft.color,
            onDanger = t.onDanger.color,
            presenceOnline = t.presenceOnline.color,
            presencePush = t.presencePush.color,
            presencePushEdge = t.presencePushEdge.color,
            isDark = isDark,
        )
    }
}

/** A generated token as a Compose colour. */
private val RGBAColor.color: Color get() = Color(toArgb())

/** The 4px spacing scale. */
object Space {
    val s1 = DesignTokens.Spacing.s1.dp
    val s2 = DesignTokens.Spacing.s2.dp
    val s3 = DesignTokens.Spacing.s3.dp
    val s4 = DesignTokens.Spacing.s4.dp
    val s5 = DesignTokens.Spacing.s5.dp
    val s6 = DesignTokens.Spacing.s6.dp
    val s8 = DesignTokens.Spacing.s8.dp
    val s12 = DesignTokens.Spacing.s12.dp
}

/**
 * `radius-sm` 4, `radius-md` 6 (buttons, fields, the date tile), `radius-lg` 8 (cards, sheets),
 * `radius-bubble` 16 (chat bubbles), and the phone-only `radius-photo` 26 (photo cards, grouped
 * panels such as ChatPeek).
 */
object Radius {
    val sm = RoundedCornerShape(DesignTokens.Radius.sm.dp)
    val md = RoundedCornerShape(DesignTokens.Radius.md.dp)
    val lg = RoundedCornerShape(DesignTokens.Radius.lg.dp)
    val bubble = RoundedCornerShape(DesignTokens.Radius.bubble.dp)
    val photo = RoundedCornerShape(DesignTokens.Radius.photo.dp)
    val pill = RoundedCornerShape(percent = 50)
}

/** Feeds and audience pages are one column up to 680dp wide (`size-content-max`). */
val ContentMaxWidth = DesignTokens.Size.contentMax.dp

/**
 * Colours for text and fills over photos. They're the same in every theme, since what's under them
 * is a photo, not the wash.
 */
object PhotoColors {
    /** `scrim`: behind light text; its opacity is computed per photo (ImageTone). */
    val scrim = DesignTokens.hotMess.scrim.color

    /** `scrim-light`: behind dark `photo-ink` text, when that still needs a scrim. */
    val scrimLight = DesignTokens.hotMess.scrimLight.color

    /** `glass`: rgba(28,20,26,0.55), the fill of pills and badges on photos. */
    val glass = DesignTokens.hotMess.glass.color

    /** `on-photo`: text and icons over photos, glass pills and scrims. */
    val onPhoto = DesignTokens.hotMess.onPhoto.color

    /** `photo-ink`: dark text over photos bright enough to carry it without a scrim. */
    val photoInk = DesignTokens.hotMess.photoInk.color

    /** `photo-placeholder`: hero and photo card fill while the image loads. */
    val placeholder = DesignTokens.hotMess.photoPlaceholder.color
}

/** The design system's `size` tokens: heights the platforms share, in dp. */
object Sizes {
    /** HeroHeader body height below the status and top bars. */
    val hero = DesignTokens.Size.hero.dp

    /** Venue and event photo cards. */
    val photoCard = DesignTokens.Size.photoCard.dp

    /** Featured event photo cards. */
    val photoCardFeatured = DesignTokens.Size.photoCardFeatured.dp

    /** Person cards (blurred picture behind a sharp 56 avatar). */
    val personCard = DesignTokens.Size.personCard.dp

    /** FriendFaces stack, overlapping by 7. */
    val avatarXs = DesignTokens.Size.avatarXs.dp

    /** Chat bubbles and chat lines. */
    val avatarSm = DesignTokens.Size.avatarSm.dp

    /** Person cards, FriendStrip. */
    val avatarLg = DesignTokens.Size.avatarLg.dp

    /** Minimum NightCalendar cell height. */
    val calendarCell = DesignTokens.Size.calendarCell.dp

    /** Inline map on the Venues tab. */
    val mapInline = DesignTokens.Size.mapInline.dp
}

/** The design system's `opacity` tokens. */
object Opacity {
    /** Upper clamp on a computed photo scrim. */
    const val scrimMax = DesignTokens.Opacity.scrimMax

    /** Lower clamp on a photo scrim with high contrast text or reduced transparency (otherwise 0). */
    const val scrimFloor = DesignTokens.Opacity.scrimFloor

    /** NightCalendar level 1: `accent` at this opacity. */
    const val busy1 = DesignTokens.Opacity.busy1

    /** NightCalendar level 2: `accent` at this opacity, `ink` text. */
    const val busy2 = DesignTokens.Opacity.busy2

    /** Past nights in the calendar. */
    const val past = DesignTokens.Opacity.past

    /** The white ring around avatars that sit on photos. */
    const val ring = DesignTokens.Opacity.ring

    /**
     * A quiet glyph standing in for something missing, like RemoteImage's empty photo icon. Android
     * only, not a shared token yet.
     */
    const val placeholderGlyph = 0.5f
}

@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    R.font.figtree,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Figtree (variable, 300 to 900) for everything. */
val Figtree = FontFamily(figtree(400), figtree(600), figtree(700), figtree(800))

private fun style(t: DesignTokens.TypeStyle) = TextStyle(
    fontFamily = Figtree,
    fontSize = t.fontSize.sp,
    lineHeight = t.lineHeight.sp,
    fontWeight = FontWeight(t.fontWeight),
    letterSpacing = t.letterSpacing.em,
)

/** The design system's type scale. */
object HotMessType {
    val display = style(DesignTokens.Typography.display)
    val title = style(DesignTokens.Typography.title)
    val heading = style(DesignTokens.Typography.heading)
    val subheading = style(DesignTokens.Typography.subheading)
    val body = style(DesignTokens.Typography.body)
    val bodySmall = style(DesignTokens.Typography.bodySm)
    val label = style(DesignTokens.Typography.label)
    val caption = style(DesignTokens.Typography.caption)
}

private val typography = Typography(
    displayLarge = HotMessType.display,
    displayMedium = HotMessType.display,
    displaySmall = HotMessType.display,
    headlineLarge = HotMessType.title,
    headlineMedium = HotMessType.title,
    headlineSmall = HotMessType.title,
    titleLarge = HotMessType.title,
    titleMedium = HotMessType.heading,
    titleSmall = HotMessType.subheading,
    bodyLarge = HotMessType.body,
    bodyMedium = HotMessType.body,
    bodySmall = HotMessType.bodySmall,
    labelLarge = HotMessType.label,
    labelMedium = HotMessType.bodySmall,
    labelSmall = HotMessType.caption,
)

private val shapes = Shapes(
    extraSmall = Radius.sm,
    small = Radius.md,
    medium = Radius.lg,
    large = Radius.lg,
    extraLarge = Radius.lg,
)

private fun HotMessColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accentInk,
        secondary = accentInk,
        onSecondary = onAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = accentInk,
        tertiary = accentInk,
        background = surface,
        onBackground = ink,
        surface = surfaceRaised,
        onSurface = ink,
        surfaceVariant = controlFill,
        onSurfaceVariant = inkMuted,
        surfaceTint = Color.Transparent,
        surfaceBright = surfaceRaised,
        surfaceDim = surface,
        surfaceContainerLowest = surfaceRaised,
        surfaceContainerLow = surfaceRaised,
        surfaceContainer = surfaceRaised,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = controlFill,
        inverseSurface = ink,
        inverseOnSurface = surface,
        inversePrimary = accentStrong,
        outline = borderStrong,
        outlineVariant = border,
        error = danger,
        onError = onDanger,
        errorContainer = dangerSoft,
        onErrorContainer = danger,
        scrim = Color(0x99000000),
    )
}

val LocalHotMessColors = staticCompositionLocalOf { HotMessColors.Light }

/** The theme's tokens, for what Material's color scheme doesn't name (spectrum, wash, status). */
val tokens: HotMessColors
    @Composable get() = LocalHotMessColors.current

@Composable
fun HotMessTheme(
    branding: Branding.Theme? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = (if (darkTheme) HotMessColors.Dark else HotMessColors.Light).withBranding(branding)
    CompositionLocalProvider(LocalHotMessColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = typography, shapes = shapes, content = content)
    }
}
