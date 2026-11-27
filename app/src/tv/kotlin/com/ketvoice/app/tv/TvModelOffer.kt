package com.ketvoice.app.tv

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.BeatModel
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.stems.ModelPack
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.delay

/**
 * « Ketmix plus fort sur TV puissante » (Ketvoice TV 1.1): only on the Full tier, the phone's optional rhythm model
 * (Beat This! small, prerelease `ketmix-beat-v1`, pinned SHA-256 in [BeatModel]; the TV APK does not carry it) is
 * offered in Settings › Ketmix (« Modèle de rythme », its size and the download's progress) and suggested once, and
 * accepting it also sets Ketmix's analysis to « Maximale » (longer reads, the key, sections). Light and Balanced TVs
 * stay on their light analysis and never see it. The download runs beside playback, never in its way.
 */
internal object TvModelOffer {
    /** This TV's own flags (shared by its profiles: the model is one file for everyone). */
    const val PREFS = "tv-device"
    private const val SUGGESTED = "rhythmModelSuggested"
    const val MAXIMUM = 2

    fun offered(tier: TvTier): Boolean = tier == TvTier.Full

    /** Suggested once, on a Full TV, while the model is neither there nor on its way. */
    fun suggest(tier: TvTier, bundled: Boolean, state: ModelPack.State, alreadySuggested: Boolean): Boolean =
        offered(tier) && !bundled && !alreadySuggested && (state == ModelPack.State.Missing || state is ModelPack.State.Failed)

    /** What the setting's line shows on its right (French source text). */
    fun value(state: ModelPack.State): String = when (state) {
        ModelPack.State.Missing -> "Télécharger"
        is ModelPack.State.Downloading -> AppText.t("Téléchargement") + " · ${(state.fraction * 100).toInt()} %"
        ModelPack.State.Ready -> "Prêt"
        is ModelPack.State.Failed -> "Échec · réessayer"
    }

    /** « 18 Mo » */
    fun size(bytes: Long): String = "${bytes / 1_000_000L} Mo"

    fun suggested(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SUGGESTED, false)

    fun markSuggested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SUGGESTED, true).apply()
    }

    /** « Télécharger »: the model in the background, and Ketmix's deeper analysis on this TV. */
    fun accept() {
        BeatModel.pack.download()
        AppSettings.ketmixAnalysis.value = MAXIMUM
        runCatching { com.ketvoice.app.data.SettingsStore.save() }
    }

    /** The line's click: download, cancel a download, or try again; a model that is there stays. */
    fun toggle(state: ModelPack.State) {
        when (state) {
            is ModelPack.State.Downloading -> BeatModel.pack.cancel()
            ModelPack.State.Ready -> Unit
            else -> accept()
        }
    }
}

/** Settings › Ketmix, on a Full TV only: « Modèle de rythme », its size, and the download as it goes. */
@Composable
fun TvRhythmModelLine() {
    if (!TvModelOffer.offered(tvTier()) || BeatModel.bundled) return
    val state by BeatModel.pack.state.collectAsState()
    val sub = AppText.t("Ketmix cale ses mix sur les temps et les mesures trouvés par un petit modèle d'IA, et lit les titres plus en profondeur. Téléchargé une fois :") +
        " " + TvModelOffer.size(BeatModel.pack.totalBytes)
    TvSettingLine("Modèle de rythme", sub, TvModelOffer.value(state), "rhythm-model", onClick = { TvModelOffer.toggle(state) },
        on = state == ModelPack.State.Ready, localizeSubtitle = false, localizeValue = state !is ModelPack.State.Downloading)
}

/** The one suggestion, a few seconds after the start of a Full TV; either answer is remembered. */
@Composable
fun TvRhythmModelSuggestion() {
    val context = LocalContext.current
    val tier = tvTier()
    val state by BeatModel.pack.state.collectAsState()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(tier) {
        delay(SUGGEST_AFTER_MS)
        show = TvModelOffer.suggest(tier, BeatModel.bundled, state, TvModelOffer.suggested(context)) && !TvProfiles.picking.value
        if (show) TvModelOffer.markSuggested(context)
    }
    if (!show) return
    TvRhythmModelCard(onDownload = { TvModelOffer.accept(); show = false }, onLater = { show = false })
}

private const val SUGGEST_AFTER_MS = 6_000L

/** Stateless, so the host tests can draw it. */
@Composable
fun TvRhythmModelCard(onDownload: () -> Unit, onLater: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onLater)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).testTag("rhythm-offer"), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).clip(RoundedCornerShape(TvDesign.Radius.panel)).background(Color(0xFF1C1C22)).padding(28.dp).holdFocus(first)) {
            Text("Ketmix plus fort sur ce téléviseur", style = TvDesign.Type.title, color = TvDesign.Ink.primary)
            Spacer(Modifier.height(12.dp))
            Text(AppText.t("Ce téléviseur est assez puissant pour le modèle de rythme de Ketmix et son analyse maximale : des mix mieux calés sur les temps. Téléchargé une fois :") +
                " " + TvModelOffer.size(BeatModel.pack.totalBytes) + ". " + AppText.t("La lecture continue pendant le téléchargement."),
                localize = false, style = TvDesign.Type.body, color = TvDesign.Ink.secondary)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Télécharger", KetIcons.Download, onDownload, Modifier.focusRequester(first), primary = true, tag = "rhythm-download")
                TvButton("Plus tard", null, onLater, tag = "rhythm-later")
            }
        }
    }
}
