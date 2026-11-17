package com.ketvoice.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.CanvasCache
import com.ketvoice.app.data.CanvasCachePlan
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Canvas cache in Settings (6.2.3): the preload on Wi-Fi, what the Canvas files use (split into what downloaded
 * titles need and the rest), the limit and « Vider le cache des Canvas ». [onChoice] opens the settings' picker.
 */
@Composable
internal fun ColumnScope.CanvasCacheRows(onChoice: (ChoiceSpec) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<CanvasCachePlan.Usage?>(null) }
    val refresh = remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh.intValue) { usage = withContext(Dispatchers.IO) { runCatching { CanvasCache.usage(ctx) }.getOrNull() } }

    SToggle(KetIcons.Wifi, "Précharger en Wi-Fi",
        "Charge à l'avance le Canvas de tes favoris et de tes playlists, en Wi-Fi seulement, application ouverte ou téléphone en charge.",
        AppSettings.canvasPreloadWifi.value) { AppSettings.canvasPreloadWifi.value = it; SettingsStore.save() }
    SRow(KetIcons.Storage, "Espace utilisé par les Canvas", sub = usageSub(usage),
        value = usage?.let { CanvasCachePlan.sizeLabel(it.totalBytes) }.orEmpty())
    SRow(KetIcons.Sliders, "Limite du cache des Canvas",
        sub = "Les moins récemment utilisés partent en premier, jamais ceux de tes téléchargements.",
        value = CanvasCachePlan.limitLabel(CanvasCachePlan.clampMb(AppSettings.canvasCacheMb.value))) {
        onChoice(limitChoice { mb ->
            AppSettings.canvasCacheMb.value = mb; SettingsStore.save()
            scope.launch(Dispatchers.IO) { runCatching { CanvasCache.applyLimit(ctx, mb) }; refresh.intValue++ }
        })
    }
    ClearCanvasRow(refresh)
}

private fun usageSub(usage: CanvasCachePlan.Usage?): String {
    if (usage == null) return AppText.t("Calcul en cours…")
    return AppText.t("Sur ce téléphone pour tes téléchargements : {0} · le reste : {1}")
        .replace("{0}", CanvasCachePlan.sizeLabel(usage.keptBytes)).replace("{1}", CanvasCachePlan.sizeLabel(usage.otherBytes))
}

private fun limitChoice(onPick: (Int) -> Unit) = ChoiceSpec(
    "Limite du cache des Canvas",
    "Les moins récemment utilisés partent en premier, jamais ceux de tes téléchargements.",
    CanvasCachePlan.clampMb(AppSettings.canvasCacheMb.value).toString(),
    CanvasCachePlan.LIMITS_MB.map { mb ->
        ChoiceOption(mb.toString(), CanvasCachePlan.limitLabel(mb), if (mb == CanvasCachePlan.DEFAULT_MB) "Par défaut" else "")
    }) { id -> id.toIntOrNull()?.let(onPick) }

@Composable
private fun ColumnScope.ClearCanvasRow(refresh: androidx.compose.runtime.MutableIntState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    SRow(KetIcons.Trash, "Vider le cache des Canvas", sub = "Garde ceux de tes titres téléchargés") { asking = true }
    if (!asking) return
    AppAlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Vider le cache des Canvas ?") },
        text = { Text("Les Canvas dont aucun titre téléchargé n'a besoin sont supprimés. Ils reviennent quand tu les regardes.") },
        confirmButton = { KetTextButton("Vider", {
            asking = false
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { CanvasCache.clear(ctx) } }
                refresh.intValue++
                runCatching { Toast.makeText(ctx, AppText.t("Cache des Canvas vidé"), Toast.LENGTH_SHORT).show() }
            }
        }, primary = true, destructive = true) },
        dismissButton = { KetTextButton("Annuler", { asking = false }) })
}
