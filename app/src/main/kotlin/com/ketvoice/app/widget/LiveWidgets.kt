package com.ketvoice.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- Paroles en direct

/**
 * « Paroles en direct » (6.4): the sung line big and the next one under it, from the same lyrics clock as the
 * lock screen ([com.ketvoice.app.player.LiveLyrics]): it wakes only when a line changes, only while a title
 * plays and the screen is on, and the widget redraws at most every [WidgetRefresh.LYRIC_SPACING_MS].
 */
class LyricsWidget : KetvoiceGlanceWidget(WidgetKind.Lyrics, WidgetSizing.live, { LyricsContent(it) })
class LyricsWidgetReceiver : LyricWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = LyricsWidget() }

/** How a lyrics widget is laid out at one size. */
private data class LyricsSpec(val padDp: Int, val coverDp: Int, val lineSp: Int, val lineMax: Int, val nextSp: Int, val nextMax: Int, val button: Boolean,
                              /** A tall card holds the lines in its middle, as the lyrics screen does; smaller ones at the bottom. */
                              val centred: Boolean = false)

private fun lyricsSpec(layout: WidgetSize, roomy: Boolean): LyricsSpec = when (layout) {
    WidgetSize.Small -> LyricsSpec(14, 26, 16, 3, 12, 2, button = false)
    WidgetSize.Medium -> if (roomy) LyricsSpec(16, 30, 21, 3, 15, 2, button = true) else LyricsSpec(15, 26, 18, 2, 13, 1, button = true)
    else -> LyricsSpec(18, 36, 25, 5, 17, 3, button = true, centred = true)
}

/** What the widget says when no line is sung. */
internal fun lyricsMessage(state: WidgetsState): String = when {
    !state.hasTrack -> "Rien en lecture"
    !state.playing -> "En pause"
    else -> "Pas de paroles synchronisées"
}

@Composable
internal fun LyricsContent(state: WidgetsState) {
    val size = LocalSize.current
    val layout = WidgetSizing.classify(size.width.value, size.height.value)
    val tap = if (state.hasTrack) WidgetActions.open(WidgetIntents.OPEN_LYRICS) else WidgetActions.playSomething(state)
    WidgetSurface(state.ground, tap) {
        if (layout == WidgetSize.Strip || layout == WidgetSize.StripNarrow) LyricsStrip(state)
        else LyricsCard(state, lyricsSpec(layout, size.height.value >= WidgetSizing.ROOMY_H))
    }
}

@Composable
private fun LyricsStrip(state: WidgetsState) {
    Row(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        WidgetCover(state.cover, 36, 9)
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            WText(state.lyric ?: AppText.t(lyricsMessage(state)), 14, bold = true)
            WText(state.nextLyric ?: state.title.ifBlank { AppText.t("Touchez pour écouter") }, 12, WidgetLook.inkSoft)
        }
    }
}

@Composable
private fun LyricsHeader(state: WidgetsState, coverDp: Int) {
    if (!state.hasTrack) { Eyebrow(AppText.t("Paroles en direct")); return }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WidgetCover(state.cover, coverDp, (coverDp / 4).coerceAtLeast(6))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            WText(state.title, 12, bold = true)
            WText(state.artist, 11, WidgetLook.inkSoft)
        }
    }
}

@Composable
private fun LyricsCard(state: WidgetsState, spec: LyricsSpec) {
    Column(GlanceModifier.fillMaxSize().padding(spec.padDp.dp)) {
        LyricsHeader(state, spec.coverDp)
        Spacer(GlanceModifier.defaultWeight())
        val line = state.lyric
        when {
            !state.hasTrack -> EmptyPrompt(state, spec.lineSp - 2, withButton = spec.button)
            line == null -> WText(AppText.t(lyricsMessage(state)), spec.lineSp - 2, WidgetLook.inkSoft, bold = true, maxLines = 2)
            else -> {
                WText(line, spec.lineSp, bold = true, maxLines = spec.lineMax)
                state.nextLyric?.let {
                    Spacer(GlanceModifier.height(4.dp))
                    WText(it, spec.nextSp, WidgetLook.inkFaint, medium = true, maxLines = spec.nextMax)
                }
            }
        }
        if (spec.centred) Spacer(GlanceModifier.defaultWeight())
    }
}

// ---------------------------------------------------------------- Ketmix

/** « Ketmix »: the switch, and what it mixes in next (or the two titles while it mixes). */
class KetmixWidget : KetvoiceGlanceWidget(WidgetKind.Ketmix, WidgetSizing.live, { KetmixContent(it) })
class KetmixWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = KetmixWidget() }

/** Ketmix's state in words. */
internal fun ketmixStatus(k: KetmixWidgetState): String = when {
    !k.on -> "Désactivé"
    k.mixing -> "Mix en cours"
    else -> "Activé"
}

@Composable
internal fun KetmixContent(state: WidgetsState) {
    val size = LocalSize.current
    val layout = WidgetSizing.classify(size.width.value, size.height.value)
    val k = state.ketmix
    val toggle = actionRunCallback<KetmixToggleCallback>()
    // Off: graphite; on: the colours of what plays.
    WidgetSurface(if (k.on) state.ground else graphiteGround, WidgetActions.open(WidgetIntents.OPEN_PLAYER)) {
        when (layout) {
            WidgetSize.StripNarrow, WidgetSize.Strip -> Row(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                KetmixTile(36)
                Spacer(GlanceModifier.width(10.dp))
                // Narrow: the glyph and the switch say it all.
                if (layout == WidgetSize.Strip) Column(GlanceModifier.defaultWeight()) {
                    WText("Ketmix", 14, bold = true)
                    WText(AppText.t(ketmixStatus(k)), 12, WidgetLook.inkSoft)
                } else Spacer(GlanceModifier.defaultWeight())
                WidgetSwitch(k.on, toggle)
            }
            WidgetSize.Small -> KetmixSmall(k, toggle)
            else -> KetmixWide(k, toggle, large = layout == WidgetSize.Large)
        }
    }
}

@Composable
private fun KetmixSmall(k: KetmixWidgetState, toggle: androidx.glance.action.Action) {
    Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            KetmixTile(40)
            Spacer(GlanceModifier.defaultWeight())
            WidgetSwitch(k.on, toggle)
        }
        Spacer(GlanceModifier.defaultWeight())
        WText("Ketmix", 15, bold = true)
        WText(AppText.t(ketmixStatus(k)), 12, WidgetLook.inkSoft)
        if (k.on && k.nextTitle.isNotBlank()) WText("${AppText.t("Ensuite")} · ${k.nextTitle}", 12, WidgetLook.inkSoft)
    }
}

@Composable
private fun KetmixWide(k: KetmixWidgetState, toggle: androidx.glance.action.Action, large: Boolean) {
    Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KetmixTile(44)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                WText("Ketmix (BÊTA)", 16, bold = true)
                WText(AppText.t(ketmixStatus(k)), 13, WidgetLook.inkSoft)
            }
            WidgetSwitch(k.on, toggle)
        }
        Spacer(GlanceModifier.defaultWeight())
        when {
            !k.on -> WText(AppText.t("Activez Ketmix pour que les titres s'enchaînent comme dans un mix."), 13, WidgetLook.inkSoft, maxLines = 3)
            k.nextTitle.isBlank() -> WText(AppText.t("Aucun titre après celui-ci dans la file d'attente."), 13, WidgetLook.inkSoft, maxLines = 2)
            else -> Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WidgetCover(k.nextCover, if (large) 72 else 44, if (large) 14 else 10)
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Eyebrow(AppText.t(if (k.mixing) "Mix en cours" else "Ensuite"))
                    WText(k.nextTitle, if (large) 16 else 14, bold = true)
                    WText(k.nextArtist, if (large) 14 else 12, WidgetLook.inkSoft)
                }
            }
        }
    }
}

/** The widget's switch: Ketmix on or off, kept like the app's own switch, and the tile follows. */
class KetmixToggleCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { KetmixSwitch.flip(context) }
    }
}

/** Ketmix switched from outside the app (a widget or a tile). */
internal object KetmixSwitch {
    fun flip(context: Context): Boolean {
        val app = context.applicationContext
        if (!SettingsStore.loaded) runCatching { SettingsStore.load(app) }
        val next = !AppSettings.automix.value
        AppSettings.automix.value = next
        SettingsStore.save()
        // Outside a screen of the app, nothing else sends the state's change notifications.
        runCatching { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        KetvoiceWidgetsUpdater.ketmixChanged()
        QuickTiles.refresh(app)
        return next
    }
}
