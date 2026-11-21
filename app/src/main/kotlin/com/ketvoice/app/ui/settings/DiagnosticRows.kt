package com.ketvoice.app.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.util.Diagnostics
import kotlinx.coroutines.launch

/** 8.2 « Diagnostic intégré », in À propos: the opt-in switch and, while it is on, the share row. */
@Composable
internal fun ColumnScope.DiagnosticRows() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    SToggle(
        KetIcons.Info, "Diagnostic intégré",
        "Note sur cet appareil les saccades, le démarrage, le délai avant le son, les chargements et la mémoire. Rien n'est envoyé, aucune donnée personnelle",
        AppSettings.diagnostics.value
    ) { AppSettings.diagnostics.value = it; Diagnostics.switched(); SettingsStore.save() }
    if (AppSettings.diagnostics.value) SRow(KetIcons.Share, "Partager le diagnostic", sub = "Par le menu de partage, seulement quand vous le choisissez") {
        if (Diagnostics.hasLog(ctx)) Diagnostics.share(ctx)
        else scope.launch { com.ketvoice.app.ui.common.TrackActionFeedback.snackbar.showSnackbar(AppText.t("Rien n'est encore enregistré")) }
    }
}
