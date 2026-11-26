package com.ketvoice.app.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.automix.KetmixLearning
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.player.AutomixGlyph
import com.ketvoice.app.ui.player.KetmixMixingBadge
import com.ketvoice.app.ui.player.LyricsPane
import com.ketvoice.app.ui.player.rememberKetmixAsk
import com.ketvoice.app.ui.player.rememberMixProgress
import kotlinx.coroutines.delay

/** What the right half of Now Playing shows. */
enum class TvPanel { Lyrics, Queue }

/**
 * Now Playing, full screen: the title's Canvas (or its cover's colours) from edge to edge, the cover, the title and
 * the transport on the left, the synced lyrics (Cascade, as on the phone) or the queue on the right. After a few
 * seconds without a key the controls step back and only the music, the picture and the lyrics remain; any key brings
 * them back (that first key only wakes them).
 */
@Composable
fun TvNowPlayingPage() {
    val state by Player.state.collectAsState()
    val track = state.current
    if (track == null) {
        Box(Modifier.fillMaxSize().background(TvDesign.Ink.page), contentAlignment = Alignment.Center) {
            TvEmpty("Rien en lecture", "Choisissez un titre dans l'Accueil, la Bibliothèque ou la Recherche.")
        }
        return
    }
    val cover = rememberCover(track)
    val canvas = rememberCanvasPath(track)
    var panel by remember { mutableStateOf(TvPanel.Lyrics) }
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var awake by remember { mutableStateOf(true) }
    LaunchedEffect(track.id) { LyricsRepo.load(track) }
    LaunchedEffect(lastKey, panel) {
        awake = true
        if (panel == TvPanel.Queue) return@LaunchedEffect
        delay(IDLE_MS)
        awake = false
    }
    val chrome by animateFloatAsState(if (awake) 1f else 0f, tween(if (awake) 180 else 900), label = "tv-chrome")
    Box(Modifier.fillMaxSize().testTag("tv-now-playing").onPreviewKeyEvent { event ->
        val sleeping = !awake
        lastKey = System.currentTimeMillis()
        // Asleep, the first press only brings the controls back.
        sleeping && event.type == KeyEventType.KeyDown
    }) {
        NowPlayingBackground(track, cover, canvas, state.isPlaying)
        Column(Modifier.fillMaxSize().padding(horizontal = TvDesign.Safe.horizontal, vertical = TvDesign.Safe.vertical)) {
            Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 14.dp)) {
                Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    TvCover(cover, 216.dp, RoundedCornerShape(TvDesign.Radius.panel), placeholder = KetIcons.Music,
                        modifier = Modifier.graphicsLayer { alpha = .45f + .55f * chrome })
                    Spacer(Modifier.height(18.dp))
                    Text(track.title, localize = false, style = TvDesign.Type.title, color = TvDesign.Ink.primary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("np-title"))
                    Text(track.artist, localize = false, style = TvDesign.Type.row, color = TvDesign.Ink.secondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    KetmixLine()
                }
                Spacer(Modifier.width(40.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (panel) {
                        TvPanel.Lyrics -> TvLyricsPanel(track)
                        TvPanel.Queue -> TvQueuePanel(state)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().graphicsLayer { alpha = chrome }) {
                TvProgress(state.durationMs)
                Spacer(Modifier.height(10.dp))
                TvTransport(state, track, panel, onPanel = { panel = it })
            }
        }
    }
}

/** Controls step back after this long without a key (never while the queue is shown). */
const val IDLE_MS = 8_000L

@Composable
private fun rememberCover(track: Track): String? {
    val covers by com.ketvoice.app.data.ArtworkRepo.covers.collectAsState()
    return remember(track.id, covers) { MediaOverrides.cover(track, covers["t:${track.id}"] ?: track.coverUrl) }
}

/** The title's Canvas when the listener keeps « Pochettes animées » on and one exists (fetched as on the phone). */
@Composable
private fun rememberCanvasPath(track: Track): String? {
    val paths by CanvasRepo.paths.collectAsState()
    val excluded by CanvasRepo.excluded.collectAsState()
    LaunchedEffect(track.id) { if (AppSettings.animatedArtwork.value) runCatching { CanvasRepo.resolve(track) } }
    return paths[track.id]?.takeIf { AppSettings.animatedArtwork.value && track.id !in excluded && !MediaOverrides.canvasHiddenInPlayer(track) }
}

/**
 * The Canvas fills the screen edge to edge (zoomed, never letterboxed) under two veils that keep the text readable;
 * without one, the cover's colours as a soft field ([TvBackdrop]).
 */
@Composable
private fun NowPlayingBackground(track: Track, cover: String?, canvas: String?, playing: Boolean) {
    var failed by remember(canvas) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        TvBackdrop(cover, Modifier.fillMaxSize(), veil = .5f)
        if (canvas != null && !failed) {
            com.ketvoice.app.ui.player.CanvasVideo(canvas, playing, cover = null, modifier = Modifier.fillMaxSize().testTag("np-canvas"),
                onFailure = { failed = true }) // zoomed to fill (the phone's Canvas resize mode)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = .78f), .45f to Color.Black.copy(alpha = .35f), 1f to Color.Black.copy(alpha = .72f))))
        }
    }
}

/** Ketmix at work (its two-ring sign), and for a few seconds after a transition the 👍/👎 of « Ketmix apprend ». */
@Composable
private fun KetmixLine() {
    val progress = rememberMixProgress()
    val ask by rememberKetmixAsk()
    Box(Modifier.height(36.dp)) {
        val t = ask
        if (t != null) Row(verticalAlignment = Alignment.CenterVertically) {
            AutomixGlyph(Color.White, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (t.rating == null) "Cette transition vous a plu ?" else "Merci, Ketmix s'en souviendra", style = TvDesign.Type.caption,
                color = TvDesign.Ink.primary)
            Spacer(Modifier.width(10.dp))
            TvIconButton(Ket27Glyphs.ThumbUp, "Elle m'a plu", { KetmixLearning.rate(true) }, size = 36.dp, active = t.rating == true,
                tag = "np-rate-up")
            Spacer(Modifier.width(6.dp))
            TvIconButton(Ket27Glyphs.ThumbDown, "Elle ne m'a pas plu", { KetmixLearning.rate(false) }, size = 36.dp, active = t.rating == false,
                tag = "np-rate-down")
        } else KetmixMixingBadge(progress.value, Color.White, Modifier.align(Alignment.CenterStart).graphicsLayer { scaleX = 1.4f; scaleY = 1.4f
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f) })
    }
}

/** The progress line and the times. The position is read while drawing: the page never recomposes with it. */
@Composable
private fun TvProgress(durationMs: Long) {
    val position = Player.positionMs.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(6.dp).testTag("np-progress")) {
            val fraction = if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Color.White.copy(alpha = .22f), cornerRadius = r)
            drawRoundRect(Color.White, size = Size(size.width * fraction, size.height), cornerRadius = r, topLeft = Offset.Zero)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            TimeText({ TvTime.format(position.value) })
            Spacer(Modifier.weight(1f))
            TimeText({ "-" + TvTime.format((durationMs - position.value).coerceAtLeast(0)) })
        }
    }
}

/** A time that changes every second recomposes only itself. */
@Composable
private fun TimeText(text: () -> String) {
    Text(text(), localize = false, style = TvDesign.Type.caption, color = TvDesign.Ink.secondary)
}

/**
 * One bar under the progress: shuffle, previous, play/pause, next, repeat on the left; like, Ketmix and lyrics/queue on
 * the right. Play/pause has the focus first.
 */
@Composable
private fun TvTransport(state: PlayerUiState, track: Track, panel: TvPanel, onPanel: (TvPanel) -> Unit) {
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(16); runCatching { play.requestFocus() } }
    val favorite = track.id in AppSettings.favoriteIds.value
    val ketmix = AppSettings.automix.value
    val accent = com.ketvoice.app.theme.LocalTheme.current.accent
    Row(Modifier.fillMaxWidth().focusProperties { onEnter = { runCatching { play.requestFocus() } } }.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        TvIconButton(KetIcons.Shuffle, "Aléatoire", { Player.setShuffle(!state.shuffle) }, size = 48.dp, active = state.shuffle,
            accent = accent, tag = "np-shuffle")
        TvIconButton(Icons.Filled.SkipPrevious, "Précédent", Player::previous, tag = "np-previous")
        TvIconButton(if (state.playWhenReady) KetIcons.Pause else KetIcons.Play, if (state.playWhenReady) "Pause" else "Lecture",
            Player::toggle, Modifier.focusRequester(play), size = 68.dp, tag = "np-play")
        TvIconButton(Icons.Filled.SkipNext, "Suivant", Player::next, tag = "np-next")
        TvIconButton(if (state.repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Répéter", Player::cycleRepeat, size = 48.dp,
            active = state.repeatOne || state.repeatAll, accent = accent, tag = "np-repeat")
        Spacer(Modifier.weight(1f))
        TvIconButton(if (favorite) Ket27Glyphs.HeartFilled else Ket27Glyphs.Heart, if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
            { com.ketvoice.app.ui.player.toggleFavorite(track) }, size = 48.dp, active = favorite, accent = accent, tag = "np-like")
        KetmixToggle(ketmix)
        TvButton(if (panel == TvPanel.Lyrics) "File d'attente" else "Paroles", if (panel == TvPanel.Lyrics) KetIcons.Playlist else KetIcons.Lyrics,
            { onPanel(if (panel == TvPanel.Lyrics) TvPanel.Queue else TvPanel.Lyrics) }, tag = "np-panel")
    }
}

/** Ketmix on or off, as the phone's switch under the transport (the same setting, synced with the account). */
@Composable
private fun KetmixToggle(on: Boolean) {
    TvButton(if (on) "Ketmix activé" else "Ketmix", null, {
        AppSettings.automix.value = !on
        SettingsStore.save()
    }, selected = on, tag = "np-ketmix")
}

/**
 * The phone's Cascade lyrics, larger for the distance. The lines are not focus stops on TV (the remote stays on the
 * transport), they follow the song by themselves.
 */
@Composable
private fun TvLyricsPanel(track: Track) {
    val lyrics by LyricsRepo.state.collectAsState()
    val fade = tvTier() != TvTier.Light
    Box(Modifier.fillMaxSize().focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()
        // The lines leaving at the top fade out instead of being cut (one offscreen layer; light TVs keep the plain edge).
        .then(if (fade) Modifier.topFade() else Modifier)
        .testTag("np-lyrics")) {
        when {
            !AppSettings.syncedLyrics.value -> TvEmpty("Paroles masquées", "Activez les paroles dans Réglages.")
            lyrics.lines.isEmpty() && !lyrics.loading -> TvEmpty("Pas de paroles pour ce titre", "Ketvoice les cherche chez chaque fournisseur à chaque écoute.")
            else -> LyricsPane(track, expanded = true, onSource = {}, modifier = Modifier.fillMaxSize(), fontScale = 1.08f * com.ketvoice.app.ui.quest.QuestLayout.lyricsScale(AppSettings.lyricsTextSize.value))
        }
    }
}

/** What comes next: the remote walks it; the select key jumps to a title. */
@Composable
private fun TvQueuePanel(state: PlayerUiState) {
    val library = rememberTvLibrary()
    val upcoming = state.queue.withIndex().drop((state.index + 1).coerceAtLeast(0)).take(80)
    Column(Modifier.fillMaxSize().testTag("np-queue")) {
        Text("À suivre", style = TvDesign.Type.row, color = TvDesign.Ink.primary, modifier = Modifier.padding(bottom = 12.dp))
        if (upcoming.isEmpty()) Text("La file d'attente est vide", style = TvDesign.Type.body, color = TvDesign.Ink.secondary)
        TvPivot(pivot = .2f) {
            LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(upcoming, key = { _, entry -> state.queueEntryIds.getOrNull(entry.index) ?: "${entry.value.id}:${entry.index}" }) { i, entry ->
                    TvTrackRow(i, entry.value, library.cover(entry.value), false, Modifier,
                        onClick = { Player.jumpTo(entry.index) }, onMenu = {})
                }
            }
        }
    }
}

/** Fades what is drawn at the top edge (the lyrics' lines scrolling away) through an offscreen layer. */
private fun Modifier.topFade(): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, .16f to Color.Black, 1f to Color.Black),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }
