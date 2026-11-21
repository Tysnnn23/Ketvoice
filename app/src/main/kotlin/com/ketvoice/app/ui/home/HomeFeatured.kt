package com.ketvoice.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.pageMargin
import com.ketvoice.app.ui.motion.Motion
import kotlin.math.abs

/**
 * 8.2 « À la une »: large cards at the top of Home. Only the card in the middle of the row plays its Canvas, muted
 * and looped, edge to edge; the others show their still (or their cover). Nothing plays when the row is off screen,
 * when the player covers Home, in the performance mode, with reduced motion, with « Pochettes animées » off, or on a
 * metered network while the Canvas preload is off.
 */
internal object HomeFeatured {
    const val COUNT = 5
    /** Width over height of a card: upright, as a Canvas is, without hiding a square cover's middle. */
    const val ASPECT = .84f

    /** Whether the centred card may play its Canvas now. */
    fun plays(onScreen: Boolean, playerOpen: Boolean, highPerformance: Boolean, reduced: Boolean, animatedArtwork: Boolean,
              unmetered: Boolean, preload: Boolean): Boolean =
        onScreen && !playerOpen && !highPerformance && !reduced && animatedArtwork && (unmetered || preload)

    /** The index of the item whose middle is nearest the viewport's middle. */
    fun centred(viewportStart: Int, viewportEnd: Int, items: List<Pair<Int, IntRange>>): Int {
        val middle = (viewportStart + viewportEnd) / 2
        return items.minByOrNull { (_, span) -> abs((span.first + span.last) / 2 - middle) }?.first ?: 0
    }
}

/** Whether at least half of the item [key] of [state] is on screen. */
internal fun homeItemMostlyShown(state: androidx.compose.foundation.lazy.LazyListState, key: Any): Boolean {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
    val top = maxOf(item.offset, info.viewportStartOffset)
    val bottom = minOf(item.offset + item.size, info.viewportEndOffset)
    return (bottom - top) * 2 >= item.size
}

@Composable
internal fun HomeFeaturedRow(tracks: List<Track>, onScreen: () -> Boolean, modifier: Modifier = Modifier) {
    val shown = remember(tracks) { tracks.take(HomeFeatured.COUNT) }
    if (shown.isEmpty()) return
    val context = LocalContext.current
    val row = rememberLazyListState()
    // Both change only when a card settles in the middle or the row comes and goes: no composition while scrolling.
    val centred by remember(row) { derivedStateOf {
        val info = row.layoutInfo
        HomeFeatured.centred(info.viewportStartOffset, info.viewportEndOffset,
            info.visibleItemsInfo.map { it.index to (it.offset until it.offset + it.size) })
    } }
    val visible by remember { derivedStateOf(onScreen) }
    // Read once each time Home comes back: the network seldom changes while Home is looked at.
    var unmetered by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { unmetered = CanvasRepo.unmetered(context); onPauseOrDispose { } }
    val allowed = HomeFeatured.plays(visible, AppUi.showPlayer.value, AppSettings.highPerformanceMode.value, Motion.reduced,
        AppSettings.animatedArtwork.value, unmetered, AppSettings.canvasPreloadWifi.value)
    val paths by CanvasRepo.paths.collectAsState()
    val lead = shown.getOrNull(centred)
    LaunchedEffect(lead?.id, allowed) {
        if (allowed && lead != null && !CanvasRepo.isExcluded(lead.id)) runCatching { CanvasRepo.resolve(lead) }
    }
    Column(modifier.testTag("home-featured")) {
        EditorialHeader("À la une")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 600.dp
            val card = if (wide) 300.dp else (maxWidth - pageMargin * 2) * .86f
            LazyRow(Modifier.padding(bottom = Ket27.Space.m), state = row, contentPadding = PaddingValues(horizontal = pageMargin),
                horizontalArrangement = Arrangement.spacedBy(HomeLayout.gap),
                flingBehavior = rememberSnapFlingBehavior(row, if (wide) SnapPosition.Start else SnapPosition.Center)) {
                itemsIndexed(shown, key = { _, t -> t.id }) { index, track ->
                    val canvas = paths[track.id]?.takeUnless { CanvasRepo.isExcluded(track.id) }
                    FeaturedCard(track, canvas, playing = allowed && index == centred, width = card) {
                        Player.playQueue(shown, index)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedCard(track: Track, canvas: String?, playing: Boolean, width: Dp, onPlay: () -> Unit) {
    val cover = com.ketvoice.app.ui.common.rememberTrackCover(track)
    val shape = RoundedCornerShape(Ket27.Radius.card)
    Box(Modifier.width(width).aspectRatio(HomeFeatured.ASPECT).clip(shape).background(Color(0xFF1C1C1E))
        .clickable(role = Role.Button, onClick = onPlay).testTag("home-featured-card")) {
        when {
            playing && canvas != null -> com.ketvoice.app.ui.sub.MotionArtwork(canvas, playing = true,
                Modifier.fillMaxSize().testTag("home-featured-canvas"), cover = cover)
            else -> {
                val poster = com.ketvoice.app.ui.sub.rememberCanvasPoster(canvas)
                if (poster != null) Image(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (cover != null) coil.compose.AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        // One shade under the words, only as tall as they need; the picture stays clear above it.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.42f).drawWithCache {
            val shade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .62f)))
            onDrawBehind { drawRect(shade) }
        })
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(Ket27.Space.l),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, localize = false, color = Color.White, style = Ket27.Type.title3, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(track.artist, localize = false, color = Color.White.copy(alpha = .8f), style = Ket27.Type.subheadline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Box(Modifier.padding(start = Ket27.Space.s).size(44.dp).clip(CircleShape).background(Color.White)
                .clickable(role = Role.Button, onClickLabel = null, onClick = onPlay), contentAlignment = Alignment.Center) {
                Icon(KetIcons.Play, "Lecture", tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }
    }
}
