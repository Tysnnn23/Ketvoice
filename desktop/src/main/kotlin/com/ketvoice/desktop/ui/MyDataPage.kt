package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.data.AppText
import com.ketvoice.desktop.connect.MyData
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.launch

/*
 * « Mes données »: what leaves this PC and why (no usage statistics, no crash reports: logs and crash files stay in the
 * data folder), the export of every row of the account as JSON, and the deletion of the account after typing SUPPRIMER.
 */

/** What Ketvoice for Windows sends, and only when it is used: the zero-telemetry statement, row by row. */
internal val WHAT_LEAVES = listOf(
    "Recherche et lecture : vos recherches et les titres lus vont aux sources de musique.",
    "Compte : votre bibliothèque, vos réglages et ce que vous écoutez, seulement si vous êtes connecté.",
    "Discord : le titre en cours, à l'application Discord de ce PC, si le statut est activé.",
    "Last.fm et ListenBrainz : vos écoutes, seulement si vous les avez liés.",
    "Mises à jour et modèles : téléchargés depuis GitHub, sans rien envoyer d'autre que la version.",
)

@Composable
internal fun MyDataPage() {
    val account by AccountSession.state.collectAsState()
    Page("Mes données", "my-data") {
        item("h:telemetry") { SectionHeader("Zéro télémétrie") }
        item("telemetry") {
            SettingsCard {
                Note("Ketvoice n'envoie aucune statistique d'usage ni rapport de plantage. Les journaux restent sur ce PC.")
                WHAT_LEAVES.forEach { Hairline(); Note(it) }
            }
        }
        item("h:export") { SectionHeader("Exporter mes données") }
        item("export") { SettingsCard { ExportRow(account.signedIn) } }
        item("h:delete") { SectionHeader("Supprimer mon compte") }
        item("delete") { SettingsCard { DeleteAccount(account.signedIn) } }
    }
}

@Composable
private fun ExportRow(signedIn: Boolean) {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MyData.Result?>(null) }
    Note("Un fichier JSON avec tout ce que votre compte contient : profil, bibliothèque, playlists, appareils, abonnements.")
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CapsuleButton("Exporter", null, {
            if (busy || !signedIn) return@CapsuleButton
            busy = true
            scope.launch { result = MyData.export(MyData.defaultFile()); busy = false }
        }, filled = true, accent = ket.accent, modifier = Modifier.alpha(if (signedIn) 1f else .5f).testTag("my-data-export"))
        Spacer(Modifier.width(12.dp))
        if (busy) Spinner()
        when (val r = result) {
            is MyData.Result.Ok -> KText(AppText.t("Enregistré dans") + " " + r.file.absolutePath, Ket27Desk.Type.footnote,
                Modifier.weight(1f).clickable { runCatching { java.awt.Desktop.getDesktop().open(r.file.parentFile) } }.testTag("my-data-saved"),
                color = ket.secondary, translate = false, maxLines = 2)
            is MyData.Result.Failed -> KText(r.message, Ket27Desk.Type.footnote, Modifier.weight(1f), color = destructiveRed, maxLines = 2)
            null -> if (!signedIn) KText("Connectez-vous à votre compte Ketvoice.", Ket27Desk.Type.footnote, color = ket.secondary)
        }
    }
}

@Composable
private fun DeleteAccount(signedIn: Boolean) {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Note("Votre compte, votre profil, votre bibliothèque synchronisée, vos playlists partagées et vos liens Last.fm sont effacés du serveur. C'est définitif.")
    if (!signedIn) { Note("Connectez-vous à votre compte Ketvoice."); return }
    Hairline()
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        KText("Tapez SUPPRIMER pour confirmer", Ket27Desk.Type.subheadline, Modifier.width(260.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(typed, { typed = it.take(20) }, Modifier.fillMaxWidth().textEntry().testTag("delete-confirm"), singleLine = true,
                textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
                cursorBrush = SolidColor(ket.accent))
        }
    }
    Hairline()
    val ready = MyData.confirmed(typed) && !busy
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(destructiveRed.copy(alpha = if (ready) 1f else .35f))
            .clickable(enabled = ready) {
                busy = true; error = null
                scope.launch { (MyData.deleteAccount(typed) as? MyData.Result.Failed)?.let { error = it.message }; busy = false }
            }.padding(horizontal = 20.dp, vertical = 10.dp).testTag("delete-account")) {
            KText("Supprimer mon compte", Ket27Desk.Type.subheadline, color = androidx.compose.ui.graphics.Color.White, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        if (busy) Spinner()
        error?.let { KText(it, Ket27Desk.Type.footnote, color = destructiveRed, maxLines = 2) }
    }
}
