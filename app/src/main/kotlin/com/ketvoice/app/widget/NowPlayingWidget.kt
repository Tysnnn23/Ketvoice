package com.ketvoice.app.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
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
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the home-screen widget shows. */
internal data class WidgetNowPlaying(
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val cover: Bitmap? = null,
    /** The Ket 27 glass ground tinted by the cover (6.1, [WidgetGlass]). */
    val ground: Bitmap? = null
) {
    val hasTrack: Boolean get() = title.isNotBlank()
}

/** The three responsive layouts: cover + play, one row with transport, and a taller card. */
internal enum class WidgetLayout { Compact, Row, Card }

internal val WIDGET_COMPACT = DpSize(110.dp, 50.dp)
internal val WIDGET_ROW = DpSize(250.dp, 50.dp)
internal val WIDGET_CARD = DpSize(250.dp, 120.dp)

internal fun widgetLayoutFor(widthDp: Float, heightDp: Float): WidgetLayout = when {
    widthDp >= WIDGET_CARD.width.value && heightDp >= WIDGET_CARD.height.value -> WidgetLayout.Card
    widthDp >= WIDGET_ROW.width.value -> WidgetLayout.Row
    else -> WidgetLayout.Compact
}

/**
 * Home-screen widget in the glass language of the app (Ket 27 since 6.1): a glass card tinted by the cover,
 * the cover, the title and the transport. RemoteViews cannot refract what is behind them, so the glass is
 * drawn once per cover ([WidgetGlass]), not the optical material.
 */
class NowPlayingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WIDGET_COMPACT, WIDGET_ROW, WIDGET_CARD))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        NowPlayingWidgetUpdater.start(context.applicationContext)
        provideContent {
            val state by NowPlayingWidgetUpdater.state.collectAsState()
            WidgetContent(state)
        }
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

@Composable
internal fun WidgetContent(state: WidgetNowPlaying) {
    val size = LocalSize.current
    val layout = widgetLayoutFor(size.width.value, size.height.value)
    val open = actionStartActivity<MainActivity>()
    GlassCard(state.ground, if (layout == WidgetLayout.Card) 28.dp else 999.dp, open) {
      Box(GlanceModifier.fillMaxSize().padding(horizontal = if (layout == WidgetLayout.Card) 12.dp else 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        when (layout) {
            WidgetLayout.Compact -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Cover(state, 40)
                Spacer(GlanceModifier.defaultWeight())
                Transport(R.drawable.ic_widget_play.takeUnless { state.playing } ?: R.drawable.ic_widget_pause,
                    if (state.playing) "Pause" else "Lecture", "toggle", state, 40, filled = true)
            }
            WidgetLayout.Row -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Cover(state, 44)
                Spacer(GlanceModifier.width(10.dp))
                Labels(state, GlanceModifier.defaultWeight())
                TransportRow(state, 40)
            }
            WidgetLayout.Card -> Column(GlanceModifier.fillMaxSize()) {
                Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    Cover(state, 64)
                    Spacer(GlanceModifier.width(12.dp))
                    Labels(state, GlanceModifier.defaultWeight())
                }
                Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    TransportRow(state, 44)
                }
            }
        }
      }
    }
}

@Composable
private fun Cover(state: WidgetNowPlaying, sizeDp: Int) {
    CoverImage(state.cover, sizeDp, sizeDp / 4)
}

@Composable
private fun Labels(state: WidgetNowPlaying, modifier: GlanceModifier) {
    Column(modifier) {
        Text(state.title.ifBlank { "Ketvoice" }, maxLines = 1,
            style = TextStyle(color = WidgetGlass.text, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        if (state.artist.isNotBlank()) Text(state.artist, maxLines = 1, style = TextStyle(color = WidgetGlass.textSoft, fontSize = 12.sp))
    }
}

@Composable
private fun TransportRow(state: WidgetNowPlaying, sizeDp: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Transport(R.drawable.ic_widget_previous, "Précédent", "previous", state, sizeDp)
        Transport(if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            if (state.playing) "Pause" else "Lecture", "toggle", state, sizeDp, filled = true)
        Transport(R.drawable.ic_widget_next, "Suivant", "next", state, sizeDp)
    }
}

@Composable
private fun Transport(icon: Int, label: String, command: String, state: WidgetNowPlaying, sizeDp: Int, filled: Boolean = false) =
    TransportButton(icon, label, command, state.hasTrack, sizeDp, filled = filled)

internal val COMMAND = ActionParameters.Key<String>("command")

class WidgetTransportCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) {
            runCatching { Player.connect(context.applicationContext) }
            if (Player.state.value.current == null) return@withContext
            when (parameters[COMMAND]) {
                "previous" -> Player.previous()
                "next" -> Player.next()
                else -> Player.toggle()
            }
        }
    }
}

/** Keeps the widget in step with playback, and only works while a widget is placed. */
internal object NowPlayingWidgetUpdater {
    val state = MutableStateFlow(WidgetNowPlaying())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private data class Key(val id: String?, val title: String, val artist: String, val playing: Boolean, val cover: String?)

    @Synchronized
    fun start(context: Context) {
        if (job != null) return
        val app = context.applicationContext
        job = scope.launch {
            combine(Player.state, ArtworkRepo.covers) { player, covers ->
                val track = player.current
                Key(track?.id, track?.title.orEmpty(), track?.artist.orEmpty(), player.playWhenReady && track != null,
                    track?.let { com.ketvoice.app.data.MediaOverrides.cover(it, covers["t:${it.id}"] ?: it.coverUrl) })
            }.distinctUntilChanged().collectLatest { key ->
                val placed = runCatching {
                    GlanceAppWidgetManager(app).getGlanceIds(NowPlayingWidget::class.java).isNotEmpty()
                }.getOrDefault(false)
                if (!placed) return@collectLatest
                val previous = state.value
                // Publish text and play state at once; the cover follows when it is decoded.
                val same = previous.title == key.title && previous.artist == key.artist
                state.value = WidgetNowPlaying(key.title, key.artist, key.playing,
                    previous.cover.takeIf { same }, previous.ground.takeIf { same })
                runCatching { NowPlayingWidget().updateAll(app) }
                val bitmap = key.cover?.let { KetvoiceWidgetsUpdater.loadCover(app, it) }
                if (bitmap != null || key.cover == null) {
                    state.value = state.value.copy(cover = bitmap,
                        ground = runCatching { WidgetGlass.ground(bitmap, WidgetGlass.tint(bitmap)) }.getOrNull())
                    runCatching { NowPlayingWidget().updateAll(app) }
                }
            }
        }
    }

}
