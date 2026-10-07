package social.hotmess.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens

/** Rows in a DetailSection, its title and its dividers are inset 20 from the panel's edges. */
val DetailInset = 20.dp

/** A DetailSection row's padding: 11 above and below, 20 at the sides. */
val DetailRowPadding = PaddingValues(horizontal = DetailInset, vertical = 11.dp)

/**
 * A titled group of rows on a raised panel (design system DetailSection), for detail screens and
 * settings: the [title] on the wash in subheading weight and `ink-muted`, then the rows on a
 * `surface-raised` panel with `radius-photo` corners, then an optional [footer]. Rows bring the
 * [DetailRowPadding] themselves (RowButton, InfoRow and EmptyRow do) and are split with [RowDivider]
 * or [CardRows].
 */
@Composable
fun DetailSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        if (title != null) SectionTitle(title)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = Radius.photo,
            color = tokens.surfaceRaised,
            contentColor = tokens.ink,
            shadowElevation = 1.dp,
        ) {
            Column(content = content)
        }
        if (footer != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = DetailInset)) { footer() }
        }
    }
}
