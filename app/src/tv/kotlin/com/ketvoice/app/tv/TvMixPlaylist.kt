package com.ketvoice.app.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.AnalysisDepth
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.automix.PlaylistMix
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * « Mixer cette playlist » (Ketvoice TV 1.1): Ketmix is switched on, the list's first titles are measured ahead (as
 * many as the analysis setting allows, each on a silent deck, never longer than a couple of minutes in all), the order
 * is changed gently for the tempo and key to flow ([PlaylistMix]; the first title stays first), then the list plays as
 * one mix. « Lire maintenant » plays with what is known so far; « Annuler » plays nothing.
 */
internal object TvPlaylistMix {
    sealed interface State {
        data object Idle : State
        /** [done] titles of [total] measured; [title] the one being measured. */
        data class Preparing(val done: Int, val total: Int, val title: String) : State
    }

    /** How a title's start is measured. False: nothing more can be measured now (no player yet, bit-perfect output). */
    fun interface Measure { suspend fun intro(track: Track): Boolean }

    /** The real one: Ketmix reads the title's start on a silent deck and files it, as for the DJ editor. */
    val Real = Measure { track ->
        when (Automix.loadForEditor(track, tail = false)) {
            Automix.EditorLoad.NoPlayer, Automix.EditorLoad.Exact -> false
            else -> true
        }
    }

    const val PER_TITLE_MS = 30_000L
    const val MAX_MS = 120_000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var job: Job? = null
    @Volatile private var hurry = false

    /** Prepares [tracks], then hands the new order to [play] on the main thread. */
    fun start(tracks: List<Track>, play: (List<Track>) -> Unit, measure: Measure = Real) {
        if (tracks.isEmpty()) return
        job?.cancel()
        hurry = false
        AppSettings.automix.value = true
        runCatching { com.ketvoice.app.data.SettingsStore.save() }
        job = scope.launch {
            val depth = AnalysisDepth.of(AppSettings.ketmixAnalysis.value)
            // On the main thread: the decks Ketmix reads with belong to it (each read waits without blocking it).
            val ordered = prepare(tracks, measure, depth, System::currentTimeMillis)
            _state.value = State.Idle
            Automix.mixList = ordered.map { it.id }.toSet()
            play(ordered)
        }
    }

    /** « Lire maintenant »: what is measured is enough. */
    fun playNow() { hurry = true }

    /** « Annuler »: nothing plays; what was measured stays for later mixes. */
    fun cancel() {
        job?.cancel(); job = null
        _state.value = State.Idle
    }

    /** The measured titles, then the new order. Public for the tests, which give their own [measure] and clock. */
    internal suspend fun prepare(tracks: List<Track>, measure: Measure, depth: AnalysisDepth, now: () -> Long): List<Track> {
        val total = PlaylistMix.budget(depth, tracks.size)
        val startedAt = now()
        for (i in 0 until total) {
            if (hurry || now() - startedAt > MAX_MS) break
            val track = tracks[i]
            _state.value = State.Preparing(i, total, track.title)
            if (AnalysisStore.intro(track.id, key(track), depth) != null) continue
            val more = withTimeoutOrNull(PER_TITLE_MS) { measure.intro(track) } ?: true
            if (!more) break
        }
        _state.value = State.Preparing(total, total, "")
        val intros = tracks.map { AnalysisStore.intro(it.id, key(it)) }
        val endings = tracks.map { AnalysisStore.outro(it.id, key(it)) }
        return PlaylistMix.apply(tracks, PlaylistMix.order(intros, endings, pinned = setOf(0)))
    }

    /** Titles' starts are filed under the length the list gives them, as Ketmix's own preparation files them. */
    private fun key(track: Track): Long = track.durationMs.takeIf { it > 0 } ?: 600_000L

    /** « Préparation du mix… 3/10 » */
    fun progressLine(state: State.Preparing): String =
        AppText.t("Préparation du mix…") + " ${minOf(state.done + 1, state.total)}/${state.total}"

    /** For host renders: a state shown without any work. */
    internal fun showForTest(state: State) { job?.cancel(); job = null; _state.value = state }
}

/** The collection page's button: « Mixer cette playlist » (« Mixer cet album » on an album). */
@Composable
fun TvMixButton(kind: String, tracks: List<Track>, modifier: Modifier = Modifier) {
    val actions = LocalTvActions.current
    if (tracks.size < 2) return
    TvButton(if (kind == "album") "Mixer cet album" else "Mixer cette playlist", KetIcons.Audio, {
        TvPlaylistMix.start(tracks, play = { ordered ->
            com.ketvoice.app.player.Player.playTrack(ordered.first(), ordered, 0, keepOrder = true)
            actions.nav.open(TvRoute.NowPlaying)
        })
    }, modifier, tag = "collection-mix")
}

/** Over everything while a list is prepared: the progress, « Lire maintenant » and « Annuler » (Back cancels too). */
@Composable
fun TvMixProgress() {
    val state by TvPlaylistMix.state.collectAsState()
    val preparing = state as? TvPlaylistMix.State.Preparing ?: return
    val first = remember { FocusRequester() }
    BackHandler { TvPlaylistMix.cancel() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .62f)).testTag("mix-progress"), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).clip(RoundedCornerShape(TvDesign.Radius.panel)).background(Color(0xFF1C1C22)).padding(28.dp).holdFocus(first)) {
            Text(TvPlaylistMix.progressLine(preparing), localize = false, style = TvDesign.Type.title, color = TvDesign.Ink.primary,
                modifier = Modifier.testTag("mix-progress-count"))
            Spacer(Modifier.height(8.dp))
            Text(preparing.title.ifBlank { AppText.t("Mise en ordre des titres") }, localize = false, style = TvDesign.Type.body,
                color = TvDesign.Ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            MixBar(if (preparing.total > 0) preparing.done.toFloat() / preparing.total else 1f)
            Spacer(Modifier.height(14.dp))
            Text("Ketmix écoute les titres pour les enchaîner en douceur : tempos et tonalités qui se suivent. Le premier titre reste le premier.",
                style = TvDesign.Type.caption, color = TvDesign.Ink.tertiary)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Lire maintenant", KetIcons.Play, TvPlaylistMix::playNow, Modifier.focusRequester(first), primary = true, tag = "mix-play-now")
                TvButton("Annuler", null, TvPlaylistMix::cancel, tag = "mix-cancel")
            }
        }
    }
}

@Composable
private fun MixBar(fraction: Float) {
    val accent = com.ketvoice.app.theme.LocalTheme.current.accent
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .12f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent))
    }
}
