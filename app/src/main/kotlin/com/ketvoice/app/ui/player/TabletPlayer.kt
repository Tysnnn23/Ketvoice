package com.ketvoice.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LargeScreen
import com.ketvoice.app.ui.adaptive.LargeScreenNav
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.launch

/**
 * The full player of a tablet, a foldable or a desktop window (anything but a phone and Quest).
 *
 * Lying, the artwork (or its Canvas) and the controls stand on the left and the lyrics fill the right,
 * in the listener's lyrics style; standing, the artwork sits beside the controls and the lyrics run
 * below. The queue opens as a panel of its own: next to the lyrics when the window is wide enough for
 * both, in their place otherwise. Without lyrics (none found, or hidden) the artwork takes the stage.
 * A foldable half-opened like a laptop keeps the artwork and the lyrics above the hinge and the controls
 * below it; held like a book, the controls stay on the left page and the lyrics on the right.
 *
 * It opens with the movement of the phone: [PlayerPresentation] unfolds it from the docked mini-player,
 * whose cover, title, play and next buttons travel to their places here ([travelFromMini]), the other
 * controls rising a little later; dragged down by its grabber or its artwork, it folds back the same way
 * ([PlayerDismiss] decides when it closes).
 */
@Composable
internal fun TabletPlayer(
    state: PlayerUiState, track: Track, cover: String?, canvas: String?, ambience: CoverAmbience,
    page: PlayerPage, onPage: (PlayerPage) -> Unit, onClose: () -> Unit,
    onTrackMenu: () -> Unit, onSources: () -> Unit, onOutput: () -> Unit,
    onCanvas: (() -> Unit)? = null
) {
    val backdrop = rememberGlassBackdrop(Color.Black)
    val lyrics by LyricsRepo.state.collectAsState()
    // Lyrics show unless the listener hides them; with none found they make room, unless asked for (to pick a source).
    var lyricsHidden by rememberSaveable { mutableStateOf(false) }
    var lyricsAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(page) {
        // Here the lyrics are not a page of their own: asking for them only makes sure they are shown.
        if (page == PlayerPage.LYRICS) { lyricsHidden = false; lyricsAsked = true; onPage(PlayerPage.ARTWORK) }
    }
    // Ctrl/⌘ U and the queue button of the docked mini-player open the player on its queue.
    val queueAsked = LargeScreenNav.openQueue.value
    LaunchedEffect(queueAsked) { if (queueAsked) { LargeScreenNav.openQueue.value = false; onPage(PlayerPage.QUEUE) } }
    val hasLyrics = lyrics.loading || lyrics.lines.isNotEmpty() || lyricsAsked

    val reveal = LocalPlayerReveal.current
    val presentation = LocalPlayerDismissOffset.current
    val travels = presentation != null && !Motion.reduced
    val lateRise = if (!travels) Modifier else Modifier.graphicsLayer {
        val rise = controlsReveal(reveal())
        alpha = rise
        translationY = (1f - rise) * 28.dp.toPx()
    }
    val dismiss = presentation ?: remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("tablet-player")) {
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val dismissDrag = Modifier.playerDismissDrag(dismiss, heightPx,
                onSettle = { scope.launch { if (Motion.reduced) dismiss.snapTo(0f) else dismiss.animateTo(0f, spring(dampingRatio = .78f, stiffness = 380f)) } },
                onDismiss = onClose)
            val plan = LargeScreen.playerPlan(maxWidth.value, maxHeight.value, lyricsWanted = !lyricsHidden && hasLyrics,
                queueWanted = page == PlayerPage.QUEUE)
            val gutter = if (maxWidth >= 1000.dp) 40.dp else 24.dp
            val windowWidth = maxWidth.value
            val windowHeight = maxHeight.value
            val density = LocalDensity.current
            val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
            val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
            val tabletop = if (plan.landscape) com.ketvoice.app.ui.adaptive.rememberTabletop()?.let { fold ->
                with(density) { com.ketvoice.app.ui.adaptive.tabletopSplit(fold.hingeTopPx.toDp().value, fold.hingeBottomPx.toDp().value,
                    maxHeight.value, bottomInset.value) }
            } else null
            val book = if (plan.landscape && tabletop == null) com.ketvoice.app.ui.adaptive.rememberBookFold()?.let { fold ->
                with(density) { com.ketvoice.app.ui.adaptive.bookPane(fold.hingeLeftPx.toDp().value, fold.hingeRightPx.toDp().value,
                    maxWidth.value, gutter.value) }?.let { it.dp to with(density) { (fold.hingeRightPx - fold.hingeLeftPx).toDp() } }
            } else null

            BackdropCapture(backdrop, Modifier.matchParentSize()) {
                PlayerSceneBackground(track, canvas, state.isPlaying, cover, ambience, simple = true)
            }
            Column(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset).padding(horizontal = gutter)) {
                TabletPlayerBar(plan, onClose, dismissDrag, lateRise,
                    onLyrics = {
                        if (plan.lyrics) lyricsHidden = true
                        else {
                            lyricsHidden = false; lyricsAsked = true
                            // A window too narrow for both shows the lyrics in the queue's place.
                            if (plan.queue && !LargeScreen.playerPlan(windowWidth, windowHeight, true, true).queueBesideLyrics) onPage(PlayerPage.ARTWORK)
                        }
                    },
                    onQueue = { onPage(if (page == PlayerPage.QUEUE) PlayerPage.ARTWORK else PlayerPage.QUEUE) },
                    onOutput = onOutput, track = track)
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 20.dp)) {
                    val controls: @Composable (Modifier, Boolean) -> Unit = { modifier, centred ->
                        NowPlayingControls(state, track, cover, onTrackMenu, travels, lateRise, modifier, centred)
                    }
                    val artwork: @Composable (Dp) -> Unit = { side ->
                        TabletArtwork(track, canvas, state.isPlaying, side, onCanvas, travels, reveal, dismissDrag)
                    }
                    val side: @Composable (Modifier) -> Unit = { modifier -> SidePanes(plan, state, track, onSources, modifier) }
                    when {
                        tabletop != null -> TabletopBody(tabletop.sceneHeightDp.dp - topInset - 56.dp, plan, artwork, controls, side)
                        plan.landscape -> LandscapeBody(plan, book, artwork, controls, side)
                        else -> PortraitBody(plan, artwork, controls, side)
                    }
                }
            }
        }
    }
}

/** The grabber that folds the player away, and the buttons of the player at the top: lyrics, queue, output, more. */
@Composable
private fun TabletPlayerBar(plan: LargeScreen.PlayerPlan, onClose: () -> Unit, dismissDrag: Modifier, lateRise: Modifier,
                            onLyrics: () -> Unit, onQueue: () -> Unit, onOutput: () -> Unit, track: Track) {
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        SmallCircleButton(onClose, size = 40.dp) { ink ->
            Icon(Icons.Filled.KeyboardArrowDown, "Fermer le lecteur", tint = ink, modifier = Modifier.size(26.dp))
        }
        // The grabber: drag it down to fold the player into the mini-player, or touch it to close.
        val closeLabel = AppText.t("Fermer le lecteur")
        Box(Modifier.align(Alignment.TopCenter).width(140.dp).height(40.dp).then(dismissDrag)
            .clickable(onClick = onClose).semantics { contentDescription = closeLabel }.testTag("tablet-player-grabber"),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(44.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .45f)))
        }
        Row(Modifier.align(Alignment.CenterEnd).then(lateRise), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            com.ketvoice.app.ui.together.KetogetherLivePill(Modifier.padding(end = 4.dp))
            SmallCircleButton(onLyrics, active = plan.lyrics, size = 40.dp, transparent = !plan.lyrics) { ink ->
                LyricsGlyph(Modifier.size(22.dp).semantics { contentDescription = AppText.t("Paroles") }.testTag("tablet-player-lyrics"), ink)
            }
            SmallCircleButton(onQueue, active = plan.queue, size = 40.dp, transparent = !plan.queue) { ink ->
                QueueGlyph(Modifier.size(22.dp).semantics { contentDescription = AppText.t("File d'attente") }.testTag("tablet-player-queue"), ink)
            }
            SmallCircleButton(onOutput, size = 40.dp, transparent = true) { ink ->
                Icon(Icons.Filled.PhoneAndroid, "Sortie audio", tint = ink, modifier = Modifier.size(20.dp))
            }
            val menuAnchor = com.ketvoice.app.ui.common.rememberRowAnchor()
            Box(menuAnchor.modifier) {
            SmallCircleButton({ menuAnchor.open(track) }, size = 40.dp, transparent = true) { ink ->
                Icon(Icons.Filled.MoreHoriz, "Options", tint = ink, modifier = Modifier.size(22.dp))
            }
            }
        }
    }
}

/** The artwork, or the Canvas playing in it; touched, the Canvas fills the window. It travels from the mini-player's cover. */
@Composable
private fun TabletArtwork(track: Track, canvas: String?, playing: Boolean, side: Dp, onCanvas: (() -> Unit)?,
                          travels: Boolean, reveal: () -> Float, dismissDrag: Modifier) {
    val shape = RoundedCornerShape(if (side >= 400.dp) 26.dp else 18.dp)
    val canvasLabel = AppText.t("Voir le Canvas en plein écran")
    Box(Modifier.size(side)
        .then(if (travels) Modifier.travelFromMini({ AppUi.miniCoverBounds.value }, reveal) else Modifier)
        .shadow(28.dp, shape, ambientColor = Color.Black.copy(alpha = .5f), spotColor = Color.Black.copy(alpha = .5f))
        .clip(shape)
        .then(dismissDrag)
        .then(if (onCanvas != null) Modifier.clickable(onClick = onCanvas).semantics { contentDescription = canvasLabel } else Modifier)
        .testTag("tablet-player-artwork")) {
        ArtworkCanvas(track, if (panoramicCanvasActive(canvas)) null else canvas, playing, Modifier.fillMaxSize(), depth = true,
            frame = com.ketvoice.app.data.MediaOverrides.playerFrame(track))
    }
}

/** Title, progress, transport and volume. [centred]: under a centred artwork (no pane beside). */
@Composable
private fun NowPlayingControls(state: PlayerUiState, track: Track, cover: String?, onTrackMenu: () -> Unit,
                               travels: Boolean, lateRise: Modifier, modifier: Modifier, centred: Boolean) {
    val reveal = LocalPlayerReveal.current
    Column(modifier) {
        PlayerTitle(track, false, cover, onTrackMenu, Modifier.fillMaxWidth(),
            textModifier = if (travels) Modifier.travelFromMini({ MiniPlayerAnchors.title }, reveal, anchorStart = true) else Modifier,
            actionsModifier = lateRise)
        Spacer(Modifier.height(10.dp))
        ScrubBar(state.durationMs, Modifier.fillMaxWidth().then(lateRise), onSeek = Player::seekTo)
        AudioStatsInfo(Modifier.fillMaxWidth().then(lateRise))
        PlayerTransport(state.playWhenReady, Modifier.fillMaxWidth().height(if (centred) 104.dp else 92.dp)
            .padding(horizontal = if (centred) 36.dp else 8.dp), Player::previous, Player::toggle, Player::next,
            previousModifier = lateRise,
            playModifier = if (travels) Modifier.travelFromMini({ MiniPlayerAnchors.play }, reveal) else Modifier,
            nextModifier = if (travels) Modifier.travelFromMini({ MiniPlayerAnchors.next }, reveal) else Modifier)
        PlayerVolume(Modifier.fillMaxWidth().then(lateRise), compact = true)
        when {
            state.buffering -> Text("Chargement…", color = Color.White.copy(alpha = .6f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            state.failed -> Text(state.quality, localize = false, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** The lyrics and/or the queue, as the plan says. */
@Composable
private fun SidePanes(plan: LargeScreen.PlayerPlan, state: PlayerUiState, track: Track, onSources: () -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (plan.lyrics) LyricsPane(track, expanded = false, onSource = onSources,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("tablet-player-lyrics-pane"),
            // A little larger than on a phone, unless the queue shares the width.
            fontScale = com.ketvoice.app.ui.quest.QuestLayout.lyricsScale(AppSettings.lyricsTextSize.value) * (if (plan.queue) 1f else 1.12f))
        AnimatedVisibility(plan.queue,
            modifier = if (plan.lyrics) Modifier.width(LargeScreen.QUEUE_PANEL_DP.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight(),
            enter = fadeIn(tween(Motion.duration(220))) + slideInHorizontally(tween(Motion.duration(280))) { if (Motion.reduced) 0 else it / 6 },
            exit = fadeOut(tween(Motion.duration(160))) + slideOutHorizontally(tween(Motion.duration(200))) { if (Motion.reduced) 0 else it / 6 }) {
            QueuePanel(state, Modifier.fillMaxSize())
        }
    }
}

/** The queue as a panel: its own frosted card, the modes on top, the list below. */
@Composable
private fun QueuePanel(state: PlayerUiState, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = .26f))
        .border(.6.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(28.dp))
        .padding(horizontal = 14.dp, vertical = 12.dp).testTag("tablet-player-queue-panel")) {
        QueuePane(state, Modifier.fillMaxSize(), showModes = true)
    }
}

@Composable
private fun LandscapeBody(plan: LargeScreen.PlayerPlan, book: Pair<Dp, Dp>?,
                          artwork: @Composable (Dp) -> Unit, controls: @Composable (Modifier, Boolean) -> Unit,
                          side: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panes = plan.lyrics || plan.queue
        if (!panes) {
            // Nothing beside it: the artwork takes the stage, the controls under it.
            val width = minOf(maxWidth, 600.dp)
            val art = LargeScreen.playerArtwork(width.value, maxHeight.value, reservedDp = 290f).dp
            Column(Modifier.width(width).fillMaxHeight().align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                artwork(art)
                Spacer(Modifier.height(22.dp))
                controls(Modifier.width(maxOf(art, 420.dp).coerceAtMost(width)), true)
            }
            return@BoxWithConstraints
        }
        // Held like a book, the controls keep to the left page: nothing important sits on the fold.
        val gap = book?.second?.let { it + 24.dp } ?: 36.dp
        val columnWidth = book?.first?.coerceAtMost(maxWidth * .5f)
            ?: (if (plan.queueBesideLyrics) maxWidth * .34f else maxWidth * .42f).coerceIn(340.dp, 540.dp)
        val art = LargeScreen.playerArtwork(columnWidth.value, maxHeight.value, reservedDp = 290f).dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(Modifier.width(columnWidth).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                artwork(art)
                Spacer(Modifier.height(20.dp))
                controls(Modifier.fillMaxWidth(), false)
            }
            side(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun PortraitBody(plan: LargeScreen.PlayerPlan, artwork: @Composable (Dp) -> Unit,
                         controls: @Composable (Modifier, Boolean) -> Unit, side: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (!(plan.lyrics || plan.queue)) {
            val width = minOf(maxWidth, 640.dp)
            val art = LargeScreen.playerArtwork(width.value, maxHeight.value, reservedDp = 320f, maxDp = 640f).dp
            Column(Modifier.width(width).fillMaxHeight().align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                artwork(art)
                Spacer(Modifier.height(28.dp))
                controls(Modifier.width(maxOf(art, 440.dp).coerceAtMost(width)), true)
            }
            return@BoxWithConstraints
        }
        // Standing: artwork and controls side by side on top, the lyrics (and the queue) below.
        val art = minOf(maxWidth * .42f, maxHeight * .34f, 460.dp)
        val rowHeight = maxOf(art, 300.dp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                artwork(art)
                Spacer(Modifier.width(28.dp))
                controls(Modifier.weight(1f), false)
            }
            Spacer(Modifier.height(24.dp))
            side(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Half-opened like a laptop: the artwork and the lyrics above the hinge, the controls below it. */
@Composable
private fun TabletopBody(upper: Dp, plan: LargeScreen.PlayerPlan, artwork: @Composable (Dp) -> Unit,
                         controls: @Composable (Modifier, Boolean) -> Unit, side: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = upper.coerceIn(160.dp, maxHeight - 160.dp)
        val art = minOf(top - 12.dp, maxWidth * .4f)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(top), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                artwork(art)
                if (plan.lyrics || plan.queue) side(Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(28.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                controls(Modifier.widthIn(max = 720.dp).fillMaxWidth(), true)
            }
        }
    }
}
