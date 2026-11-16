package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme

/**
 * What every page shares under Ket 27, in one place: the large title of a root page, the bar of a
 * page reached from another, the alert. Each falls back to what the classic page draws.
 */

/** The margin of a page: 16 dp under Ket 27, the page's own otherwise. */
internal fun pageMargin(classic: Dp): Dp = if (Ket27.on) Ket27.Metrics.margin else classic

/**
 * The title a root page opens on. Ket 27 writes it as iOS writes a large title (34, bold); the
 * classic interface keeps the size, weight and tracking the page chose.
 */
@Composable
internal fun PageTitle(text: String, classicSize: TextUnit, modifier: Modifier = Modifier, classicWeight: FontWeight = FontWeight.Bold,
                       classicSpacing: TextUnit = TextUnit.Unspecified, classicLineHeight: TextUnit = TextUnit.Unspecified,
                       color: Color = LocalTheme.current.onBackground, localize: Boolean = true, maxLines: Int = 1) {
    if (Ket27.on) Text(text, modifier, color = color, style = Ket27.Type.largeTitle, localize = localize, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis)
    else Text(text, modifier, color = color, fontSize = classicSize, fontWeight = classicWeight, letterSpacing = classicSpacing,
        lineHeight = classicLineHeight, localize = localize, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * Ket 27: the bar of a page reached from another, as on iOS 27 — a round glass button to go back,
 * the name of the page in the middle (17, semibold) and what the page adds at the end.
 */
@Composable
internal fun Ket27PageBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, localize: Boolean = true,
                          trailing: @Composable RowScope.() -> Unit = {}) {
    val theme = LocalTheme.current
    Box(modifier.fillMaxWidth().height(54.dp).padding(horizontal = Ket27.Metrics.margin).testTag("ket27-page-bar")) {
        Box(Modifier.align(Alignment.CenterStart)) { GlassBackButton(onBack) }
        Text(title, localize = localize, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp).semantics { heading() })
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/**
 * Ket 27's alert: a rounded glass card in the middle of the screen, its title and message centred,
 * and its buttons as two capsules side by side — the confirmation tinted with the app's colour.
 */
@Composable
internal fun Ket27Alert(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
                        dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
                        text: (@Composable () -> Unit)? = null) {
    val theme = LocalTheme.current
    Dialog(onDismissRequest) {
        val shape = RoundedCornerShape(Ket27.Metrics.alertRadius)
        GlassPanel(modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("ket27-alert"), shape, GlassParams.sheet(34f, dark = theme.isDark)) {
            Column(Modifier.fillMaxWidth().background(theme.card.copy(alpha = if (theme.isDark) .82f else .9f))
                .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val centred: TextStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
                if (title != null) ProvideTextStyle(centred.merge(Ket27.Type.headline).copy(color = theme.onBackground, textAlign = TextAlign.Center)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).semantics { heading() }, contentAlignment = Alignment.Center) { title() }
                }
                if (text != null) ProvideTextStyle(centred.merge(Ket27.Type.footnote).copy(color = theme.secondary, textAlign = TextAlign.Center)) {
                    Box(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = if (title != null) 6.dp else 0.dp),
                        contentAlignment = Alignment.Center) { text() }
                }
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val capsule = RoundedCornerShape(999.dp)
                    if (dismissButton != null) Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(capsule)
                        .background(theme.onBackground.copy(alpha = .08f)), contentAlignment = Alignment.Center, propagateMinConstraints = true) { dismissButton() }
                    Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(capsule).background(theme.accent.copy(alpha = .16f)),
                        contentAlignment = Alignment.Center, propagateMinConstraints = true) { confirmButton() }
                }
            }
        }
    }
}

/** The chevron of an iOS back button: the app's right-pointing chevron, turned round. */
internal fun Modifier.turnedBack(): Modifier = graphicsLayer { scaleX = -1f }
