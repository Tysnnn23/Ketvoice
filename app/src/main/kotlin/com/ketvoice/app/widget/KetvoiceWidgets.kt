package com.ketvoice.app.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.unit.ColorProvider as Solid
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import coil.imageLoader
import coil.request.ImageRequest
import com.ketvoice.app.MainActivity
import com.ketvoice.app.R
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.ListeningWeek
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.player.LiveLyrics
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PostThrottle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * The four widgets added in 5.9.0 (owner, 2026-11-16: a large Liquid Glass player, "Votre semaine",
 * the mix of the moment and small controls), redone in the Ket 27 look in 6.1 ([WidgetGlass]: glass tinted
 * by the cover) with the sung line in the large player. One updater feeds them all, and only works while at
 * least one of them is placed.
 */
internal data class WidgetsState(
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val cover: Bitmap? = null,
    /** The glass ground of the player widgets, from the cover ([WidgetGlass.ground]). */
    val ground: Bitmap? = null,
    /** The same glass without the cover's picture, for "Votre semaine". */
    val plainGround: Bitmap? = null,
    val tint: WidgetTint = WidgetGlass.DEFAULT,
    val progress: Float = 0f,
    /** The sung line (6.1), in the large player while synced lyrics play. */
    val lyric: String? = null,
    val weekMinutes: Long = 0,
    val weekPlays: Int = 0,
    val weekArtist: String? = null,
    /** Listening of the last seven days, oldest first, each 0..1 of the longest day. */
    val weekBars: List<Float> = emptyList(),
    val mixId: String? = null,
    val mixLabel: String = "",
    val mixTitle: String = "",
    val mixCover: Bitmap? = null,
) {
    val hasTrack: Boolean get() = title.isNotBlank()
}

/** Extras MainActivity reads when a widget opens it. */
internal object WidgetIntents {
    const val MIX = "ketvoice.widget.mix"
    const val MIX_TITLE = "ketvoice.widget.mixTitle"
    const val OPEN = "ketvoice.widget.open"
    val mixKey = ActionParameters.Key<String>(MIX)
    val mixTitleKey = ActionParameters.Key<String>(MIX_TITLE)
    val openKey = ActionParameters.Key<String>(OPEN)
}

/** A blurred, dimmed copy of [source]: shrunk to a few pixels then smoothed back up, which RemoteViews can show. */
internal fun frostedGround(source: Bitmap, size: Int = 240): Bitmap {
    val tiny = source.scale(12, 12, filter = true)
    val soft = tiny.scale(48, 48, filter = true).scale(size, size, filter = true)
    val out = soft.copy(Bitmap.Config.ARGB_8888, true)
    android.graphics.Canvas(out).drawColor(android.graphics.Color.argb(120, 0, 0, 0))
    return out
}

/** Seven bars from the daily listening: each as a share of the longest day, a sliver for days with a little. */
internal fun weekBars(daily: List<Long>): List<Float> {
    val longest = daily.maxOrNull() ?: 0L
    if (longest <= 0L) return daily.map { 0f }
    return daily.map { if (it <= 0L) 0f else (it.toFloat() / longest).coerceIn(.08f, 1f) }
}

internal object KetvoiceWidgetsUpdater {
    val state = MutableStateFlow(WidgetsState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    /** A widget redraw sends its pictures to the launcher again: the sung line refreshes it at most this often. */
    const val LYRIC_MIN_MS = 3_000L

    private data class Key(val id: String?, val title: String, val artist: String, val playing: Boolean, val cover: String?, val duration: Long)

    private val widgets = listOf(GlassPlayerWidget::class.java, WeekWidget::class.java, MixWidget::class.java, MiniControlsWidget::class.java)

    private suspend fun placed(app: Context): Boolean = widgets.any { type -> placed(app, type) }

    private suspend fun placed(app: Context, type: Class<out GlanceAppWidget>): Boolean =
        runCatching { GlanceAppWidgetManager(app).getGlanceIds(type).isNotEmpty() }.getOrDefault(false)

    private suspend fun refreshAll(app: Context) {
        runCatching { GlassPlayerWidget().updateAll(app) }
        runCatching { WeekWidget().updateAll(app) }
        runCatching { MixWidget().updateAll(app) }
        runCatching { MiniControlsWidget().updateAll(app) }
    }

    @Synchronized
    fun start(context: Context) {
        if (job != null) return
        val app = context.applicationContext
        runCatching { ListeningHistory.init(app) }
        runCatching { ArtistPrefs.init(app) }
        job = scope.launch {
            launch {
                combine(Player.state, ArtworkRepo.covers) { player, covers ->
                    val track = player.current
                    Key(track?.id, track?.title.orEmpty(), track?.artist.orEmpty(), player.playWhenReady && track != null,
                        track?.let { com.ketvoice.app.data.MediaOverrides.cover(it, covers["t:${it.id}"] ?: it.coverUrl) },
                        player.durationMs)
                }.distinctUntilChanged().collectLatest { key ->
                    // The large player shows the sung line: the lyrics clock runs for it while it is placed.
                    LiveLyrics.widgetWants = placed(app, GlassPlayerWidget::class.java)
                    if (!placed(app)) return@collectLatest
                    val previous = state.value
                    val same = previous.title == key.title && previous.artist == key.artist
                    state.value = previous.copy(title = key.title, artist = key.artist, playing = key.playing,
                        cover = previous.cover.takeIf { same }, ground = previous.ground.takeIf { same },
                        plainGround = previous.plainGround.takeIf { same }, tint = if (same) previous.tint else WidgetGlass.DEFAULT,
                        lyric = previous.lyric.takeIf { same }, progress = progress(key.duration))
                    refreshAll(app)
                    val bitmap = key.cover?.let { loadCover(app, it) }
                    if (bitmap != null || key.cover == null) {
                        val tint = WidgetGlass.tint(bitmap)
                        state.value = state.value.copy(cover = bitmap, tint = tint,
                            ground = runCatching { WidgetGlass.ground(bitmap, tint) }.getOrNull(),
                            plainGround = runCatching { WidgetGlass.ground(null, tint) }.getOrNull())
                        refreshAll(app)
                    }
                    // While a title plays, the large player's bar moves every half minute: often enough to read, rare enough for the battery.
                    while (key.playing) {
                        delay(30_000)
                        if (!placed(app)) break
                        state.value = state.value.copy(progress = progress(key.duration))
                        runCatching { GlassPlayerWidget().updateAll(app) }
                    }
                }
            }
            launch {
                // The sung line (6.1): only when it changes, spaced out, and only for a placed large player.
                val throttle = PostThrottle(LYRIC_MIN_MS)
                LiveLyrics.state.collectLatest { live ->
                    val line = live.line?.takeIf { it.trackId == Player.state.value.current?.id }?.current
                    if (line == state.value.lyric || !LiveLyrics.widgetWants) return@collectLatest
                    val wait = throttle.waitAt(android.os.SystemClock.elapsedRealtime())
                    if (wait > 0) delay(wait)
                    state.value = state.value.copy(lyric = line)
                    runCatching { GlassPlayerWidget().updateAll(app) }
                    throttle.posted(android.os.SystemClock.elapsedRealtime())
                }
            }
            launch {
                combine(ListeningHistory.days, ListeningHistory.knownTracks, ListeningHistory.tracks, ListeningHistory.playCounts) { days, known, recent, counts ->
                    Triple(days, known, recent to counts)
                }.distinctUntilChanged().collectLatest { (days, known, rest) ->
                    if (!placed(app)) return@collectLatest
                    val today = LocalDate.now()
                    val week = ListeningWeek.summary(days, today, known)
                    val (recent, counts) = rest
                    val cards = runCatching {
                        PersonalMixes.propose(known.filter(ArtistPrefs::allows), recent.filter(ArtistPrefs::allows), counts,
                            com.ketvoice.app.data.AppSettings.favoriteIds.value)
                    }.getOrDefault(emptyList())
                    val mix: Pair<MixCard, String>? = runCatching { com.ketvoice.app.ui.home.mixForHour(LocalTime.now().hour, cards) }.getOrNull()
                    val mixCover = mix?.first?.covers?.firstOrNull { !it.isNullOrBlank() }?.let { loadCover(app, it) }
                    state.value = state.value.copy(
                        weekMinutes = week.listenedMs / 60_000, weekPlays = week.plays, weekArtist = week.topArtist,
                        weekBars = weekBars(ListeningWeek.daily(days, today).toList()),
                        mixId = mix?.first?.id, mixLabel = mix?.second.orEmpty(),
                        mixTitle = mix?.first?.let { AppText.t(com.ketvoice.app.ui.home.mixTitle(it)) }.orEmpty(), mixCover = mixCover)
                    runCatching { WeekWidget().updateAll(app) }
                    runCatching { MixWidget().updateAll(app) }
                }
            }
        }
    }

    private fun progress(duration: Long): Float =
        if (duration > 0) (Player.positionMs.value.toFloat() / duration).coerceIn(0f, 1f) else 0f

    /** Covers are sent to the launcher with every redraw: small and without alpha (2 bytes a pixel). */
    internal suspend fun loadCover(context: Context, url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(url).size(224).allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565).build()
        context.imageLoader.execute(request).drawable?.toBitmap(config = Bitmap.Config.RGB_565)
    }.getOrNull()
}

@Composable
private fun widgetState(): WidgetsState = KetvoiceWidgetsUpdater.state.collectAsState().value

@Composable
internal fun TransportButton(icon: Int, label: String, command: String, hasTrack: Boolean, sizeDp: Int,
                             tint: androidx.glance.unit.ColorProvider = WidgetGlass.text, filled: Boolean = false) {
    // Without a loaded queue a transport tap opens the app instead of doing nothing.
    val action: Action = if (hasTrack) actionRunCallback<WidgetTransportCallback>(actionParametersOf(COMMAND to command))
    else actionStartActivity<MainActivity>()
    var modifier = GlanceModifier.size(sizeDp.dp).cornerRadius((sizeDp / 2).dp)
    if (filled) modifier = modifier.background(WidgetGlass.fill)
    Image(ImageProvider(icon), AppText.t(label), modifier.clickable(action).padding(((sizeDp - 24) / 2).dp),
        colorFilter = ColorFilter.tint(tint))
}

@Composable
internal fun CoverImage(bitmap: Bitmap?, sizeDp: Int, radiusDp: Int) {
    val modifier = GlanceModifier.size(sizeDp.dp).cornerRadius(radiusDp.dp)
    if (bitmap != null) Image(ImageProvider(bitmap), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(WidgetGlass.fill), contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_notification_lyrics), null, GlanceModifier.size((sizeDp / 3).dp),
            colorFilter = ColorFilter.tint(WidgetGlass.textFaint))
    }
}

// ---------------------------------------------------------------- Large glass player

/** 4×2: glass tinted by the cover, the cover, the title, the sung line, a progress bar and the transport. */
class GlassPlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        KetvoiceWidgetsUpdater.start(context.applicationContext)
        provideContent { GlassPlayerContent(widgetState()) }
    }
}
class GlassPlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlassPlayerWidget()
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // The last large player was removed: the lyrics clock no longer runs for it.
        LiveLyrics.widgetWants = false
    }
}

@Composable
internal fun GlassPlayerContent(state: WidgetsState) {
    val lyric = state.lyric?.takeIf { state.hasTrack }
    // The line opens the lyrics; elsewhere the card opens the app.
    val openLyrics = actionStartActivity<MainActivity>(actionParametersOf(WidgetIntents.openKey to "lyrics"))
    GlassCard(state.ground, 28.dp, actionStartActivity<MainActivity>()) {
        Column(GlanceModifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.Top) {
                CoverImage(state.cover, 64, 14)
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(state.title.ifBlank { "Ketvoice" }, maxLines = 1,
                        style = TextStyle(color = WidgetGlass.text, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    Text(state.artist.ifBlank { AppText.t("Touchez pour écouter") }, maxLines = 1,
                        style = TextStyle(color = WidgetGlass.textSoft, fontSize = 13.sp))
                    if (lyric != null) {
                        Spacer(GlanceModifier.height(6.dp))
                        Text(lyric, maxLines = 2, modifier = GlanceModifier.clickable(openLyrics),
                            style = TextStyle(color = WidgetGlass.text, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
            Spacer(GlanceModifier.height(8.dp))
            LinearProgressIndicator(state.progress, GlanceModifier.fillMaxWidth().height(3.dp).cornerRadius(2.dp),
                color = WidgetGlass.text, backgroundColor = WidgetGlass.track)
            Spacer(GlanceModifier.height(4.dp))
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                TransportButton(R.drawable.ic_widget_previous, "Précédent", "previous", state.hasTrack, 40)
                Spacer(GlanceModifier.width(20.dp))
                TransportButton(if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                    if (state.playing) "Pause" else "Lire", "toggle", state.hasTrack, 44, filled = true)
                Spacer(GlanceModifier.width(20.dp))
                TransportButton(R.drawable.ic_widget_next, "Suivant", "next", state.hasTrack, 40)
            }
        }
    }
}

// ---------------------------------------------------------------- Your week

/** "Votre semaine" on the home screen: minutes, plays, the artist of the week and the seven days. */
class WeekWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        KetvoiceWidgetsUpdater.start(context.applicationContext)
        provideContent { WeekContent(widgetState()) }
    }
}
class WeekWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WeekWidget() }

@Composable
internal fun WeekContent(state: WidgetsState) {
    val open = actionStartActivity<MainActivity>(actionParametersOf(WidgetIntents.openKey to "replay"))
    GlassCard(state.plainGround, 28.dp, open) {
        Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
            Text(AppText.t("Votre semaine").uppercase(), style = TextStyle(color = WidgetGlass.textSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            if (state.weekPlays < 1) {
                Spacer(GlanceModifier.height(8.dp))
                Text(AppText.t("Écoutez quelques titres pour voir votre semaine"), maxLines = 3,
                    style = TextStyle(color = WidgetGlass.text, fontSize = 13.sp))
                return@Column
            }
            Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text("${state.weekMinutes} min", style = TextStyle(color = WidgetGlass.text, fontSize = 24.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(8.dp))
                Text("${state.weekPlays} ${AppText.t("écoutes")}", style = TextStyle(color = WidgetGlass.textSoft, fontSize = 12.sp))
            }
            Row(GlanceModifier.fillMaxWidth().defaultWeight().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                state.weekBars.forEachIndexed { index, share ->
                    Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 2.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(GlanceModifier.fillMaxWidth().height((4 + share * 40).dp).cornerRadius(4.dp)
                            .background(if (index == state.weekBars.lastIndex) Solid(Color(state.tint.glow)) else WidgetGlass.track)) {}
                    }
                }
            }
            state.weekArtist?.let {
                Text("${AppText.t("Artiste de la semaine")} · $it", maxLines = 1, style = TextStyle(color = WidgetGlass.textSoft, fontSize = 12.sp))
            }
        }
    }
}

// ---------------------------------------------------------------- Mix of the moment

/** The mix of the hour, with its cover: a tap opens Ketvoice and plays it. */
class MixWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        KetvoiceWidgetsUpdater.start(context.applicationContext)
        provideContent { MixContent(widgetState()) }
    }
}
class MixWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MixWidget() }

@Composable
internal fun MixContent(state: WidgetsState) {
    val id = state.mixId
    val open = if (id == null) actionStartActivity<MainActivity>()
        else actionStartActivity<MainActivity>(actionParametersOf(WidgetIntents.mixKey to id, WidgetIntents.mixTitleKey to state.mixTitle))
    GlassCard(state.plainGround, 28.dp, open) {
        state.mixCover?.let { Image(ImageProvider(it), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // A veil under the words: the title stays readable on a light cover.
        Image(ImageProvider(WidgetGlass.scrim), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.Bottom) {
            Text(AppText.t(state.mixLabel.ifBlank { "Pour vous" }).uppercase(), maxLines = 1,
                style = TextStyle(color = WidgetGlass.textSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            Text(state.mixTitle.ifBlank { AppText.t("Écoutez quelques titres pour voir votre semaine") }, maxLines = 2,
                style = TextStyle(color = WidgetGlass.text, fontSize = 17.sp, fontWeight = FontWeight.Bold))
            if (id != null) Row(GlanceModifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.height(30.dp).cornerRadius(15.dp).background(WidgetGlass.fill).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(ImageProvider(R.drawable.ic_widget_play), null, GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(WidgetGlass.text))
                        Spacer(GlanceModifier.width(6.dp))
                        Text(AppText.t("Écouter"), style = TextStyle(color = WidgetGlass.text, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Mini controls

/** 2×1: a round cover, play or pause, next, on a glass pill. */
class MiniControlsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        KetvoiceWidgetsUpdater.start(context.applicationContext)
        provideContent { MiniControlsContent(widgetState()) }
    }
}
class MiniControlsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MiniControlsWidget() }

@Composable
internal fun MiniControlsContent(state: WidgetsState) {
    val height = LocalSize.current.height.value.coerceAtLeast(40f)
    val coverDp = (height - 14f).toInt().coerceIn(32, 56)
    GlassCard(state.ground, 999.dp, actionStartActivity<MainActivity>()) {
        Row(GlanceModifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(state.cover, coverDp, coverDp / 2)
            Spacer(GlanceModifier.defaultWeight())
            TransportButton(if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                if (state.playing) "Pause" else "Lire", "toggle", state.hasTrack, 40, filled = true)
            Spacer(GlanceModifier.width(4.dp))
            TransportButton(R.drawable.ic_widget_next, "Suivant", "next", state.hasTrack, 40)
        }
    }
}
