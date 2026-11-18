package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarData
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme

/**
 * The confirmation of an action, as Ket 27 draws it in place of Material's snackbar: a capsule of
 * glass above the tab bar, its message in the page's ink and its action (« Annuler ») in the app's
 * colour. The message and the action arrive already translated (see [TrackActionFeedback]).
 */
@Composable
fun KetSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(26.dp)
    // Vice City (6.3): the toast wears the theme's neon edge.
    GlassPanel(modifier.padding(horizontal = Ket27.Metrics.margin).widthIn(max = 520.dp).fillMaxWidth().testTag("ket-snackbar")
        .then(sheetEdge(shape)),
        shape, GlassParams.pill(dark = theme.isDark)) {
        Row(Modifier.fillMaxWidth().background(theme.card.copy(alpha = if (theme.isDark) .55f else .7f))
            .heightIn(min = 52.dp).padding(start = 20.dp, end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically) {
            Text(data.visuals.message, localize = false, color = theme.onBackground, style = Ket27.Type.subheadline,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            val action = data.visuals.actionLabel
            if (action != null) Text(action, localize = false, color = theme.accent, style = Ket27.Type.subheadline,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.heightIn(min = 44.dp)
                    .clickable(role = Role.Button) { data.performAction() }.padding(horizontal = 12.dp, vertical = 12.dp))
            else androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 12.dp))
        }
    }
}
