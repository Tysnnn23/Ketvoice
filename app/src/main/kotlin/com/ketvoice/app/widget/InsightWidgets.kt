package com.ketvoice.app.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
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
import androidx.glance.layout.width
import com.ketvoice.app.data.AppText

// ---------------------------------------------------------------- Votre semaine

/** « Votre semaine »: the minutes, the plays, the artist of the week and the seven days. A tap opens Replay. */
class WeekWidget : KetvoiceGlanceWidget(WidgetKind.Week, WidgetSizing.cards, { WeekContent(it) })
class WeekWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WeekWidget() }

@Composable
internal fun WeekContent(state: WidgetsState) {
    val size = LocalSize.current
    val layout = WidgetSizing.classify(size.width.value, size.height.value)
    WidgetSurface(state.plainGround, WidgetActions.open(WidgetIntents.OPEN_REPLAY)) {
        when {
            state.weekPlays < 1 -> Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
                Eyebrow(AppText.t("Votre semaine"))
                Spacer(GlanceModifier.defaultWeight())
                WText(AppText.t("Écoutez quelques titres pour voir votre semaine"), 14, bold = true, maxLines = 3)
            }
            layout == WidgetSize.Medium -> WeekMedium(state)
            layout == WidgetSize.Large -> WeekLarge(state)
            else -> Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                Eyebrow(AppText.t("Votre semaine"))
                Spacer(GlanceModifier.height(4.dp))
                Minutes(state.weekMinutes, 26)
                Spacer(GlanceModifier.defaultWeight())
                WeekBars(state.weekBars, 34)
            }
        }
    }
}

/** « 214 min », the number big and the unit small. */
@Composable
private fun Minutes(minutes: Long, sizeSp: Int) {
    // The unit as the interface language writes « {0} min » (« 214 minutes », « 214 分 »), drawn smaller.
    val template = AppText.t("{0} min")
    val unit = template.replace("{0}", "").trim()
    val unitFirst = !template.trimStart().startsWith("{0}")
    val unitText: @Composable () -> Unit = {
        WText(unit, (sizeSp / 2).coerceAtLeast(12), WidgetLook.inkSoft, bold = true, modifier = GlanceModifier.padding(bottom = (sizeSp / 8).dp))
    }
    Row(verticalAlignment = Alignment.Bottom) {
        if (unitFirst) { unitText(); Spacer(GlanceModifier.width(4.dp)) }
        WText(WidgetLook.minutes(minutes), sizeSp, bold = true)
        if (!unitFirst) { Spacer(GlanceModifier.width(4.dp)); unitText() }
    }
}

@Composable
private fun WeekMedium(state: WidgetsState) {
    Row(GlanceModifier.fillMaxSize().padding(16.dp)) {
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            Eyebrow(AppText.t("Votre semaine"))
            Spacer(GlanceModifier.height(2.dp))
            Minutes(state.weekMinutes, 28)
            WText("${state.weekPlays} ${AppText.t("écoutes")}", 12, WidgetLook.inkSoft)
            Spacer(GlanceModifier.defaultWeight())
            state.weekArtist?.let { WText(it, 13, bold = true) }
        }
        Spacer(GlanceModifier.width(14.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.Bottom) { WeekBars(state.weekBars, 64) }
    }
}

@Composable
private fun WeekLarge(state: WidgetsState) {
    Column(GlanceModifier.fillMaxSize().padding(18.dp)) {
        Eyebrow(AppText.t("Votre semaine"))
        Spacer(GlanceModifier.height(4.dp))
        Minutes(state.weekMinutes, 38)
        WText("${state.weekPlays} ${AppText.t("écoutes")}", 13, WidgetLook.inkSoft)
        Spacer(GlanceModifier.defaultWeight())
        WeekBars(state.weekBars, 110)
        state.weekArtist?.let {
            Spacer(GlanceModifier.height(14.dp))
            Eyebrow(AppText.t("Artiste de la semaine"))
            WText(it, 15, bold = true)
        }
    }
}

/** Seven rounded bars, today's in ink, the others quiet; a day without music keeps a stub. */
@Composable
private fun WeekBars(bars: List<Float>, heightDp: Int) {
    val days = bars.ifEmpty { List(7) { 0f } }
    Row(GlanceModifier.fillMaxWidth().height(heightDp.dp), verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { index, share ->
            Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 2.dp), contentAlignment = Alignment.BottomCenter) {
                val h = (5 + share * (heightDp - 5)).toInt()
                Box(GlanceModifier.fillMaxWidth().height(h.dp).cornerRadius(4.dp)
                    .background(if (index == days.lastIndex) WidgetLook.ink else WidgetLook.track)) {}
            }
        }
    }
}

// ---------------------------------------------------------------- Pour vous

/** The mix of the hour with its cover: a tap opens Ketvoice and plays it. */
class MixWidget : KetvoiceGlanceWidget(WidgetKind.Mix, WidgetSizing.cards, { MixContent(it) })
class MixWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MixWidget() }

@Composable
internal fun MixContent(state: WidgetsState) {
    val size = LocalSize.current
    val layout = WidgetSizing.classify(size.width.value, size.height.value)
    val play = WidgetActions.playSomething(state)
    val label = AppText.t(state.mixLabel.ifBlank { "Pour vous" })
    when {
        state.mixId == null -> WidgetSurface(state.plainGround, play) {
            Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
                Eyebrow(label)
                Spacer(GlanceModifier.defaultWeight())
                WText(AppText.t("Écoutez quelques titres : votre mix du moment arrivera ici"), 14, bold = true, maxLines = 3)
            }
        }
        layout == WidgetSize.Medium -> WidgetSurface(state.mixGround, play) { MixMedium(state, label, play, size.height.value >= WidgetSizing.ROOMY_H) }
        else -> WidgetSurface(state.mixGround, play, veil = state.mixCover == null) { MixPoster(state, label, play, large = layout == WidgetSize.Large) }
    }
}

/** Square and tall: the cover full-bleed, a veil under the words, the words in white. */
@Composable
private fun MixPoster(state: WidgetsState, label: String, play: androidx.glance.action.Action, large: Boolean) {
    val cover = state.mixCover
    if (cover != null) {
        Image(ImageProvider(cover), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Image(ImageProvider(WidgetLook.scrim), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
    val ink = if (cover != null) WidgetLook.white else WidgetLook.ink
    val soft = if (cover != null) WidgetLook.whiteSoft else WidgetLook.inkSoft
    Column(GlanceModifier.fillMaxSize().padding(if (large) 18.dp else 14.dp), verticalAlignment = Alignment.Bottom) {
        Eyebrow(label, soft)
        WText(state.mixTitle, if (large) 22 else 16, ink, bold = true, maxLines = 2)
        Spacer(GlanceModifier.height(10.dp))
        PillButton("Écouter", play, onCover = cover != null)
    }
}

@Composable
private fun MixMedium(state: WidgetsState, label: String, play: androidx.glance.action.Action, roomy: Boolean) {
    Row(GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        WidgetCover(state.mixCover, if (roomy) 132 else 82, if (roomy) 18 else 14)
        Spacer(GlanceModifier.width(14.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            Eyebrow(label)
            Spacer(GlanceModifier.height(2.dp))
            WText(state.mixTitle, 16, bold = true, maxLines = 2)
            Spacer(GlanceModifier.defaultWeight())
            PillButton("Écouter", play)
        }
    }
}

// ---------------------------------------------------------------- Replay

/** « Replay »: this month's minutes, its artist and its title; a tap opens Replay. */
class ReplayWidget : KetvoiceGlanceWidget(WidgetKind.Replay, WidgetSizing.cards, { ReplayContent(it) })
class ReplayWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ReplayWidget() }

@Composable
internal fun ReplayContent(state: WidgetsState) {
    val size = LocalSize.current
    val layout = WidgetSizing.classify(size.width.value, size.height.value)
    val r = state.replay
    WidgetSurface(r.ground, WidgetActions.open(WidgetIntents.OPEN_REPLAY)) {
        when {
            r.empty -> Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
                ReplayHeading(r)
                Spacer(GlanceModifier.defaultWeight())
                WText(AppText.t("Écoutez quelques titres pour voir votre Replay du mois"), 14, bold = true, maxLines = 3)
            }
            layout == WidgetSize.Medium -> ReplayMedium(r)
            layout == WidgetSize.Large -> ReplayLarge(r)
            else -> Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                ReplayHeading(r)
                Spacer(GlanceModifier.defaultWeight())
                Minutes(r.minutes, 28)
                r.topArtist?.let { WText(it, 13, bold = true) }
            }
        }
    }
}

@Composable
private fun ReplayHeading(r: ReplayWidgetState) {
    Eyebrow("Replay")
    if (r.monthName.isNotBlank()) WText(r.monthName, 13, WidgetLook.inkSoft, medium = true)
}

@Composable
private fun TopTitle(r: ReplayWidgetState, coverDp: Int, titleSp: Int) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WidgetCover(r.cover, coverDp)
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Eyebrow(AppText.t("Titre du mois"))
            WText(r.topTitle.orEmpty(), titleSp, bold = true)
            WText(r.topTitleArtist.orEmpty(), titleSp - 2, WidgetLook.inkSoft)
        }
    }
}

@Composable
private fun ReplayMedium(r: ReplayWidgetState) {
    Row(GlanceModifier.fillMaxSize().padding(16.dp)) {
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            ReplayHeading(r)
            Spacer(GlanceModifier.defaultWeight())
            Minutes(r.minutes, 30)
            r.topArtist?.let { WText(it, 13, bold = true) }
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.width(96.dp).fillMaxHeight(), verticalAlignment = Alignment.Bottom) {
            WidgetCover(r.cover, 64)
            Spacer(GlanceModifier.height(8.dp))
            WText(r.topTitle.orEmpty(), 13, bold = true)
            WText(r.topTitleArtist.orEmpty(), 12, WidgetLook.inkSoft)
        }
    }
}

@Composable
private fun ReplayLarge(r: ReplayWidgetState) {
    Column(GlanceModifier.fillMaxSize().padding(18.dp)) {
        ReplayHeading(r)
        Spacer(GlanceModifier.height(6.dp))
        Minutes(r.minutes, 40)
        WText("${r.plays} ${AppText.t("écoutes")}", 13, WidgetLook.inkSoft)
        Spacer(GlanceModifier.defaultWeight())
        if (r.topTitle != null) TopTitle(r, 64, 15)
        r.topArtist?.let {
            Spacer(GlanceModifier.height(14.dp))
            Eyebrow(AppText.t("Artiste du mois"))
            WText(it, 16, bold = true)
        }
    }
}
