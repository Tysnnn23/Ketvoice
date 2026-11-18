package com.ketvoice.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.ketvoice.app.data.AppText
import com.ketvoice.app.player.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The base of Ketvoice's widgets: responsive sizes ([WidgetSizing]) and the widget's own copy of the state
 * ([KetvoiceWidgetsUpdater.view]), so it redraws only when it is due.
 */
abstract class KetvoiceGlanceWidget internal constructor(
    private val kind: WidgetKind,
    sizes: Set<DpSize>,
    private val content: @Composable (WidgetsState) -> Unit,
) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(sizes)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        KetvoiceWidgetsUpdater.start(context.applicationContext)
        if (kind == WidgetKind.Player || kind == WidgetKind.Lyrics) com.ketvoice.app.player.LiveLyrics.widgetWants = true
        val flow = KetvoiceWidgetsUpdater.fresh(kind)
        provideContent {
            val state by flow.collectAsState()
            content(state)
        }
    }
}

/** The receivers of the widgets that show the sung line: the lyrics clock runs only while one of them is placed. */
abstract class LyricWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        KetvoiceWidgetsUpdater.lyricDemandSoon(context)
    }
}

// ---------------------------------------------------------------- the three players

/** « Lecture en cours »: the cover, the title and the transport, at every size. */
class NowPlayingWidget : KetvoiceGlanceWidget(WidgetKind.NowPlaying, WidgetSizing.players, { PlayerContent(it, withLine = false) })
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget() }

/** « Lecteur »: the large player, with the sung line where there is room for it. */
class GlassPlayerWidget : KetvoiceGlanceWidget(WidgetKind.Player, WidgetSizing.players, { PlayerContent(it, withLine = true) })
class GlassPlayerWidgetReceiver : LyricWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = GlassPlayerWidget() }

/** « Mini »: one row whatever its size: cover, labels when there is room, play/pause and next. */
class MiniControlsWidget : KetvoiceGlanceWidget(WidgetKind.Mini, WidgetSizing.mini, { PlayerContent(it, withLine = false, mini = true) })
class MiniControlsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MiniControlsWidget() }

@Composable
internal fun PlayerContent(state: WidgetsState, withLine: Boolean, mini: Boolean = false) {
    val size = LocalSize.current
    val layout = WidgetSizing.classify(size.width.value, size.height.value)
    val tap = if (state.hasTrack) WidgetActions.open(WidgetIntents.OPEN_PLAYER) else WidgetActions.playSomething(state)
    WidgetSurface(state.ground, tap) {
        when {
            mini || layout == WidgetSize.StripNarrow || layout == WidgetSize.Strip -> PlayerStrip(state, size.width.value)
            !state.hasTrack -> EmptyPlayer(state, layout)
            layout == WidgetSize.Small -> PlayerSmall(state)
            layout == WidgetSize.Medium -> PlayerMedium(state, withLine, roomy = size.height.value >= WidgetSizing.ROOMY_H)
            withLine && state.lyric != null -> PlayerLargeWithLine(state)
            else -> PlayerLarge(state)
        }
    }
}

@Composable
private fun Labels(state: WidgetsState, titleSp: Int, modifier: GlanceModifier = GlanceModifier) {
    Column(modifier) {
        WText(state.title.ifBlank { AppText.t("Rien en lecture") }, titleSp, bold = true)
        WText(state.artist.ifBlank { AppText.t("Touchez pour écouter") }, titleSp - 2, WidgetLook.inkSoft)
    }
}

/** One row: a pill-like strip. Narrow: the cover and play; from 180 dp the labels and next; from 250 dp previous too. */
@Composable
private fun PlayerStrip(state: WidgetsState, widthDp: Float) {
    val labels = widthDp >= WidgetSizing.STRIP_LABELS_W
    Row(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        WidgetCover(state.cover, 40, 10)
        Spacer(GlanceModifier.width(10.dp))
        if (labels) Labels(state, 14, GlanceModifier.defaultWeight()) else Spacer(GlanceModifier.defaultWeight())
        if (state.hasTrack && widthDp >= WidgetSizing.STRIP_FULL_W) SkipButton(next = false, state, 38)
        PlayButton(state, 38)
        if (state.hasTrack && labels) SkipButton(next = true, state, 38)
    }
}

/** About 2×2: the cover and play on top, the title underneath, as Apple Music's small widget. */
@Composable
private fun PlayerSmall(state: WidgetsState) {
    Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            WidgetCover(state.cover, 60, 12)
            Spacer(GlanceModifier.defaultWeight())
            PlayButton(state, 36)
        }
        Spacer(GlanceModifier.defaultWeight())
        Labels(state, 14)
    }
}

/** About 4×2: the cover on the left; beside it, centred on it, the title, the sung line when there is room, the transport. */
@Composable
private fun PlayerMedium(state: WidgetsState, withLine: Boolean, roomy: Boolean) {
    Row(GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        WidgetCover(state.cover, if (roomy) 132 else 82, if (roomy) 18 else 14)
        Spacer(GlanceModifier.width(14.dp))
        Column(GlanceModifier.defaultWeight()) {
            Labels(state, 15)
            val line = state.lyric.takeIf { withLine && roomy }
            if (line != null) {
                Spacer(GlanceModifier.height(6.dp))
                WText(line, 15, bold = true, maxLines = 2)
            }
            Spacer(GlanceModifier.height(8.dp))
            if (roomy) {
                ProgressLine(state.progress)
                Spacer(GlanceModifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkipButton(next = false, state, 38)
                Spacer(GlanceModifier.width(6.dp))
                PlayButton(state, 40)
                Spacer(GlanceModifier.width(6.dp))
                SkipButton(next = true, state, 38)
            }
        }
    }
}

@Composable
private fun LargeTransport(state: WidgetsState) {
    ProgressLine(state.progress)
    Spacer(GlanceModifier.height(12.dp))
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(GlanceModifier.defaultWeight())
        SkipButton(next = false, state, 46)
        Spacer(GlanceModifier.defaultWeight())
        PlayButton(state, 56)
        Spacer(GlanceModifier.defaultWeight())
        SkipButton(next = true, state, 46)
        Spacer(GlanceModifier.defaultWeight())
    }
}

/** About 4×4: a big cover, the title, the progress and the transport. */
@Composable
private fun PlayerLarge(state: WidgetsState) {
    Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
        WidgetCover(state.cover, 112, 18)
        Spacer(GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.height(10.dp))
        Labels(state, 17)
        Spacer(GlanceModifier.height(12.dp))
        LargeTransport(state)
    }
}

/** About 4×4 while synced lyrics play: the title on top, the sung line big and the next one under it. */
@Composable
private fun PlayerLargeWithLine(state: WidgetsState) {
    Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(state.cover, 56, 12)
            Spacer(GlanceModifier.width(12.dp))
            Labels(state, 15, GlanceModifier.defaultWeight())
        }
        Spacer(GlanceModifier.defaultWeight())
        WText(state.lyric.orEmpty(), 21, bold = true, maxLines = 3)
        state.nextLyric?.let {
            Spacer(GlanceModifier.height(4.dp))
            WText(it, 15, WidgetLook.inkFaint, medium = true, maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        LargeTransport(state)
    }
}

/** Nothing loaded: « Rien en lecture » and a button that plays the mix of the moment. */
@Composable
private fun EmptyPlayer(state: WidgetsState, layout: WidgetSize) {
    when (layout) {
        WidgetSize.Medium -> Row(GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            NoteTile(82, 14)
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight()) { EmptyPrompt(state, 15, withButton = true) }
        }
        WidgetSize.Large -> Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
            NoteTile(112, 18)
            Spacer(GlanceModifier.defaultWeight())
            EmptyPrompt(state, 20, withButton = true)
        }
        else -> Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
            NoteTile(48, 12)
            Spacer(GlanceModifier.defaultWeight())
            EmptyPrompt(state, 14, withButton = false)
        }
    }
}

// ---------------------------------------------------------------- transport

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
