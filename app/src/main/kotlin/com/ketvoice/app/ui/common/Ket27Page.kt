package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
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
 * What every page shares, in one place (Ket 27): the large title of a root page, its section
 * headers, the bar of a page reached from another, the alert.
 */

/** The margin of a page: 16 dp, as on iOS. */
internal val pageMargin: Dp get() = Ket27.Metrics.margin

/**
 * The title a root page opens on, written as iOS writes a large title (34, bold). With [neon] (Home, Explorer and the
 * Library) the Vice City theme writes it in its 80s chrome with a neon underline (6.3, `ui/vicecity/ViceCityNeon.kt`).
 */
@Composable
internal fun PageTitle(text: String, modifier: Modifier = Modifier, color: Color = LocalTheme.current.onBackground,
                       localize: Boolean = true, maxLines: Int = 1, neon: Boolean = false) {
    if (neon && LocalTheme.current.viceCity) {
        com.ketvoice.app.ui.vicecity.ViceCityPageTitle(if (localize) com.ketvoice.app.data.AppText.t(text) else text,
            modifier, Ket27.Type.largeTitle, maxLines)
        return
    }
    Text(text, modifier, color = color, style = Ket27.Type.largeTitle, localize = localize, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis)
}

/**
 * The header of a section of a page, as iOS 27 writes it: in sentence case (« Écouté récemment »),
 * title 2 (22, bold), in the page's text colour. [title] is a French literal, translated at display.
 */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier, localize: Boolean = true) {
    Text(title, modifier.semantics { heading() }, color = LocalTheme.current.onBackground, style = Ket27.Type.title2,
        localize = localize, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Ket27DialogCard(onDismissRequest, modifier.testTag("ket27-alert")) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val centred: TextStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
                if (title != null) ProvideTextStyle(centred.merge(Ket27.Type.headline).copy(color = theme.onBackground, textAlign = TextAlign.Center)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).semantics { heading() }, contentAlignment = Alignment.Center) { title() }
                }
                if (text != null) ProvideTextStyle(centred.merge(Ket27.Type.footnote).copy(color = theme.secondary, textAlign = TextAlign.Center)) {
                    Box(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = if (title != null) 6.dp else 0.dp),
                        contentAlignment = Alignment.Center) { text() }
                }
                Ket27AlertButtons(confirmButton, dismissButton, Modifier.padding(top = 18.dp))
            }
    }
}

/** The buttons of a Ket 27 alert: two capsules side by side, the confirmation tinted with the app's colour. */
@Composable
internal fun Ket27AlertButtons(confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val capsule = RoundedCornerShape(999.dp)
        if (dismissButton != null) Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(capsule)
            .background(theme.onBackground.copy(alpha = .08f)), contentAlignment = Alignment.Center, propagateMinConstraints = true) { dismissButton() }
        Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(capsule).background(theme.accent.copy(alpha = .16f)),
            contentAlignment = Alignment.Center, propagateMinConstraints = true) { confirmButton() }
    }
}

/**
 * The card every Ket 27 dialog is drawn on: rounded glass (34 dp corners) in the middle of the
 * screen, at most 320 dp wide, its content laid out by the caller (an alert, a short choice, a field).
 */
@Composable
internal fun Ket27DialogCard(onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalTheme.current
    Dialog(onDismissRequest) {
        val shape = RoundedCornerShape(Ket27.Metrics.alertRadius)
        GlassPanel(modifier.widthIn(max = 320.dp).fillMaxWidth(), shape, GlassParams.sheet(34f, dark = theme.isDark)) {
            Column(Modifier.fillMaxWidth().background(theme.card.copy(alpha = if (theme.isDark) .82f else .9f))
                .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 16.dp), content = content)
        }
    }
}

/**
 * The back link inside a page, as iOS writes it: a chevron and the name of the level above, in the
 * app's colour, with a 44 dp target. [label] is a French literal, translated at display.
 */
@Composable
internal fun BackLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Row(modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(KetIcons.ChevronRight, null, tint = theme.accent, modifier = Modifier.size(18.dp).turnedBack())
        Text(label, color = theme.accent, style = Ket27.Type.body, modifier = Modifier.padding(start = 4.dp))
    }
}

/** The chevron of an iOS back button: the app's right-pointing chevron, turned round. */
internal fun Modifier.turnedBack(): Modifier = graphicsLayer { scaleX = -1f }
