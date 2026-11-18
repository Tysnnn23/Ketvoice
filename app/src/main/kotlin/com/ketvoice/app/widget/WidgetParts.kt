package com.ketvoice.app.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ketvoice.app.MainActivity
import com.ketvoice.app.R
import com.ketvoice.app.data.AppText

/** What a tap on a widget does. A tap never does nothing: without a queue it opens Ketvoice (and plays the mix of the moment). */
internal object WidgetActions {
    fun open(): Action = actionStartActivity<MainActivity>()
    fun open(where: String): Action = actionStartActivity<MainActivity>(actionParametersOf(WidgetIntents.openKey to where))

    /** « Écouter » in an empty widget: the mix of the moment when there is one, else Ketvoice resumes what it can. */
    fun playSomething(state: WidgetsState): Action {
        val mix = state.mixId ?: return open(WidgetIntents.OPEN_PLAY)
        return actionStartActivity<MainActivity>(actionParametersOf(WidgetIntents.mixKey to mix, WidgetIntents.mixTitleKey to state.mixTitle))
    }

    fun transport(command: String, state: WidgetsState): Action =
        if (state.hasTrack) actionRunCallback<WidgetTransportCallback>(actionParametersOf(COMMAND to command)) else playSomething(state)
}

/** The ground drawn for Ketvoice's own colours (no cover), made once. */
internal val defaultGround: Bitmap by lazy { WidgetLook.ground(null, WidgetLook.DEFAULT) }
internal val graphiteGround: Bitmap by lazy { WidgetLook.ground(null, WidgetLook.GRAPHITE) }

/**
 * The card of every widget: the launcher's own corner radius (Material You, `system_app_widget_background_radius`),
 * the cover's gradient and the day/night veil over it. [veil] false for content that brings its own (a full-bleed cover).
 */
@Composable
internal fun WidgetSurface(ground: Bitmap?, onClick: Action?, veil: Boolean = true, content: @Composable () -> Unit) {
    // Android 11 (Ketvoice Lite) cannot round a view: the card's picture carries its corners and its veil.
    val drawn = WidgetCorners.drawn()
    var outer = GlanceModifier.fillMaxSize().appWidgetBackground()
    outer = if (drawn) outer.background(ImageProvider(cardPicture(LocalContext.current, ground ?: defaultGround, veil,
            LocalSize.current)), ContentScale.FillBounds)
        else outer.cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(ImageProvider(ground ?: defaultGround), ContentScale.FillBounds)
    if (onClick != null) outer = outer.clickable(onClick)
    Box(outer) {
        Box(if (veil && !drawn) GlanceModifier.fillMaxSize().background(WidgetLook.veil) else GlanceModifier.fillMaxSize()) { content() }
    }
}

/** One line (or a few) of text in the widgets' type: system sans, tight sizes, bold for what matters. */
@Composable
internal fun WText(text: String, sizeSp: Int, color: ColorProvider = WidgetLook.ink, bold: Boolean = false, maxLines: Int = 1,
                   modifier: GlanceModifier = GlanceModifier, medium: Boolean = false) {
    val weight = when { bold -> FontWeight.Bold; medium -> FontWeight.Medium; else -> FontWeight.Normal }
    Text(text, modifier, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = weight), maxLines)
}

/** A small label over a block: « VOTRE SEMAINE », « REPLAY ». */
@Composable
internal fun Eyebrow(text: String, color: ColorProvider = WidgetLook.inkSoft) =
    WText(text.uppercase(), 11, color, bold = true)

/** The cover, rounded a little less than the card, or a quiet tile with a note when there is none. */
@Composable
internal fun WidgetCover(bitmap: Bitmap?, sizeDp: Int, radiusDp: Int = (sizeDp / 5).coerceIn(6, 22)) {
    val modifier = GlanceModifier.size(sizeDp.dp).cornerRadius(radiusDp.dp)
    if (bitmap != null) Image(ImageProvider(if (WidgetCorners.drawn()) WidgetCorners.rounded(bitmap, radiusDp / sizeDp.toFloat())
        else bitmap), null, modifier, contentScale = ContentScale.Crop)
    else NoteTile(sizeDp, radiusDp)
}

@Composable
internal fun NoteTile(sizeDp: Int, radiusDp: Int = (sizeDp / 5).coerceIn(6, 22)) {
    Box(GlanceModifier.size(sizeDp.dp).roundFill(sizeDp, sizeDp, radiusDp.toFloat(), WidgetLook.chip), contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_widget_note), null, GlanceModifier.size((sizeDp * .44f).dp),
            colorFilter = ColorFilter.tint(WidgetLook.inkSoft))
    }
}

/** Play or pause in a filled circle (ink on the veil), the strongest thing on a player. */
@Composable
internal fun PlayButton(state: WidgetsState, sizeDp: Int) {
    val icon = if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
    val label = if (state.playing) "Pause" else "Lecture"
    val glyph = (sizeDp * .46f).toInt()
    Box(GlanceModifier.size(sizeDp.dp).roundFill(sizeDp, sizeDp, sizeDp / 2f, WidgetLook.ink)
        .clickable(WidgetActions.transport("toggle", state)), contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), AppText.t(label), GlanceModifier.size(glyph.dp), colorFilter = ColorFilter.tint(WidgetLook.onInk))
    }
}

/** Previous or next: the glyph alone, with a full-size touch target. */
@Composable
internal fun SkipButton(next: Boolean, state: WidgetsState, sizeDp: Int) {
    val glyph = (sizeDp * .58f).toInt()
    Box(GlanceModifier.size(sizeDp.dp).cornerRadius((sizeDp / 2).dp)
        .clickable(WidgetActions.transport(if (next) "next" else "previous", state)), contentAlignment = Alignment.Center) {
        Image(ImageProvider(if (next) R.drawable.ic_widget_next else R.drawable.ic_widget_previous),
            AppText.t(if (next) "Suivant" else "Précédent"), GlanceModifier.size(glyph.dp), colorFilter = ColorFilter.tint(WidgetLook.ink))
    }
}

/** A rounded button with a play glyph and a word: « Écouter ». */
@Composable
internal fun PillButton(label: String, action: Action, onCover: Boolean = false) {
    val fill = if (onCover) WidgetLook.white else WidgetLook.ink
    val glyph = if (onCover) ColorProvider(androidx.compose.ui.graphics.Color(0xFF111114)) else WidgetLook.onInk
    Box(GlanceModifier.height(34.dp).capsuleFill(34, fill).clickable(action).padding(start = 12.dp, end = 15.dp),
        contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_widget_play), null, GlanceModifier.size(15.dp), colorFilter = ColorFilter.tint(glyph))
            Spacer(GlanceModifier.width(6.dp))
            WText(AppText.t(label), 14, glyph, bold = true)
        }
    }
}

/** The position in the title: a thin rounded bar. */
@Composable
internal fun ProgressLine(progress: Float) {
    LinearProgressIndicator(progress.coerceIn(0f, 1f), GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
        color = WidgetLook.ink, backgroundColor = WidgetLook.track)
}

/** The empty state of a player or the lyrics: « Rien en lecture » and a way to play something. */
@Composable
internal fun EmptyPrompt(state: WidgetsState, titleSp: Int, withButton: Boolean) {
    WText(AppText.t("Rien en lecture"), titleSp, bold = true)
    Spacer(GlanceModifier.height(2.dp))
    WText(AppText.t("Touchez pour écouter"), (titleSp - 2).coerceAtLeast(12), WidgetLook.inkSoft)
    if (withButton) {
        Spacer(GlanceModifier.height(10.dp))
        PillButton("Écouter", WidgetActions.playSomething(state))
    }
}

/** Ketmix's glyph (two rings, as in the app) in a filled tile. */
@Composable
internal fun KetmixTile(sizeDp: Int) {
    Box(GlanceModifier.size(sizeDp.dp).roundFill(sizeDp, sizeDp, sizeDp * .26f, WidgetLook.ink), contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_widget_ketmix), null, GlanceModifier.size((sizeDp * .62f).dp), colorFilter = ColorFilter.tint(WidgetLook.onInk))
    }
}

/** A switch drawn as iOS draws one: a pill and its knob, green when on. */
@Composable
internal fun WidgetSwitch(on: Boolean, action: Action) {
    Box(GlanceModifier.width(50.dp).height(30.dp).roundFill(50, 30, 15f, if (on) WidgetLook.switchOn else WidgetLook.switchOff)
        .clickable(action).padding(2.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(GlanceModifier.size(26.dp).roundFill(26, 26, 13f, WidgetLook.knob)) {}
    }
}
