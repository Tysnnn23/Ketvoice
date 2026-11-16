package com.ketvoice.app.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.glance.color.ColorProvider
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
import com.ketvoice.app.player.Player
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
 * the mix of the moment and small controls). One updater feeds them all, and only works while at
 * least one of them is placed.
 */
internal data class WidgetsState(
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val cover: Bitmap? = null,
    /** The cover blurred and darkened: the frosted ground of the large player. */
    val ground: Bitmap? = null,
    val progress: Float = 0f,
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

    private data class Key(val id: String?, val title: String, val artist: String, val playing: Boolean, val cover: String?, val duration: Long)

    private val widgets = listOf(GlassPlayerWidget::class.java, WeekWidget::class.java, MixWidget::class.java, MiniControlsWidget::class.java)

    private suspend fun placed(app: Context): Boolean = widgets.any { type ->
        runCatching { GlanceAppWidgetManager(app).getGlanceIds(type).isNotEmpty() }.getOrDefault(false)
    }

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
                    if (!placed(app)) return@collectLatest
                    val previous = state.value
                    val same = previous.title == key.title && previous.artist == key.artist
                    state.value = previous.copy(title = key.title, artist = key.artist, playing = key.playing,
                        cover = previous.cover.takeIf { same }, ground = previous.ground.takeIf { same },
                        progress = progress(key.duration))
                    refreshAll(app)
                    val bitmap = key.cover?.let { loadCover(app, it) }
                    if (bitmap != null || key.cover == null) {
                        state.value = state.value.copy(cover = bitmap, ground = bitmap?.let { runCatching { frostedGround(it) }.getOrNull() })
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

    private suspend fun loadCover(context: Context, url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(url).size(320).allowHardware(false).build()
        context.imageLoader.execute(request).drawable?.toBitmap()
    }.getOrNull()
}

private val glassSurface = ColorProvider(day = Color(0xE6FFFFFF), night = Color(0xE61C1C1F))
private val glassText = ColorProvider(day = Color(0xFF141414), night = Color(0xFFFFFFFF))
private val glassSecondary = ColorProvider(day = Color(0xFF5B5B63), night = Color(0xFFB7B7C2))
private val glassFaint = ColorProvider(day = Color(0x1F000000), night = Color(0x29FFFFFF))
private val accent = ColorProvider(day = Color(0xFFFF2D55), night = Color(0xFFFF375F))
private val white = Solid(Color.White)
private val whiteSoft = Solid(Color(0xCCFFFFFF))
private val whiteFaint = Solid(Color(0x40FFFFFF))
private val darkGround = Solid(Color(0xFF1C1C1F))

@Composable
private fun widgetState(): WidgetsState = KetvoiceWidgetsUpdater.state.collectAsState().value

@Composable
private fun TransportButton(icon: Int, label: String, command: String, state: WidgetsState, sizeDp: Int, tint: androidx.glance.unit.ColorProvider) {
    val action: Action = if (state.hasTrack) actionRunCallback<WidgetTransportCallback>(actionParametersOf(COMMAND to command))
    else actionStartActivity<MainActivity>()
    Image(ImageProvider(icon), AppText.t(label), GlanceModifier.size(sizeDp.dp).cornerRadius((sizeDp / 2).dp)
        .clickable(action).padding(((sizeDp - 24) / 2).dp), colorFilter = ColorFilter.tint(tint))
}

@Composable
private fun CoverImage(bitmap: Bitmap?, sizeDp: Int, radiusDp: Int) {
    val modifier = GlanceModifier.size(sizeDp.dp).cornerRadius(radiusDp.dp)
    if (bitmap != null) Image(ImageProvider(bitmap), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(glassFaint)) {}
}

// ---------------------------------------------------------------- Large glass player

/** 4×2: the cover blurred behind everything, the cover itself, the title, a progress bar and the transport. */
class GlassPlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        KetvoiceWidgetsUpdater.start(context.applicationContext)
        provideContent { GlassPlayerContent(widgetState()) }
    }
}
class GlassPlayerWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = GlassPlayerWidget() }

@Composable
private fun GlassPlayerContent(state: WidgetsState) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(darkGround).clickable(actionStartActivity<MainActivity>())) {
        state.ground?.let { Image(ImageProvider(it), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(state.cover, 84, 18)
                Spacer(GlanceModifier.width(14.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(state.title.ifBlank { "Ketvoice" }, maxLines = 2,
                        style = TextStyle(color = white, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                    Text(state.artist.ifBlank { AppText.t("Touchez pour écouter") }, maxLines = 1,
                        style = TextStyle(color = whiteSoft, fontSize = 13.sp))
                }
            }
            LinearProgressIndicator(state.progress, GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
                color = white, backgroundColor = whiteFaint)
            Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                TransportButton(R.drawable.ic_widget_previous, "Précédent", "previous", state, 44, white)
                Spacer(GlanceModifier.width(18.dp))
                TransportButton(if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                    if (state.playing) "Pause" else "Lire", "toggle", state, 52, white)
                Spacer(GlanceModifier.width(18.dp))
                TransportButton(R.drawable.ic_widget_next, "Suivant", "next", state, 44, white)
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
private fun WeekContent(state: WidgetsState) {
    val open = actionStartActivity<MainActivity>(actionParametersOf(WidgetIntents.openKey to "replay"))
    Column(GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(glassSurface).clickable(open).padding(14.dp)) {
        Text(AppText.t("Votre semaine"), style = TextStyle(color = glassText, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        if (state.weekPlays < 1) {
            Spacer(GlanceModifier.height(8.dp))
            Text(AppText.t("Écoutez quelques titres pour voir votre semaine"), maxLines = 3,
                style = TextStyle(color = glassSecondary, fontSize = 12.sp))
            return@Column
        }
        Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text("${state.weekMinutes} min", style = TextStyle(color = glassText, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.width(8.dp))
            Text("${state.weekPlays} ${AppText.t("écoutes")}", style = TextStyle(color = glassSecondary, fontSize = 12.sp))
        }
        Row(GlanceModifier.fillMaxWidth().defaultWeight().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            state.weekBars.forEachIndexed { index, share ->
                Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 2.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(GlanceModifier.fillMaxWidth().height((4 + share * 40).dp).cornerRadius(4.dp)
                        .background(if (index == state.weekBars.lastIndex) accent else glassFaint)) {}
                }
            }
        }
        state.weekArtist?.let {
            Text("${AppText.t("Artiste de la semaine")} · $it", maxLines = 1, style = TextStyle(color = glassSecondary, fontSize = 12.sp))
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
private fun MixContent(state: WidgetsState) {
    val id = state.mixId
    val open = if (id == null) actionStartActivity<MainActivity>()
        else actionStartActivity<MainActivity>(actionParametersOf(WidgetIntents.mixKey to id, WidgetIntents.mixTitleKey to state.mixTitle))
    Box(GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(darkGround).clickable(open)) {
        state.mixCover?.let { Image(ImageProvider(it), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Column(GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.Bottom) {
            Text(AppText.t(state.mixLabel.ifBlank { "Pour vous" }).uppercase(), maxLines = 1,
                style = TextStyle(color = whiteSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            Text(state.mixTitle.ifBlank { AppText.t("Écoutez quelques titres pour voir votre semaine") }, maxLines = 2,
                style = TextStyle(color = white, fontSize = 17.sp, fontWeight = FontWeight.Bold))
            if (id != null) Row(GlanceModifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_widget_play), null, GlanceModifier.size(30.dp).cornerRadius(15.dp)
                    .background(white).padding(6.dp), colorFilter = ColorFilter.tint(Solid(Color.Black)))
                Spacer(GlanceModifier.width(8.dp))
                Text(AppText.t("Écouter"), style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

// ---------------------------------------------------------------- Mini controls

/** 2×1: a round cover, play or pause, next. */
class MiniControlsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        KetvoiceWidgetsUpdater.start(context.applicationContext)
        provideContent { MiniControlsContent(widgetState()) }
    }
}
class MiniControlsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MiniControlsWidget() }

@Composable
private fun MiniControlsContent(state: WidgetsState) {
    val height = LocalSize.current.height.value.coerceAtLeast(40f)
    val coverDp = (height - 12f).toInt().coerceIn(32, 56)
    Row(GlanceModifier.fillMaxSize().cornerRadius(999.dp).background(glassSurface).clickable(actionStartActivity<MainActivity>())
        .padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverImage(state.cover, coverDp, coverDp / 2)
        Spacer(GlanceModifier.defaultWeight())
        TransportButton(if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            if (state.playing) "Pause" else "Lire", "toggle", state, 40, glassText)
        TransportButton(R.drawable.ic_widget_next, "Suivant", "next", state, 40, glassText)
    }
}

