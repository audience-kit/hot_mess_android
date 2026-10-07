package social.hotmess.android.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.PhotoColors
import social.hotmess.android.ui.theme.Sizes
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.PhotoTone
import kotlin.math.max

// HeroHeader (design system HeroHeader): the full-bleed photo at the top of Venue, Event, Person and
// Now, running up under the status bar and a clear top bar, with the title written over its bottom
// edge in whichever colour the photo can carry.

/** Material's small top app bar height. */
private val TopBarHeight = 64.dp

/** Text sits 20 from the hero's sides and bottom. */
private val HeroInset = 20.dp

/** The bottom scrim fades out over this much above the text band. */
private val HeroFade = 90.dp

/** The fade under the status bar when neither bar style reaches 3:1. */
private val TopFade = 40.dp

/**
 * The hero itself. Put it first in a [HeroFeed] under a [HeroScaffold], which passes [topInset] (the
 * status bar plus the top bar). The body is `size-hero` (256) below that, taller if [content] needs
 * it. [onTone] reports what the brightness check decided, so the scaffold can style the bar to match.
 */
@Composable
fun HeroHeader(
    url: String?,
    topInset: Dp,
    onTone: (PhotoTone) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var textHeight by remember { mutableIntStateOf(0) }

    // The text band (the text plus 8 above, inset 16 from the sides) and the status and top bar area.
    val areas: Pair<Rect, Rect>? = if (size.width == 0 || textHeight == 0) {
        null
    } else {
        with(density) {
            val height = size.height.toFloat()
            val textTop = height - HeroInset.toPx() - textHeight
            Rect(16.dp.toPx(), max(0f, textTop - 8.dp.toPx()), size.width - 16.dp.toPx(), height) to
                Rect(0f, 0f, size.width.toFloat(), topInset.toPx().coerceAtMost(height))
        }
    }
    val band = areas?.first
    val top = areas?.second

    val tone = rememberPhotoTone(url, size, band, top, kind = "hero")
    LaunchedEffect(tone) { onTone(tone) }

    val textColor by animateColorAsState(tone.textColor, toneAnimation(), label = "hero text")
    val scrimColor by animateColorAsState(tone.scrimColor, toneAnimation(), label = "hero scrim colour")
    val scrimOpacity by animateFloatAsState(tone.scrim.toFloat(), toneAnimation(), label = "hero scrim")
    val barScrimColor by animateColorAsState(tone.barScrimColor, toneAnimation(), label = "bar scrim colour")
    val barScrimOpacity by animateFloatAsState(tone.barScrim.toFloat(), toneAnimation(), label = "bar scrim")

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = topInset + Sizes.hero)
            .clipToBounds()
            .background(PhotoColors.placeholder)
            .onSizeChanged { size = it },
    ) {
        PhotoLayer(url, Modifier.matchParentSize())
        Box(
            Modifier
                .fillMaxWidth()
                .height(topInset + TopFade)
                .background(Brush.verticalGradient(listOf(barScrimColor.copy(alpha = barScrimOpacity), barScrimColor.copy(alpha = 0f)))),
        )
        Scrim(scrimColor, scrimOpacity, band = band?.height ?: 0f, fade = HeroFade, modifier = Modifier.matchParentSize())
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = HeroInset, end = HeroInset, bottom = HeroInset, top = topInset + 16.dp)
                .onSizeChanged { textHeight = it.height },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides textColor) { content() }
        }
    }
}

/**
 * Whether a hero at the top of [state] has scrolled under the top bar, so the bar should turn solid
 * and show the screen's title.
 */
@Composable
fun rememberHeroCollapsed(state: LazyListState): State<Boolean> {
    val threshold = with(LocalDensity.current) { (Sizes.hero - 8.dp).toPx() }
    return remember(state, threshold) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > threshold }
    }
}

/**
 * A screen that opens on a [HeroHeader]: [content] fills the screen from the top edge, and the top
 * bar floats over it, clear with its back button and actions (and the status bar) in the colour
 * [tone] picked for the photo, then the `surface` wash with [title] once [collapsed]. Pass
 * `collapsed = true` while there's no hero to show (loading, errors), so the bar reads on the wash.
 * [content] gets the height the hero runs up under.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeroScaffold(
    title: String,
    tone: PhotoTone,
    collapsed: Boolean,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (topInset: Dp) -> Unit,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    val solid = tokens.surface
    val barColor by animateColorAsState(if (collapsed) solid else solid.copy(alpha = 0f), toneAnimation(), label = "bar")
    val iconColor by animateColorAsState(if (collapsed) tokens.ink else tone.barContentColor, toneAnimation(), label = "bar icons")

    StatusBarIcons(light = if (collapsed) tokens.isDark else tone.lightBar)

    Box(Modifier.fillMaxSize().background(tokens.surface)) {
        content(topInset)
        TopAppBar(
            title = {
                if (collapsed) Text(title, style = HotMessType.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = barColor,
                scrolledContainerColor = barColor,
                titleContentColor = tokens.ink,
                navigationIconContentColor = iconColor,
                actionIconContentColor = iconColor,
            ),
        )
    }
}

/** The screen whose hero set the status bar icons last; only it puts them back. */
private var statusBarOwner: Any? = null

/** White status bar icons over a dark photo ([light]), dark ones over a bright one; the theme's again when the screen goes. */
@Composable
private fun StatusBarIcons(light: Boolean) {
    val view = LocalView.current
    val themeDark = tokens.isDark
    val owner = remember { Any() }
    if (view.isInEditMode) return
    DisposableEffect(view, light, themeDark) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !light
        statusBarOwner = owner
        onDispose {
            if (statusBarOwner === owner) {
                controller?.isAppearanceLightStatusBars = !themeDark
                statusBarOwner = null
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
