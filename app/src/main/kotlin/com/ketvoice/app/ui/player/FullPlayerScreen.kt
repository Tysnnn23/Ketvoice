package com.ketvoice.app.ui.player

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Share
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.Cover
import com.kyant.backdrop.backdrops.layerBackdrop
import com.ketvoice.app.ui.motion.Motion
import androidx.compose.animation.animateColorAsState
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.KetTheme
import androidx.compose.material3.MaterialTheme
import com.ketvoice.app.ui.common.TrackTitle

enum class PlayerPage { ARTWORK, LYRICS, QUEUE }

@Composable
fun FullPlayerScreen(
    modifier: Modifier = Modifier,
    initialPage: PlayerPage = PlayerPage.ARTWORK,
    onDismiss: () -> Unit = { AppUi.showPlayer.value = false; AppUi.showLyrics.value = false }
) {
    val state by Player.state.collectAsState()
    val track = state.current
    if (track == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var page by remember { mutableStateOf(if (AppUi.showLyrics.value) PlayerPage.LYRICS else initialPage) }
    var expanded by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }
    var interaction by remember { mutableLongStateOf(0L) }
    var touching by remember { mutableStateOf(false) }
    val covers by ArtworkRepo.covers.collectAsState()
    val canvas by CanvasRepo.paths.collectAsState()
    val lyrics by LyricsRepo.state.collectAsState()
    val cover = covers["t:${track.id}"] ?: track.coverUrl
    val sampled = rememberCoverAmbience(cover)
    val ambientColor by animateColorAsState(sampled.color, tween(Motion.duration(420)), label = "album-background")
    val ambience = sampled.copy(color = ambientColor)
    val preferredAccent = LocalTheme.current.accent
    val albumAccent by animateColorAsState(
        if (AppSettings.albumColors.value) sampled.accent ?: preferredAccent else preferredAccent,
        tween(Motion.duration(420)), label = "album-accent")
    val context = LocalContext.current
    val backdrop = rememberGlassBackdrop(Color.Black)
    val adaptivePlayer = LocalAdaptiveLayout.current.adaptivePlayer
    fun navigate(next: PlayerPage) {
        page = next
        expanded = false
        interaction = android.os.SystemClock.uptimeMillis()
        AppUi.showLyrics.value = next == PlayerPage.LYRICS
    }
    fun close() {
        sheet = ""
        AppUi.showTrackMenu.value = false
        AppUi.showAudioOutput.value = false
        AppUi.showLyricsSource.value = false
        onDismiss()
    }
    BackHandler {
        when {
            sheet.isNotEmpty() -> sheet = ""
            expanded -> expanded = false
            page != PlayerPage.ARTWORK -> navigate(PlayerPage.ARTWORK)
            else -> close()
        }
    }
    LaunchedEffect(track.id) { LyricsRepo.load(track) }
    LaunchedEffect(page, sheet, interaction, touching, expanded, lyrics.lines.isNotEmpty(), AppUi.showAccount.value, adaptivePlayer) {
        if (!com.ketvoice.app.BuildConfig.IS_QUEST && !adaptivePlayer && page == PlayerPage.LYRICS && !expanded && !touching && sheet.isBlank() &&
            lyrics.lines.isNotEmpty() && !AppUi.showAccount.value) {
            kotlinx.coroutines.delay(2000)
            expanded = true
        }
    }

    CompositionLocalProvider(LocalTheme provides KetTheme.dark().copy(accent = albumAccent, secondary = Color.White.copy(alpha = .78f))) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = albumAccent, onPrimary = Color.Black)) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) {
            QuestLandscapePlayer(state, track, cover, canvas[track.id], ambience, page,
                onPage = ::navigate, onClose = ::close, onTrackMenu = { sheet = "track" },
                onSources = { sheet = "sources" }, onOutput = { sheet = "audio" })
            if (sheet.isNotBlank()) PlayerSheetScrim({ sheet = "" }) {
                when (sheet) {
                    "sources" -> LyricsSourceSheet(track, onClose = { sheet = "" })
                    "track" -> TrackMenuSheet(track) { sheet = "" }
                    else -> AudioOutputSheet { sheet = "" }
                }
            }
            return@BoxWithConstraints
        }
        if (adaptivePlayer) {

            var dragX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = dragX }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragX = 0f
                    var total = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        total += change.positionChange().x
                        dragX = total
                        if (!change.pressed) break
                        change.consume()
                    }
                    if (kotlin.math.abs(total) > 380f) close() else dragX = 0f
                }
            }) {
                TabletPlayer(state, track, cover, canvas[track.id], ambience, page,
                    onPage = ::navigate, onClose = ::close, onTrackMenu = { sheet = "track" },
                    onSources = { sheet = "sources" }, onOutput = { sheet = "audio" })
            }
            if (sheet.isNotBlank()) PlayerSheetScrim({ sheet = "" }) {
                when (sheet) {
                    "sources" -> LyricsSourceSheet(track, onClose = { sheet = "" })
                    "track" -> TrackMenuSheet(track) { sheet = "" }
                    else -> AudioOutputSheet { sheet = "" }
                }
            }
            return@BoxWithConstraints
        }
        val margin = maxWidth * .08f
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val safeHeight = maxHeight - bottomInset
        val wide = maxWidth > maxHeight
        val controlsWidth = if (wide) maxWidth * .48f else maxWidth
        val sceneWidth = if (wide && !expanded) maxWidth - controlsWidth else maxWidth
        val lowerHeight = if (wide) (safeHeight - topInset - 6.dp) else (safeHeight * .405f).coerceAtLeast(300.dp)
        val contentBottom = safeHeight - lowerHeight
        val headerTop = if (wide) topInset + 20.dp else (safeHeight * .093f).coerceAtLeast(topInset + 35.dp)
        val headerHeight = 58.dp
        val sceneTop = headerTop + headerHeight + 7.dp
        CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        touching = event.changes.any { it.pressed }
                        interaction = android.os.SystemClock.uptimeMillis()
                    }
                }
            }) {
                PlayerSceneBackground(track, canvas[track.id], state.isPlaying, cover, ambience)

                AnimatedContent(page, transitionSpec = {
                    (fadeIn(tween(Motion.duration())) + slideInVertically(tween(Motion.duration())) { it / 35 })
                        .togetherWith(fadeOut(tween(Motion.duration())))
                }, label = "player-page", modifier = Modifier.fillMaxSize()) { shownPage ->
                Box(Modifier.fillMaxSize()) {
                if (shownPage == PlayerPage.ARTWORK) {
                    Box(Modifier.width(sceneWidth).height(if (wide) safeHeight else safeHeight * .55f)
                        .onGloballyPositioned { AppUi.playerCoverBounds.value = it.boundsInRoot() }
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()

                            drawRect(Brush.verticalGradient(
                                0f to Color.White, .36f to Color.White, .52f to Color.White.copy(alpha = .98f),
                                .66f to Color.White.copy(alpha = .82f), .79f to Color.White.copy(alpha = .48f),
                                .90f to Color.White.copy(alpha = .16f), 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn)
                        }.swipeToMini { close() }) {
                        ArtworkCanvas(track, if (panoramicCanvasActive(canvas[track.id])) null else canvas[track.id], state.isPlaying, Modifier.fillMaxSize(), depth = true)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = .16f), .52f to Color.Transparent,
                            .83f to ambience.color.copy(alpha = .45f), 1f to ambience.color.copy(alpha = .85f))))
                    }
                    Text("Lecture depuis ${track.artist}", color = Color(0xFFCBCDD6), fontSize = 10.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(sceneWidth).padding(top = topInset + 32.dp))
                    PlayerTitle(track, false, cover, sheetAction = { sheet = "track" }, onArtist = {
                        track.artistId?.takeIf { it.startsWith("UC") }?.let {
                            AppUi.openArtist(it, track.artist)
                        }
                    },
                        modifier = Modifier.width(sceneWidth).offset(y = if (wide) safeHeight - 66.dp
                            else minOf(safeHeight * .514f, contentBottom - 61.dp)).padding(horizontal = margin))
                } else {
                    PlayerTitle(track, true, cover, sheetAction = {
                        if (expanded) {
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${track.title} — ${track.artist}") }
                            context.startActivity(Intent.createChooser(send, "Partager le morceau"))
                        } else sheet = "track"
                    }, share = expanded, onCover = {
                        navigate(PlayerPage.ARTWORK)
                    }, onArtist = {
                        track.artistId?.takeIf { it.startsWith("UC") }?.let {
                            AppUi.openArtist(it, track.artist)
                        }
                    },
                        modifier = Modifier.width(sceneWidth).offset(y = headerTop).padding(horizontal = if (wide) 18.dp else margin)
                            .height(headerHeight).swipeToMini { close() })
                }
                }
                }

                PlayerHandle(Modifier.offset(x = (sceneWidth - 120.dp) / 2).padding(top = topInset), onClose = { close() })

                AnimatedVisibility(
                    visible = page != PlayerPage.ARTWORK,
                    enter = fadeIn(tween(Motion.duration())) +
                        slideInVertically(tween(Motion.duration())) { it / 10 } +
                        scaleIn(initialScale = if(Motion.reduced) 1f else .98f, animationSpec = tween(Motion.duration())),
                    exit = fadeOut(tween(Motion.duration())) +
                        slideOutVertically(tween(Motion.duration())) { it / 10 } +
                        scaleOut(targetScale = if(Motion.reduced) 1f else .98f, animationSpec = tween(Motion.duration()))
                ) {
                    val sceneEnd = if (expanded || wide) safeHeight - 12.dp else contentBottom
                    Box(Modifier.offset(y = sceneTop).height((sceneEnd - sceneTop).coerceAtLeast(80.dp)).width(sceneWidth)) {
                        if (page == PlayerPage.QUEUE) QueuePane(state, Modifier.fillMaxSize().padding(horizontal = if (wide) 18.dp else margin))
                         else LyricsPane(track, expanded, onSource = { sheet = "sources" },
                            modifier = Modifier.fillMaxSize().padding(horizontal = if (wide) 18.dp else margin * .8f),
                            fontScale = com.ketvoice.app.ui.quest.QuestLayout.lyricsScale(AppSettings.lyricsTextSize.value))
                    }
                }

                if (!expanded) {
                    Column(Modifier.align(if (wide) Alignment.BottomEnd else Alignment.BottomCenter).padding(bottom = bottomInset)
                        .height(lowerHeight).width(controlsWidth).padding(horizontal = if (wide) 20.dp else margin)) {
                        Box(Modifier.height(34.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (page == PlayerPage.LYRICS) {
                                Text("${lyrics.source.ifBlank { "Recherche des paroles…" }} · ${lyrics.syncDescription()}", color = Color(0xFFB5B5C0), fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { sheet = "sources" })
                            } else LyricsPreviewLine { navigate(PlayerPage.LYRICS) }
                        }
                        ScrubBar(state.durationMs, Modifier.fillMaxWidth(), onSeek = Player::seekTo)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            PlayerTransport(state.playWhenReady, Modifier.fillMaxSize(), Player::previous, Player::toggle, Player::next)
                            if (state.buffering) Text("Chargement…", color = Color.White.copy(alpha = .42f), fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.BottomCenter))
                        }
                        PlayerVolume(Modifier.fillMaxWidth(), compact = true)
                        Spacer(Modifier.height(8.dp))
                        if (page == PlayerPage.QUEUE) QueueActionsBar(state.shuffle, state.repeatOne, state.autoplay,
                            { Player.setShuffle(!state.shuffle) }, { Player.setRepeatOne(!state.repeatOne) },
                            { Player.setAutoplay(!state.autoplay) }, { navigate(PlayerPage.ARTWORK) })
                        else PlayerBottomBar(page == PlayerPage.LYRICS,
                            onLyrics = { navigate(if (page == PlayerPage.LYRICS) PlayerPage.ARTWORK else PlayerPage.LYRICS) },
                            onOutput = { sheet = "audio" },
                            onProfile = { AppUi.showAccount.value = true },
                            onQueue = { navigate(PlayerPage.QUEUE) })
                        Text(if (com.ketvoice.app.BuildConfig.IS_QUEST) "Ce casque" else "Ce téléphone", color = Color(0xFF777984), fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 18.dp, bottom = 19.dp))
                    }
                }
            }
            AnimatedContent(sheet, transitionSpec = { fadeIn(tween(Motion.duration())).togetherWith(fadeOut(tween(Motion.duration()))) }, label = "player-sheet") { selected ->
                if (selected.isNotBlank()) PlayerSheetScrim({ sheet = "" }) {
                    when (selected) {
                        "sources" -> LyricsSourceSheet(track, onClose = { sheet = "" })
                        "track" -> TrackMenuSheet(track) { sheet = "" }
                        else -> AudioOutputSheet { sheet = "" }
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
private fun PlayerHandle(modifier: Modifier, onClose: () -> Unit) {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    var distance by remember { mutableFloatStateOf(0f) }
    Box(modifier.width(120.dp).height(30.dp).semantics { contentDescription = com.ketvoice.app.data.AppText.t("Fermer le lecteur") }
        .clickable(onClick = onClose).pointerInput(Unit) {
            detectVerticalDragGestures(onVerticalDrag = { change, dy -> change.consume(); distance += dy },
                onDragStart = { distance = 0f }, onDragEnd = { if (distance > threshold) onClose(); distance = 0f },
                onDragCancel = { distance = 0f })
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp, 5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF989AA6)))
    }
}

@Composable
internal fun PlayerTitle(track: Track, compact: Boolean, cover: String?, sheetAction: () -> Unit,
                         modifier: Modifier = Modifier, share: Boolean = false, onCover: () -> Unit = {},
                         onArtist: () -> Unit = { track.artistId?.let { AppUi.openArtist(it, track.artist) } }) {
    val favorite = track.id in AppSettings.favoriteIds.value
    var favoriteTap by remember(track.id) { mutableIntStateOf(0) }
    val pulse = remember(track.id) { androidx.compose.animation.core.Animatable(1f) }
    val starColor by animateColorAsState(if (favorite) com.ketvoice.app.theme.LocalTheme.current.accent else Color(0xFFCED2E3),
        tween(Motion.duration()), label = "favorite-color")
    LaunchedEffect(favoriteTap, Motion.reduced) {
        if (favoriteTap > 0 && !Motion.reduced) {
            pulse.animateTo(1.16f, tween(Motion.PRESS))
            pulse.animateTo(1f, tween(Motion.FADE))
        } else pulse.snapTo(1f)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (compact) { Cover(track.title, cover, size = 56.dp, radius = 8.dp,
            modifier = Modifier.semantics { contentDescription = com.ketvoice.app.data.AppText.t("Revenir à la pochette") }.clickable(onClick = onCover)); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.explicit) {
                    Text("E", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(RoundedCornerShape(2.dp)).background(Color.White).padding(horizontal = 2.dp))
                    Spacer(Modifier.width(5.dp))
                }
                TrackTitle(track.title, color = Color.White, fontSize = if (compact) 20.sp else 22.sp,
                    selected = true, maxLines = if (compact) 1 else 2)
            }

            Text(track.artist, localize = false, color = Color.White.copy(alpha = .82f), fontSize = 17.sp, letterSpacing = (-.5).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onArtist)
                    .semantics { contentDescription = "Ouvrir la page de ${track.artist}" }
            )
        }
        Spacer(Modifier.width(8.dp))
        SmallCircleButton({
            favoriteTap++
            AppSettings.favoriteIds.value = if (favorite) AppSettings.favoriteIds.value - track.id else AppSettings.favoriteIds.value + track.id
            SettingsStore.save()
        }, active = favorite, size = 36.dp) {
            Icon(if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                if (favorite) "Retirer des favoris" else "Ajouter aux favoris", tint = starColor,
                modifier = Modifier.size(23.dp).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value })
        }
        Spacer(Modifier.width(8.dp))
        SmallCircleButton(sheetAction, size = 36.dp) {
            Icon(if (share) Icons.Filled.Share else Icons.Filled.MoreHoriz, if (share) "Partager" else "Options",
                tint = Color(0xFFCED2E3), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Modifier.swipeToMini(onDismiss: () -> Unit): Modifier {
    val dismiss by rememberUpdatedState(onDismiss)
    return pointerInput(Unit) {
    var distance = 0f
    detectVerticalDragGestures(onDragStart = { distance = 0f },
        onVerticalDrag = { change, dy -> change.consume(); distance += dy },
        onDragEnd = { if (distance > 64.dp.toPx()) dismiss(); distance = 0f },
        onDragCancel = { distance = 0f })
}
}
