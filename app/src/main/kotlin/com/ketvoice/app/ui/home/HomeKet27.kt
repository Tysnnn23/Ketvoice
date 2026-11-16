package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.MixKind
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.pageMargin
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.captureRevealOrigin
import com.ketvoice.app.ui.motion.rememberRevealOrigin

/**
 * Home in the manner of Apple Music (Ket 27, 2026-11-16): a large title that hands over to a small one
 * in the bar as the page scrolls, large cards for what is composed for the listener, and rows that
 * come to rest on a whole card.
 */
internal object HomeLayout {
    /** The gap between two cards of a row. */
    val gap: Dp = 12.dp
    /** Width of a large mix card on a phone. */
    val featureWidth: Dp = 248.dp
    /** How far the page scrolls before the small title has fully replaced the large one. */
    val titleHandover: Dp = 64.dp

    /** 0 while the large title is whole, 1 once it has scrolled [handoverPx] away; 1 past the first item. */
    fun collapse(firstIndex: Int, firstOffsetPx: Int, handoverPx: Float): Float =
        com.ketvoice.app.ui.common.ScrollTitle.collapse(firstIndex, firstOffsetPx, handoverPx)

    /**
     * How far a section header that is [topPx] from the top of a window [windowPx] tall has come in:
     * 0 as it appears at the bottom edge, 1 once it has risen [rangePx] into the window.
     */
    fun reveal(topPx: Float, windowPx: Float, rangePx: Float): Float {
        if (!topPx.isFinite() || windowPx <= 0f || rangePx <= 0f) return 1f
        return ((windowPx - topPx) / rangePx).coerceIn(0f, 1f)
    }
}

/** A row of cards that comes to rest on a whole card, its first one aligned with the page's margin. */
@Composable
internal fun HomeRow(modifier: Modifier = Modifier, gap: Dp = HomeLayout.gap, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    LazyRow(modifier, state = state, contentPadding = PaddingValues(horizontal = pageMargin),
        horizontalArrangement = Arrangement.spacedBy(gap),
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start), content = content)
}

/**
 * A section header rises and clears as it comes up from the bottom of the window, then stays put.
 * The movement follows the finger, read in the layer only; with reduced motion it is not drawn.
 */
internal fun Modifier.headerReveal(): Modifier = composed {
    if (Motion.reduced) return@composed this
    val progress = remember { mutableFloatStateOf(1f) }
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
    val range = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
    onGloballyPositioned { coordinates ->
        val window = coordinates.findRootCoordinates().size.height.toFloat()
        progress.floatValue = HomeLayout.reveal(coordinates.positionInRoot().y, window, range)
    }.graphicsLayer {
        val p = progress.floatValue
        alpha = .25f + .75f * p
        translationY = (1f - p) * rise
    }
}

/** The small title of the bar, shown as the large one scrolls away (see ScrollEdgeTitle). */
@Composable
internal fun HomeInlineTitle(title: String, collapse: () -> Float, modifier: Modifier = Modifier) =
    com.ketvoice.app.ui.common.ScrollEdgeTitle(title, collapse, modifier, tag = "home-inline-title")

/**
 * A mix as a large card, as Apple Music presents what it composed for the listener: its covers
 * on top, then a band in their colour with the mix's name. Opens the mix's page.
 */
@Composable
internal fun MixFeatureCard(card: MixCard, width: Dp) {
    val theme = LocalTheme.current
    val origin = rememberRevealOrigin()
    val title = mixTitle(card)
    val cover = card.covers.firstOrNull { !it.isNullOrBlank() }
    val tone = com.ketvoice.app.theme.rememberCoverTone(cover) ?: theme.accent
    // A deep version of the cover's colour: white words read on it whatever the cover.
    val band = Color(tone.red * .52f, tone.green * .52f, tone.blue * .52f)
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.width(width).captureRevealOrigin(origin).clip(shape).background(band)
        .border(.8.dp, Color.White.copy(alpha = if (theme.isDark) .10f else .0f), shape)
        .clickable(role = Role.Button) { AppUi.openCatalog(CatalogRequest("mix", card.id, title), origin.value) }
        .testTag("home-mix")) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            MixCollage(card.covers)
            // The covers melt into the band below them.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, .72f to Color.Transparent, 1f to band)))
            Row(Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = .38f)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(mixGlyph(card.kind), null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text("Mix", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 5.dp))
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)) {
            Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mixSubtitle(card), color = Color.White.copy(alpha = .78f), fontSize = 13.sp, lineHeight = 17.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

internal fun mixGlyph(kind: MixKind) = when (kind) {
    MixKind.Artist -> KetIcons.Music
    MixKind.Style -> KetIcons.Globe
    MixKind.OnRepeat -> KetIcons.Refresh
    MixKind.Rediscover -> KetIcons.Clock
}
