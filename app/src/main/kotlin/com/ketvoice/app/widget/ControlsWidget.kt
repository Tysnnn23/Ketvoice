package com.ketvoice.app.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.ketvoice.app.data.AppText

/**
 * « Commandes » (7.1): a 4×1 strip that can be narrowed to two cells and still keeps previous,
 * play/pause and next; the cover joins them from three cells, the title and the artist from four.
 * Spotify replaced its small widget in 2026 and lost its controls in narrow sizes; this one never does.
 */
class ControlsWidget : KetvoiceGlanceWidget(WidgetKind.Controls, WidgetSizing.controls, { ControlsContent(it) })
class ControlsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ControlsWidget() }

@Composable
internal fun ControlsContent(state: WidgetsState) {
    val parts = WidgetSizing.controlsParts(LocalSize.current.width.value)
    val tap = if (state.hasTrack) WidgetActions.open(WidgetIntents.OPEN_PLAYER) else WidgetActions.playSomething(state)
    WidgetSurface(state.ground, tap) {
        Row(GlanceModifier.fillMaxSize().padding(horizontal = if (parts.cover) 8.dp else 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = if (parts.cover) Alignment.Start else Alignment.CenterHorizontally) {
            if (parts.cover) {
                WidgetCover(state.cover, 40, 10)
                Spacer(GlanceModifier.width(8.dp))
            }
            if (parts.labels) Column(GlanceModifier.defaultWeight()) {
                WText(state.title.ifBlank { AppText.t("Rien en lecture") }, 14, bold = true)
                WText(state.artist.ifBlank { AppText.t("Touchez pour écouter") }, 12, WidgetLook.inkSoft)
            } else if (parts.cover) Spacer(GlanceModifier.defaultWeight())
            SkipButton(next = false, state, parts.skipDp)
            PlayButton(state, parts.playDp)
            SkipButton(next = true, state, parts.skipDp)
        }
    }
}
