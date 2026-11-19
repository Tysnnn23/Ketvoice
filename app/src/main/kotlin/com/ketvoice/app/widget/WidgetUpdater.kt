package com.ketvoice.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import coil.imageLoader
import coil.request.ImageRequest
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.ListeningWeek
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.player.LiveLyrics
import com.ketvoice.app.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime

/**
 * Feeds every widget (6.4). Battery first:
 * - nothing runs for a widget type that is not placed, and nothing at all while none is;
 * - redraws are coalesced per widget type ([WidgetCoalescer]): the sung line at most every 750 ms, a title and its
 *   cover 250 ms apart, the listening history every 2 s;
 * - each widget reads its own copy of the state ([view]), written only when that widget is due, so a line of
 *   lyrics never redraws "Votre semaine";
 * - the progress bar moves every 30 s, only while a title plays and the screen is on; with the screen off the
 *   sung line waits too, and both are brought up to date when the screen comes on.
 */
internal object KetvoiceWidgetsUpdater {
    val state = MutableStateFlow(WidgetsState())
    private val views = WidgetKind.entries.associateWith { MutableStateFlow(WidgetsState()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val coalescer = WidgetCoalescer()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lyricStale = false
    @Volatile private var durationMs = 0L
    @Volatile private var appContext: Context? = null

    /** What the widget of [kind] shows: the state as it was when that widget was last due. */
    fun view(kind: WidgetKind): StateFlow<WidgetsState> = views.getValue(kind)

    /** A widget starts or is redrawn by the launcher: it shows the latest state at once. */
    fun fresh(kind: WidgetKind): StateFlow<WidgetsState> = views.getValue(kind).also { it.value = state.value }

    fun widget(kind: WidgetKind): GlanceAppWidget = when (kind) {
        WidgetKind.NowPlaying -> NowPlayingWidget()
        WidgetKind.Player -> GlassPlayerWidget()
        WidgetKind.Mini -> MiniControlsWidget()
        WidgetKind.Controls -> ControlsWidget()
        WidgetKind.Lyrics -> LyricsWidget()
        WidgetKind.Ketmix -> KetmixWidget()
        WidgetKind.Week -> WeekWidget()
        WidgetKind.Mix -> MixWidget()
        WidgetKind.Replay -> ReplayWidget()
    }

    private fun type(kind: WidgetKind): Class<out GlanceAppWidget> = widget(kind).javaClass

    internal suspend fun placed(app: Context, kind: WidgetKind): Boolean =
        runCatching { GlanceAppWidgetManager(app).getGlanceIds(type(kind)).isNotEmpty() }.getOrDefault(false)

    private suspend fun anyPlaced(app: Context, kinds: Collection<WidgetKind> = WidgetKind.entries): Boolean =
        kinds.any { placed(app, it) }

    fun request(reason: WidgetReason) {
        synchronized(coalescer) { coalescer.request(reason) }
        signal.trySend(Unit)
    }

    /** Ketmix was switched from a widget, a tile or the app: the Ketmix widget and the tile follow. */
    fun ketmixChanged() {
        state.update { it.copy(ketmix = it.ketmix.copy(on = AppSettings.automix.value)) }
        request(WidgetReason.Ketmix)
    }

    /** The sung line is worked out while a widget showing it is placed (the lyrics clock costs nothing otherwise). */
    suspend fun refreshLyricDemand(app: Context) {
        LiveLyrics.widgetWants = placed(app, WidgetKind.Player) || placed(app, WidgetKind.Lyrics)
    }

    /** A widget showing the line was removed: whether another one still wants it is asked off the receiver's thread. */
    fun lyricDemandSoon(context: Context) {
        val app = context.applicationContext
        scope.launch { refreshLyricDemand(app) }
    }

    @Synchronized
    fun start(context: Context) {
        if (job != null) return
        val app = context.applicationContext
        appContext = app
        runCatching { ListeningHistory.init(app) }
        runCatching { ArtistPrefs.init(app) }
        ScreenState.watch(app)
        job = scope.launch {
            launch { sendLoop(app) }
            launch { followTrack(app) }
            launch { followProgress(app) }
            launch { followLyrics() }
            launch { followKetmix(app) }
            launch { followHistory(app) }
            launch { followScreen() }
        }
    }

    private suspend fun sendLoop(app: Context) {
        while (scope.isActive) {
            val wait = synchronized(coalescer) { coalescer.waitAt(SystemClock.elapsedRealtime()) }
            when {
                wait == null -> signal.receive()
                wait > 0 -> withTimeoutOrNull(wait) { signal.receive() }
                else -> synchronized(coalescer) { coalescer.take(SystemClock.elapsedRealtime()) }.forEach { send(app, it) }
            }
        }
    }

    private suspend fun send(app: Context, kind: WidgetKind) {
        if (!placed(app, kind)) return
        views.getValue(kind).value = state.value
        // A widget whose session is open redraws from its copy above; a closed one is started again.
        runCatching { widget(kind).updateAll(app) }
    }

    private data class TrackKey(val id: String?, val title: String, val artist: String, val playing: Boolean, val cover: String?, val duration: Long)

    private val trackKinds = WidgetReason.Track.kinds + WidgetKind.Week

    private suspend fun followTrack(app: Context) {
        combine(Player.state, ArtworkRepo.covers) { player, covers ->
            val track = player.current
            TrackKey(track?.id, track?.title.orEmpty(), track?.artist.orEmpty(), player.playWhenReady && track != null,
                track?.let { com.ketvoice.app.data.MediaOverrides.cover(it, covers["t:${it.id}"] ?: it.coverUrl) }, player.durationMs)
        }.distinctUntilChanged().collectLatest { key ->
            refreshLyricDemand(app)
            if (!anyPlaced(app, trackKinds)) return@collectLatest
            durationMs = key.duration
            val previous = state.value
            val same = previous.title == key.title && previous.artist == key.artist
            state.update {
                it.copy(title = key.title, artist = key.artist, playing = key.playing, progress = progress(),
                    cover = it.cover.takeIf { same }, ground = it.ground.takeIf { same }, plainGround = it.plainGround.takeIf { same },
                    tint = if (same) it.tint else WidgetLook.DEFAULT, lyric = it.lyric.takeIf { same }, nextLyric = it.nextLyric.takeIf { same })
            }
            request(WidgetReason.Track)
            if (same && key.cover == lastTrackCover && (previous.cover != null || key.cover == null)) return@collectLatest
            val bitmap = key.cover?.let { loadCover(app, it) }
            if (bitmap == null && key.cover != null) return@collectLatest
            lastTrackCover = key.cover
            val tint = WidgetLook.tint(bitmap)
            state.update {
                it.copy(cover = bitmap, tint = tint, ground = runCatching { WidgetLook.ground(bitmap, tint) }.getOrNull(),
                    plainGround = runCatching { WidgetLook.ground(null, tint) }.getOrNull())
            }
            request(WidgetReason.Track)
            synchronized(coalescer) { coalescer.request(listOf(WidgetKind.Week)) }
        }
    }

    private suspend fun followProgress(app: Context) {
        combine(state.map { it.playing }.distinctUntilChanged(), ScreenState.on) { playing, on -> playing && on }
            .distinctUntilChanged().collectLatest { active ->
                while (active) {
                    delay(WidgetRefresh.PROGRESS_MS)
                    if (!anyPlaced(app, WidgetReason.Progress.kinds)) continue
                    state.update { it.copy(progress = progress()) }
                    request(WidgetReason.Progress)
                }
            }
    }

    private suspend fun followLyrics() {
        LiveLyrics.state.map { live ->
            live.line?.takeIf { it.trackId == Player.state.value.current?.id }?.let { it.current to it.next }
        }.distinctUntilChanged().collect { line ->
            if (!LiveLyrics.widgetWants && line != null) return@collect
            state.update { it.copy(lyric = line?.first, nextLyric = line?.second) }
            if (ScreenState.on.value) request(WidgetReason.Lyric) else lyricStale = true
        }
    }

    private suspend fun followScreen() {
        ScreenState.on.collect { on ->
            if (!on) return@collect
            if (lyricStale) { lyricStale = false; request(WidgetReason.Lyric) }
            if (state.value.playing) { state.update { it.copy(progress = progress()) }; request(WidgetReason.Progress) }
        }
    }

    private data class KetmixKey(val nextId: String?, val nextTitle: String, val nextArtist: String, val nextCover: String?,
                                 val on: Boolean, val mixing: Boolean)

    private suspend fun followKetmix(app: Context) {
        val on = androidx.compose.runtime.snapshotFlow { AppSettings.automix.value }
        combine(Player.state, on, Automix.ui.map { it.phase == Automix.Phase.Mixing }.distinctUntilChanged()) { _, switched, mixing ->
            val next = runCatching { Player.automixNext()?.track }.getOrNull()
            KetmixKey(next?.id, next?.title.orEmpty(), next?.artist.orEmpty(), next?.let { ArtworkRepo.coverForTrack(it) }, switched, mixing)
        }.distinctUntilChanged().collectLatest { key ->
            if (!placed(app, WidgetKind.Ketmix)) return@collectLatest
            val previous = state.value.ketmix
            val keepCover = previous.nextTitle == key.nextTitle && previous.nextArtist == key.nextArtist
            state.update { it.copy(ketmix = KetmixWidgetState(key.on, key.mixing, key.nextTitle, key.nextArtist, previous.nextCover.takeIf { keepCover })) }
            request(WidgetReason.Ketmix)
            if (keepCover || key.nextCover == null) return@collectLatest
            val cover = loadCover(app, key.nextCover, 128) ?: return@collectLatest
            state.update { it.copy(ketmix = it.ketmix.copy(nextCover = cover)) }
            request(WidgetReason.Ketmix)
        }
    }

    private suspend fun followHistory(app: Context) {
        combine(ListeningHistory.days, ListeningHistory.knownTracks, ListeningHistory.tracks, ListeningHistory.playCounts,
            ListeningHistory.months) { days, known, recent, counts, months -> HistoryInputs(days, known, recent, counts, months) }
            .distinctUntilChanged().collectLatest { inputs ->
                if (!anyPlaced(app, WidgetReason.History.kinds)) return@collectLatest
                week(inputs)
                mix(app, inputs)
                replay(app, inputs)
                request(WidgetReason.History)
            }
    }

    private data class HistoryInputs(
        val days: Map<String, com.ketvoice.app.data.DayStat>,
        val known: List<com.ketvoice.app.data.Track>,
        val recent: List<com.ketvoice.app.data.Track>,
        val counts: Map<String, Int>,
        val months: Map<String, com.ketvoice.app.data.MonthStat>,
    )

    private fun week(inputs: HistoryInputs) {
        val today = LocalDate.now()
        val week = ListeningWeek.summary(inputs.days, today, inputs.known)
        state.update {
            it.copy(weekMinutes = week.listenedMs / 60_000, weekPlays = week.plays, weekArtist = week.topArtist,
                weekBars = weekBars(ListeningWeek.daily(inputs.days, today).toList()))
        }
    }

    private suspend fun mix(app: Context, inputs: HistoryInputs) {
        val cards = runCatching {
            PersonalMixes.propose(inputs.known.filter(ArtistPrefs::allows), inputs.recent.filter(ArtistPrefs::allows), inputs.counts,
                AppSettings.favoriteIds.value)
        }.getOrDefault(emptyList())
        val mix: Pair<MixCard, String>? = runCatching { com.ketvoice.app.ui.home.mixForHour(LocalTime.now().hour, cards) }.getOrNull()
        val coverUrl = mix?.first?.covers?.firstOrNull { !it.isNullOrBlank() }
        val previous = state.value
        val cover = if (coverUrl != null && coverUrl == lastMixCover && previous.mixCover != null) previous.mixCover
            else coverUrl?.let { loadCover(app, it) }
        lastMixCover = coverUrl
        val ground = if (cover === previous.mixCover && previous.mixGround != null) previous.mixGround
            else cover?.let { runCatching { WidgetLook.ground(it, WidgetLook.tint(it)) }.getOrNull() }
        state.update {
            it.copy(mixId = mix?.first?.id, mixLabel = mix?.second.orEmpty(),
                mixTitle = mix?.first?.let { m -> AppText.t(com.ketvoice.app.ui.home.mixTitle(m)) }.orEmpty(), mixCover = cover, mixGround = ground)
        }
    }

    @Volatile private var lastTrackCover: String? = null
    @Volatile private var lastMixCover: String? = null
    @Volatile private var lastReplayCover: String? = null

    private suspend fun replay(app: Context, inputs: HistoryInputs) {
        val key = com.ketvoice.app.data.ListeningMonths.key(LocalDate.now())
        val recap = runCatching { Wrapped.month(key, inputs.months, inputs.known, coverOf = { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }) }.getOrNull()
        val top = recap?.topTrack
        val coverUrl = top?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }
        val previous = state.value.replay
        val cover = if (coverUrl != null && coverUrl == lastReplayCover && previous.cover != null) previous.cover
            else coverUrl?.let { loadCover(app, it) }
        lastReplayCover = coverUrl
        val ground = if (cover === previous.cover && previous.ground != null) previous.ground
            else cover?.let { runCatching { WidgetLook.ground(it, WidgetLook.tint(it)) }.getOrNull() }
        state.update {
            it.copy(replay = ReplayWidgetState(key, monthName(key), recap?.minutes ?: 0, recap?.plays ?: 0, recap?.topArtist?.name,
                top?.title, top?.let { t -> com.ketvoice.app.data.MediaOverrides.mainArtist(t.artist) }, cover, ground))
        }
    }

    /** « Novembre » in the interface language. */
    internal fun monthName(key: String, locale: java.util.Locale = com.ketvoice.app.ui.sub.WrappedPeriod.locale()): String = runCatching {
        java.time.YearMonth.parse(key).format(java.time.format.DateTimeFormatter.ofPattern("LLLL", locale)).replaceFirstChar { it.titlecase(locale) }
    }.getOrDefault(key)

    private fun progress(): Float {
        val duration = durationMs
        return if (duration > 0) (Player.positionMs.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
    }

    /** Covers are sent to the launcher with every redraw: small and without alpha (2 bytes a pixel). */
    internal suspend fun loadCover(context: Context, url: String, size: Int = 256): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(url).size(size).allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565).build()
        context.imageLoader.execute(request).drawable?.toBitmap(config = Bitmap.Config.RGB_565)
    }.getOrNull()
}
