package com.ketvoice.app.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppLocales
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.theme.KetTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.LiquidGlassSwitch
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val TAU = 2f * PI.toFloat()

// ------------------------------------------------------------------ page 1: the language

/** The language list: the phone's language first, then the eleven of the app, each in its own script. */
@Composable
internal fun LanguageList() {
    val context = LocalContext.current
    val chosen = if (AppSettings.languageChosen.value) AppLanguage.fromLabel(AppSettings.language.value) else null
    Group(Modifier.testTag("onboarding-languages")) {
        LanguageRow("Langue du téléphone", AppLocales.systemLanguage().nativeName, chosen == null, localize = true) { pick(context, null) }
        AppLanguage.all.forEach { language ->
            Divider()
            LanguageRow(language.nativeName, language.englishName, chosen == language, localize = false) { pick(context, language) }
        }
    }
}

/** Applies a language as Settings does: the choice is stored, then Android's per-app language follows it. */
private fun pick(context: android.content.Context, language: AppLanguage?) {
    AppSettings.languageChosen.value = language != null
    AppSettings.language.value = (language ?: AppLocales.systemLanguage()).label
    SettingsStore.save()
    runCatching { AppLocales.apply(context, language) }
}

@Composable
private fun LanguageRow(title: String, sub: String, selected: Boolean, localize: Boolean, onClick: () -> Unit) {
    val th = LocalTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, localize = localize, color = th.onBackground, fontSize = 17.sp)
            Text(sub, localize = false, color = th.secondary, fontSize = 13.sp)
        }
        if (selected) Icon(KetIcons.Check, "Sélectionné", tint = th.accent, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------ page 2: the playlists

/** The playlist import: a choice carried out at the end of the welcome, or « Bientôt » while the import is not there. */
@Composable
internal fun ImportChoice(state: PageState) {
    val th = LocalTheme.current
    Group(Modifier.testTag("onboarding-import")) {
        if (state.importAvailable) SwitchRow("Importer mes playlists",
            "Spotify, Deezer… L'import s'ouvre à la fin de l'accueil.", state.importPlaylists, state.onImport, "onboarding-import-switch") {
            Icon(KetIcons.Playlist, null, tint = th.accent, modifier = Modifier.size(26.dp))
        } else Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Playlist, null, tint = th.secondary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Importer mes playlists", color = th.onBackground, fontSize = 17.sp)
                Text("L'import depuis Spotify et Deezer arrive bientôt.", color = th.secondary, fontSize = 13.sp)
            }
            Soon(th)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("Vos favoris et vos playlists Ketvoice se retrouvent dans la Bibliothèque.", color = th.secondary, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
}

@Composable
private fun Soon(th: KetTheme) {
    Text("Bientôt", color = th.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(th.accent.copy(alpha = .14f))
            .padding(horizontal = 10.dp, vertical = 4.dp).testTag("onboarding-import-soon"))
}

// ------------------------------------------------------------------ page 3: Ketmix and the voice

@Composable
internal fun FeatureSwitches(state: PageState) {
    Group(Modifier.testTag("onboarding-features")) {
        SwitchRow("Ketmix (BETA)", "Les titres s'enchaînent comme chez un DJ : la fin de l'un se mêle au début du suivant, sur le temps.",
            state.ketmix, state.onKetmix, "onboarding-ketmix") { MixArt(Modifier.size(56.dp)) }
        Divider()
        SwitchRow("Niveau de la voix", "Baissez ou retirez la voix d'un titre depuis les paroles. Le modèle de séparation (environ 40 Mo) est téléchargé une seule fois.",
            state.voice, state.onVoice, "onboarding-voice") { VoiceArt(Modifier.size(56.dp)) }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit, tag: String,
                      art: @Composable () -> Unit) {
    val th = LocalTheme.current
    Row(Modifier.fillMaxWidth().toggleable(on, role = Role.Switch, onValueChange = onChange).padding(14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { art() }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = th.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = th.secondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
        }
        LiquidGlassSwitch(on, onCheckedChange = null)
    }
}

/** A grouped list, as in Settings. */
@Composable
private fun Group(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val th = LocalTheme.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(th.card), content = content)
}

@Composable
private fun Divider() {
    val th = LocalTheme.current
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(.5.dp).background(th.hairline))
}

// ------------------------------------------------------------------ the animated pictures

/** A loop of [periodMs], 0..1; with reduced motion it holds at [still]. */
@Composable
private fun loop(periodMs: Int, still: Float = .3f, reverse: Boolean = false): State<Float> {
    if (Motion.reduced) return remember { androidx.compose.runtime.mutableFloatStateOf(still) }
    return rememberInfiniteTransition(label = "onboarding-loop").animateFloat(0f, 1f,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing), if (reverse) RepeatMode.Reverse else RepeatMode.Restart),
        label = "onboarding-t")
}

@Composable
internal fun PageArt(page: OnboardingPage, modifier: Modifier) {
    val th = LocalTheme.current
    val t = loop(if (page == OnboardingPage.Playlists) 2_800 else 6_000)
    Canvas(modifier.clearAndSetSemantics { }.testTag("onboarding-art")) {
        when (page) {
            OnboardingPage.Language -> globe(th, t.value)
            OnboardingPage.Playlists -> covers(th, t.value)
            OnboardingPage.Features -> decks(th, t.value)
        }
    }
}

/** A globe turning slowly, three little speech bubbles around it. */
private fun DrawScope.globe(th: KetTheme, t: Float) {
    val r = size.minDimension * .34f
    val stroke = Stroke(2.dp.toPx())
    drawCircle(th.accent.copy(alpha = .12f), r * 1.25f)
    drawCircle(th.accent, r, style = stroke)
    for (i in 0 until 3) {
        val w = r * abs(cos(TAU * (t + i / 6f)))
        drawOval(th.accent.copy(alpha = .7f), Offset(center.x - w, center.y - r), Size(w * 2f, r * 2f), style = Stroke(1.4.dp.toPx()))
    }
    for (y in listOf(-.5f, 0f, .5f)) {
        val half = r * kotlin.math.sqrt(1f - y * y)
        drawLine(th.accent.copy(alpha = .55f), Offset(center.x - half, center.y + y * r), Offset(center.x + half, center.y + y * r), 1.4.dp.toPx())
    }
    listOf(Offset(-1.15f, -.85f), Offset(1.05f, -.55f), Offset(.95f, .95f)).forEachIndexed { i, at ->
        val bob = sin(TAU * (t * 2f + i / 3f)) * 4.dp.toPx()
        bubble(center + at * r + Offset(0f, bob), lerp(th.accent, th.secondary, i * .3f))
    }
}

private fun DrawScope.bubble(at: Offset, colour: Color) {
    val w = 30.dp.toPx(); val h = 20.dp.toPx()
    drawRoundRect(colour.copy(alpha = .9f), Offset(at.x - w / 2, at.y - h / 2), Size(w, h), CornerRadius(h / 2))
    for (k in -1..1) drawCircle(Color.White.copy(alpha = .9f), 1.8.dp.toPx(), Offset(at.x + k * 6.dp.toPx(), at.y))
}

/** Three covers gliding one after the other onto a stack: playlists coming into the library. */
private fun DrawScope.covers(th: KetTheme, t: Float) {
    val side = size.minDimension * .42f
    val target = Offset(center.x + side * .15f, center.y - side / 2)
    val colours = listOf(th.accent, lerp(th.accent, Color.White, .35f), lerp(th.accent, th.secondary, .5f))
    drawRoundRect(th.onBackground.copy(alpha = .08f), Offset(target.x - 8.dp.toPx(), target.y + side + 10.dp.toPx()),
        Size(side + 16.dp.toPx(), 6.dp.toPx()), CornerRadius(3.dp.toPx()))
    for (i in 2 downTo 0) {
        val local = ((t * 1.4f - i * .18f).coerceIn(0f, 1f)).let { it * it * (3f - 2f * it) }
        val from = Offset(-side * .2f, center.y - side / 2 - (i - 1) * side * .55f)
        val to = target + Offset(i * 7.dp.toPx(), -i * 7.dp.toPx())
        val at = Offset(from.x + (to.x - from.x) * local, from.y + (to.y - from.y) * local)
        drawRoundRect(colours[i].copy(alpha = .35f + .65f * local), at, Size(side, side), CornerRadius(side * .14f))
        note(at + Offset(side / 2, side / 2), side * .18f)
    }
}

private fun DrawScope.note(at: Offset, s: Float) {
    drawCircle(Color.White.copy(alpha = .9f), s * .45f, at + Offset(-s * .25f, s * .55f))
    drawLine(Color.White.copy(alpha = .9f), at + Offset(s * .15f, s * .55f), at + Offset(s * .15f, -s * .7f), s * .16f, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = .9f), at + Offset(s * .15f, -s * .7f), at + Offset(s * .6f, -s * .45f), s * .16f, StrokeCap.Round)
}

/** Two records and, between them, one title's wave giving way to the next one's. */
private fun DrawScope.decks(th: KetTheme, t: Float) {
    val r = size.minDimension * .2f
    val left = Offset(size.width * .22f, center.y)
    val right = Offset(size.width * .78f, center.y)
    val mix = .5f - .5f * cos(TAU * t)
    record(left, r, th.accent.copy(alpha = 1f - .6f * mix), t)
    record(right, r, th.secondary.copy(alpha = .4f + .6f * mix), -t)
    wave(left.x + r * 1.1f, right.x - r * 1.1f, center.y, r * .6f * (1f - mix), th.accent, t)
    wave(left.x + r * 1.1f, right.x - r * 1.1f, center.y, r * .6f * mix, th.secondary, t + .25f)
}

private fun DrawScope.record(at: Offset, r: Float, colour: Color, turn: Float) {
    drawCircle(colour, r, at)
    drawCircle(Color.Black.copy(alpha = .25f), r * .78f, at, style = Stroke(1.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = .25f), r * .58f, at, style = Stroke(1.dp.toPx()))
    drawCircle(Color.White.copy(alpha = .85f), r * .2f, at)
    val a = TAU * turn
    drawLine(Color.White.copy(alpha = .5f), at + Offset(cos(a), sin(a)) * (r * .3f), at + Offset(cos(a), sin(a)) * (r * .9f), 1.5.dp.toPx())
}

private fun DrawScope.wave(from: Float, to: Float, y: Float, amplitude: Float, colour: Color, t: Float) {
    if (to <= from) return
    val path = Path()
    var x = from
    while (x <= to) {
        val u = (x - from) / (to - from)
        val wy = y + sin(TAU * (u * 3f + t * 2f)) * amplitude * sin(PI.toFloat() * u)
        if (x == from) path.moveTo(x, wy) else path.lineTo(x, wy)
        x += 3.dp.toPx()
    }
    drawPath(path, colour, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
}

/** Ketmix in small: two waves trading places, the way two titles blend. */
@Composable
private fun MixArt(modifier: Modifier) {
    val th = LocalTheme.current
    val t = loop(3_200, still = .5f, reverse = true)
    Canvas(modifier.clearAndSetSemantics { }) {
        val mix = t.value
        wave(0f, size.width, center.y, size.height * .3f * (1f - mix), th.accent, mix)
        wave(0f, size.width, center.y, size.height * .3f * mix, th.secondary, mix + .25f)
    }
}

/** The voice level in small: the middle bars (the voice) sink and come back while the music bars keep playing. */
@Composable
private fun VoiceArt(modifier: Modifier) {
    val th = LocalTheme.current
    val t = loop(2_400, still = .4f)
    Canvas(modifier.clearAndSetSemantics { }) {
        val bars = 7
        val gap = size.width / (bars * 2f)
        val voice = .25f + .75f * (.5f + .5f * cos(TAU * t.value))
        for (i in 0 until bars) {
            val isVoice = i in 2..4
            val beat = .55f + .45f * abs(sin(TAU * (t.value * 2f + i * .17f)))
            val h = size.height * .8f * beat * (if (isVoice) voice else .8f)
            val x = gap * (1 + i * 2)
            drawLine(if (isVoice) th.accent else th.secondary.copy(alpha = .7f), Offset(x, center.y - h / 2), Offset(x, center.y + h / 2),
                gap * .9f, StrokeCap.Round)
        }
    }
}
