package com.ketvoice.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.stems.OrtAccel
import com.ketvoice.desktop.ketmix.KetmixFeedback
import com.ketvoice.desktop.ketmix.KetmixPcSettings
import com.ketvoice.desktop.ketmix.KetmixPower
import com.ketvoice.desktop.ketmix.LibraryPrep
import com.ketvoice.desktop.ketmix.PcModels
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * « Puissance de Ketmix » (Windows 2.0), for the interface to place in Ketmix's settings: the tier (Auto / Équilibré /
 * Maximum), what this PC is, the large models' download, the graphics card, the idle preparation and « Ketmix apprend ».
 */
@Composable
fun KetmixPowerSection() {
    val machine = KetmixPower.machine()
    val tier = KetmixPower.tier(KetmixPcSettings.power.value, machine)
    SettingsCard {
        ChoiceRow("Puissance de Ketmix", KetmixPcSettings.power.value, listOf("Automatique", "Équilibré", "Maximum"), "ketmix-power") {
            KetmixPcSettings.power.value = it
        }
        Hairline()
        SettingRow(AppText.t("Cet ordinateur"), KetmixPower.describe(machine) { AppText.t(it) })
        if (tier == KetmixPower.Tier.Maximum) {
            Hairline()
            LargeModelsRow(KetmixPower.models(tier, machine))
            if (machine.gpu != null) {
                Hairline()
                ToggleRow("Accélération GPU", KetmixPcSettings.gpu.value, "ketmix-gpu") { KetmixPcSettings.gpu.value = it }
                Hairline()
                GpuRow()
            }
            Hairline()
            IdleRow()
        }
        Hairline()
        ToggleRow("Ketmix apprend de vos avis", KetmixPcSettings.learns.value, "ketmix-learns") { KetmixPcSettings.learns.value = it }
        if (KetmixPcSettings.learns.value) {
            Hairline()
            ActionText("Oublier ce que Ketmix a appris", "ketmix-forget") { KetmixFeedback.reset() }
        }
    }
}

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
@Composable
private fun LargeModelsRow(models: Set<com.ketvoice.desktop.ketmix.PcModel>) {
    val ket = LocalKet.current
    val progress by PcModels.progress.collectAsState()
    val missing = PcModels.missing(models)
    val text = when {
        progress.running -> "${(progress.fraction * 100).toInt()} %"
        missing.isEmpty() -> AppText.t("Prêt")
        progress.failed != null -> AppText.t("Échec · réessayer")
        else -> AppText.t("Télécharger") + " · ${PcModels.bytes(missing) / 1_000_000L} Mo"
    }
    Row(Modifier.fillMaxWidth().height(44.dp).clickable(enabled = !progress.running && missing.isNotEmpty()) {
        GlobalScope.launch(Dispatchers.IO) { PcModels.ensure(models) }
    }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        KText("Grands modèles", Ket27Desk.Type.subheadline, Modifier.weight(1f))
        KText(text, Ket27Desk.Type.subheadline, color = if (missing.isNotEmpty() && !progress.running) ket.accent else ket.secondary,
            weight = FontWeight.Normal, translate = false)
    }
}

@Composable
private fun GpuRow() {
    val status by OrtAccel.status.collectAsState()
    val value = when (status) {
        OrtAccel.Status.Gpu -> OrtAccel.deviceName ?: AppText.t("Carte graphique")
        OrtAccel.Status.Checking -> AppText.t("Vérification…")
        OrtAccel.Status.Failed -> AppText.t("Échec, processeur utilisé")
        OrtAccel.Status.Cpu -> AppText.t("Processeur")
    }
    SettingRow(AppText.t("Carte graphique"), value)
}

@Composable
private fun IdleRow() {
    val p by LibraryPrep.progress.collectAsState()
    val value = when {
        p.running -> "${p.done} / ${p.total}"
        p.paused -> AppText.t("En pause")
        else -> AppText.t("En attente du repos")
    }
    SettingRow(AppText.t("Préparation au repos"), value)
}

@Composable
private fun ActionText(label: String, tag: String, onClick: () -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.subheadline, Modifier.weight(1f), color = ket.accent)
    }
}
