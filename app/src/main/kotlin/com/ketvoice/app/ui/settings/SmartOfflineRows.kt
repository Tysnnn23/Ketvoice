package com.ketvoice.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.SmartOffline
import com.ketvoice.app.data.SmartOfflinePlan
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * « Hors ligne intelligent » in Settings → Téléchargements (7.0): the switch, the room it may take, the charging
 * condition, the list of what it downloaded and « Supprimer les téléchargements automatiques ». [onChoice] opens
 * the settings' picker.
 */
@Composable
internal fun ColumnScope.SmartOfflineRows(onChoice: (ChoiceSpec) -> Unit) {
    val ctx = LocalContext.current
    SToggle(KetIcons.Download, "Hors ligne intelligent",
        "Télécharge tout seul, en Wi-Fi, ce que tu écoutes le plus et tes nouveaux favoris.",
        AppSettings.smartOffline.value) {
        AppSettings.smartOffline.value = it; SettingsStore.save(); SmartOffline.settingsChanged(ctx)
    }
    if (!AppSettings.smartOffline.value) return
    SRow(KetIcons.Storage, "Espace maximum",
        sub = "Quand il manque de la place, les titres automatiques les moins écoutés partent en premier, jamais ceux que tu as téléchargés toi-même.",
        value = limitLabel(AppSettings.smartOfflineGb.value, AppSettings.smartOfflinePercent.value)) {
        onChoice(limitChoice { gb, percent ->
            AppSettings.smartOfflineGb.value = gb
            if (percent != null) AppSettings.smartOfflinePercent.value = percent
            SettingsStore.save(); SmartOffline.settingsChanged(ctx)
        })
    }
    SToggle(KetIcons.Sliders, "Seulement en charge",
        "Les téléchargements automatiques attendent que le téléphone soit branché.",
        AppSettings.smartOfflineCharging.value) {
        AppSettings.smartOfflineCharging.value = it; SettingsStore.save(); SmartOffline.settingsChanged(ctx)
    }
    SmartOfflineListRows()
}

@Composable
private fun ColumnScope.SmartOfflineListRows() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val auto by SmartOffline.autoIds.collectAsState()
    val items by Downloader.items.collectAsState()
    val usage = remember(auto, items) { SmartOffline.usage() }
    var asking by remember { mutableStateOf(false) }
    SRow(KetIcons.Album, "Voir ce qui a été téléchargé automatiquement",
        value = usageLabel(ctx, usage)) {
        SmartOffline.showAutoOnly.value = true
        AppUi.pushOverlay(AppUi.DOWNLOADS)
    }
    if (usage.count > 0) SRow(KetIcons.Trash, "Supprimer les téléchargements automatiques",
        sub = "Garde les titres que tu as téléchargés toi-même") { asking = true }
    if (!asking) return
    AppAlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Supprimer les téléchargements automatiques ?") },
        text = { Text("Les titres téléchargés par Ketvoice sont supprimés. Tant que le hors ligne intelligent reste activé, ils peuvent revenir au prochain passage.") },
        confirmButton = { KetTextButton("Supprimer", {
            asking = false
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { SmartOffline.clearAuto() } }
                runCatching { Toast.makeText(ctx, AppText.t("Téléchargements automatiques supprimés"), Toast.LENGTH_SHORT).show() }
            }
        }, primary = true, destructive = true) },
        dismissButton = { KetTextButton("Annuler", { asking = false }) })
}

private fun usageLabel(ctx: android.content.Context, usage: SmartOffline.Usage): String {
    if (usage.count == 0) return AppText.t("Rien pour l'instant")
    val count = AppText.t(if (usage.count == 1) "1 titre" else "${usage.count} titres")
    return count + " · " + android.text.format.Formatter.formatShortFileSize(ctx, usage.bytes)
}

/** « 2 Go » or « 25 % du stockage libre »: the units are words of the interface, translated at display. */
internal fun limitLabel(gb: Int, percent: Int): String =
    if (gb > 0) AppText.t("{0} Go").replace("{0}", gb.toString())
    else AppText.t("Ne pas dépasser {0} % du stockage libre").replace("{0}", SmartOfflinePlan.clampPercent(percent).toString())

/** Option ids: a number of GB as itself, a share of the free storage as 100 + its percent. */
private fun limitChoice(onPick: (Int, Int?) -> Unit): ChoiceSpec {
    val gb = AppSettings.smartOfflineGb.value
    val selected = (if (gb > 0) gb else 100 + SmartOfflinePlan.clampPercent(AppSettings.smartOfflinePercent.value)).toString()
    val options = SmartOfflinePlan.GIGABYTES.map { ChoiceOption(it.toString(), limitLabel(it, 0), if (it == SmartOfflinePlan.DEFAULT_GB) "Par défaut" else "") } +
        SmartOfflinePlan.PERCENTS.map { ChoiceOption((100 + it).toString(), limitLabel(0, it)) }
    return ChoiceSpec("Espace maximum",
        "Ketvoice laisse toujours au moins 1 Go libre sur l'appareil.", selected, options) { id ->
        val value = id.toIntOrNull() ?: return@ChoiceSpec
        if (value > 100) onPick(0, value - 100) else onPick(value, null)
    }
}
