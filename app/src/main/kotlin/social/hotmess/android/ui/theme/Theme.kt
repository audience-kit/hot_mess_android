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
import com.audiencekit.RGBAColor
import social.hotmess.android.R

/**
 * The AudienceKit design system's tokens (https://claude.ai/artifact/F1mB4QrCQSwi7ZahwgPWgd) for the
 * `hot_mess` preset. The tenant layer (accent and spectrum) can be overridden by the audience's
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
        val Light = HotMessColors(
            surface = Color(0xFFF6EFF3),
            surfaceRaised = Color(0xFFFFFFFF),
            surfaceSunken = Color(0xFFFAF4F7),
            controlFill = Color(0xFFEFE4EA),
            controlFillHover = Color(0xFFE6D7DF),
            pressOverlay = Color(0x1A24161D),
            border = Color(0xFFE6D3DC),
            borderStrong = Color(0xFF937F89),
            ink = Color(0xFF24161D),
            inkMuted = Color(0xFF6B5360),
            accent = Color(0xFFB8236F),
            accentStrong = Color(0xFF951B5A),
            accentSoft = Color(0xFFFDE6F1),
            onAccent = Color(0xFFFFFFFF),
            accentInk = Color(0xFFA01F61),
            focus = Color(0xFF7A1D6E),
            spectrum = listOf(Color(0xFFE40303), Color(0xFFFF8C00), Color(0xFFFFED00), Color(0xFF008026), Color(0xFF004DFF), Color(0xFF750787)),
            onSpectrum = listOf(Color(0xFFFFFFFF), Color(0xFF1D2125), Color(0xFF1D2125), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFFFFFFF)),
            success = Color(0xFF1D7144),
            successSoft = Color(0xFFE5F4EC),
            warning = Color(0xFF8A5300),
            warningSoft = Color(0xFFFDF1D9),
            danger = Color(0xFFB3261E),
            dangerSoft = Color(0xFFFCEBEA),
            onDanger = Color(0xFFFFFFFF),
            isDark = false,
        )

        val Dark = HotMessColors(
            surface = Color(0xFF1A1519),
            surfaceRaised = Color(0xFF272026),
            surfaceSunken = Color(0xFF201A1F),
            controlFill = Color(0xFF3D3239),
            controlFillHover = Color(0xFF4B3F47),
            pressOverlay = Color(0x2EFFFFFF),
            border = Color(0xFF3E333B),
            borderStrong = Color(0xFF8A7A85),
            ink = Color(0xFFF3E6EE),
            inkMuted = Color(0xFFC2ADBA),
            accent = Color(0xFFFF7AB6),
            accentStrong = Color(0xFFFFA6CF),
            accentSoft = Color(0xFF3B1A2C),
            onAccent = Color(0xFF3A0A22),
            accentInk = Color(0xFFFF93C4),
            focus = Color(0xFFFFA6CF),
            spectrum = listOf(Color(0xFFFF4D4D), Color(0xFFFFA133), Color(0xFFFFE94D), Color(0xFF2FBF5B), Color(0xFF4D86FF), Color(0xFFC77DFF)),
            onSpectrum = List(6) { Color(0xFF1D2125) },
            success = Color(0xFF5FD394),
            successSoft = Color(0xFF12301F),
            warning = Color(0xFFF2B84B),
            warningSoft = Color(0xFF34270E),
            danger = Color(0xFFFF8A80),
            dangerSoft = Color(0xFF3A1716),
            onDanger = Color(0xFF3A0805),
            isDark = true,
        )
    }
}

/** The 4px spacing scale. */
object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s12 = 48.dp
}

/** `radius-sm` 4, `radius-md` 6 (buttons, fields, the date tile), `radius-lg` 8 (cards, sheets). */
object Radius {
    val sm = RoundedCornerShape(4.dp)
    val md = RoundedCornerShape(6.dp)
    val lg = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(percent = 50)
}

/** Feeds and audience pages are one column up to 680dp wide. */
val ContentMaxWidth = 680.dp

@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    R.font.figtree,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Figtree (variable, 300 to 900) for everything. */
val Figtree = FontFamily(figtree(400), figtree(600), figtree(700), figtree(800))

/** The design system's type scale. */
object HotMessType {
    val display = TextStyle(fontFamily = Figtree, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight(800), letterSpacing = (-0.02).em)
    val title = TextStyle(fontFamily = Figtree, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em)
    val heading = TextStyle(fontFamily = Figtree, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val subheading = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val bodySmall = TextStyle(fontFamily = Figtree, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontFamily = Figtree, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em)
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
