package com.ketvoice.app.ui.airket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.airket.AirKet
import com.ketvoice.app.airket.AirKetCode
import com.ketvoice.app.airket.AirKetFormat
import com.ketvoice.app.airket.AirKetFound
import com.ketvoice.app.airket.AirKetOffer
import com.ketvoice.app.airket.AirKetReceiveUi
import com.ketvoice.app.airket.AirKetSendUi
import com.ketvoice.app.airket.AirKetSummary
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetProgressBar
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.LiquidGlassSwitch
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.settings.SettingsAction
import com.ketvoice.app.ui.settings.SettingsSheet
import com.ketvoice.app.ui.settings.SettingsSheetHeader
import com.ketvoice.app.ui.together.QrImage

/** The sheet of Air Ket (7.0), placed once at the root beside Ketogether's. It opens from Settings, onboarding or a QR code. */
@Composable
fun AirKetHost() {
    val mode = AirKet.sheet.value ?: return
    val context = LocalContext.current
    val send by AirKet.send.collectAsState()
    val receive by AirKet.receive.collectAsState()
    val close = {
        when (mode) {
            AirKet.Mode.Send -> if (send.phase !in setOf(AirKetSendUi.Phase.Paired, AirKetSendUi.Phase.Sending)) AirKet.stopSending()
            AirKet.Mode.Receive -> if (receive.phase !in setOf(AirKetReceiveUi.Phase.Receiving, AirKetReceiveUi.Phase.Installing)) AirKet.closeReceive()
            AirKet.Mode.Menu -> Unit
        }
        AirKet.sheet.value = null
    }
    SettingsSheet(onDismiss = close) {
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight()).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (mode) {
                AirKet.Mode.Menu -> AirKetMenu(onClose = close,
                    onSend = { AirKet.sheet.value = AirKet.Mode.Send; AirKet.startSending(context) },
                    onReceive = { AirKet.sheet.value = AirKet.Mode.Receive; AirKet.startSearching(context) })
                AirKet.Mode.Send -> AirKetSendContent(send, onClose = close, onStop = { AirKet.cancel(); AirKet.stopSending(); AirKet.sheet.value = null },
                    onRestart = { AirKet.stopSending(); AirKet.startSending(context) })
                AirKet.Mode.Receive -> AirKetReceiveContent(receive, onClose = close)
            }
        }
    }
}

@Composable
internal fun ColumnScope.AirKetMenu(onClose: () -> Unit, onSend: () -> Unit, onReceive: () -> Unit) {
    val theme = LocalTheme.current
    SettingsSheetHeader("Air Ket", "Transférez toute votre bibliothèque vers un autre téléphone sur le même Wi-Fi, directement, sans compte ni serveur.", onClose)
    SettingsAction("Envoyer vers un autre téléphone", onSend, primary = true, modifier = Modifier.fillMaxWidth().testTag("airket-send"))
    SettingsAction("Recevoir via Air Ket", onReceive, modifier = Modifier.fillMaxWidth().testTag("airket-receive"))
    Text("Rien ne passe par Internet : les deux téléphones se parlent sur votre Wi-Fi, avec un code à 6 chiffres que vous seul connaissez.",
        color = theme.secondary, style = Ket27.Type.footnote)
}

// ---------------------------------------------------------------------------------------------- the old phone

@Composable
internal fun ColumnScope.AirKetSendContent(ui: AirKetSendUi, onClose: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit) {
    val theme = LocalTheme.current
    SettingsSheetHeader("Air Ket · envoyer", "Sur l'autre téléphone : Réglages, Air Ket, Recevoir via Air Ket.", onClose)
    when (ui.phase) {
        AirKetSendUi.Phase.Idle, AirKetSendUi.Phase.Preparing -> Row(verticalAlignment = Alignment.CenterVertically) {
            ActivitySpinner(); Spacer(Modifier.width(12.dp))
            Text("Préparation de la sauvegarde…", color = theme.secondary, style = Ket27.Type.subheadline)
        }
        AirKetSendUi.Phase.NoWifi -> Notice("Connectez les deux téléphones au même Wi-Fi privé.")
        else -> SendCode(ui)
    }
    SendStatus(ui)
    when (ui.phase) {
        AirKetSendUi.Phase.Expired, AirKetSendUi.Phase.Locked, AirKetSendUi.Phase.NoWifi ->
            SettingsAction("Nouveau code", onRestart, primary = true, modifier = Modifier.fillMaxWidth())
        AirKetSendUi.Phase.Done -> SettingsAction("Terminé", onStop, primary = true, modifier = Modifier.fillMaxWidth())
        else -> SettingsAction(if (ui.phase == AirKetSendUi.Phase.Sending) "Annuler l'envoi" else "Fermer", onStop, modifier = Modifier.fillMaxWidth().testTag("airket-stop"))
    }
}

@Composable
private fun SendCode(ui: AirKetSendUi) {
    val theme = LocalTheme.current
    val live = ui.phase in setOf(AirKetSendUi.Phase.Waiting, AirKetSendUi.Phase.Paired, AirKetSendUi.Phase.Sending, AirKetSendUi.Phase.Failed)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(theme.onBackground.copy(alpha = .06f)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Code d'appairage", color = theme.secondary, style = Ket27.Type.footnote)
        Text(AirKetCode.display(ui.code), localize = false, color = theme.onBackground.copy(alpha = if (live) 1f else .35f), fontSize = 40.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 4.sp, modifier = Modifier.testTag("airket-code"))
        if (live && ui.link.isNotBlank()) QrImage(ui.link, Modifier.size(196.dp).testTag("airket-qr"))
        if (ui.phase == AirKetSendUi.Phase.Waiting) {
            Text(AppText.t("Expire dans") + " " + AirKetFormat.clock(ui.secondsLeft), localize = false, color = theme.secondary, style = Ket27.Type.footnote)
        }
    }
}

@Composable
private fun SendStatus(ui: AirKetSendUi) {
    val theme = LocalTheme.current
    val line = when (ui.phase) {
        AirKetSendUi.Phase.Waiting -> "En attente de l'autre téléphone…"
        AirKetSendUi.Phase.Paired -> "Connecté · l'autre téléphone choisit ce qu'il reçoit"
        AirKetSendUi.Phase.Sending -> "Envoi en cours"
        AirKetSendUi.Phase.Done -> "Envoi terminé"
        AirKetSendUi.Phase.Expired -> "Le code a expiré."
        AirKetSendUi.Phase.Locked -> "Trop de codes incorrects : l'envoi est fermé."
        AirKetSendUi.Phase.Failed -> "Transfert interrompu · le code reste valable pour reprendre"
        else -> ""
    }
    if (line.isNotBlank()) Text(line, color = theme.onBackground, style = Ket27.Type.subheadline)
    if (ui.phase == AirKetSendUi.Phase.Sending) {
        KetProgressBar(null, Modifier.fillMaxWidth())
        Text(AirKetFormat.size(ui.sentBytes) + " · " + AirKetFormat.speed(ui.bytesPerSecond), localize = false, color = theme.secondary, style = Ket27.Type.footnote)
    }
    if (ui.titles > 0 && ui.phase == AirKetSendUi.Phase.Waiting) {
        Text(AppText.t("Téléchargements disponibles") + " : " + ui.titles + " · " + AirKetFormat.size(ui.downloadBytes), localize = false,
            color = theme.secondary, style = Ket27.Type.footnote)
    }
    if (ui.wrongTries > 0 && ui.phase == AirKetSendUi.Phase.Waiting) {
        Text(AppText.t("Codes incorrects") + " : " + ui.wrongTries + " / " + AirKetCode.MAX_WRONG, localize = false,
            color = theme.secondary, style = Ket27.Type.footnote)
    }
}

@Composable
private fun Notice(text: String) {
    val theme = LocalTheme.current
    Text(text, color = theme.secondary, style = Ket27.Type.subheadline)
}

// ---------------------------------------------------------------------------------------------- the new phone

@Composable
internal fun ColumnScope.AirKetReceiveContent(ui: AirKetReceiveUi, onClose: () -> Unit) {
    val context = LocalContext.current
    var code by rememberSaveable(ui.prefilledCode) { mutableStateOf(ui.prefilledCode) }
    SettingsSheetHeader("Air Ket · recevoir", "Sur l'ancien téléphone : Réglages, Air Ket, Envoyer.", onClose)
    when (ui.phase) {
        AirKetReceiveUi.Phase.Idle, AirKetReceiveUi.Phase.Searching -> FindSender(ui, code, { code = it.filter(Char::isDigit).take(AirKetCode.DIGITS) }) {
            AirKet.connectTo(it, code)
        }
        AirKetReceiveUi.Phase.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
            ActivitySpinner(); Spacer(Modifier.width(12.dp)); Text("Connexion…", color = LocalTheme.current.secondary)
        }
        AirKetReceiveUi.Phase.Offer -> ui.offer?.let { Offer(it, onClose) }
        AirKetReceiveUi.Phase.Receiving, AirKetReceiveUi.Phase.Installing -> Receiving(ui)
        AirKetReceiveUi.Phase.Summary -> ui.summary?.let { AirKetSummaryContent(it) { AirKet.closeReceive(); AirKet.sheet.value = null } }
        AirKetReceiveUi.Phase.Failed -> Failure(ui.error, onRetry = { AirKet.startSearching(context) })
    }
}

@Composable
private fun FindSender(ui: AirKetReceiveUi, code: String, onCode: (String) -> Unit, onPick: (AirKetFound) -> Unit) {
    val theme = LocalTheme.current
    KetTextField(code, onCode, "Code à 6 chiffres", modifier = Modifier.fillMaxWidth().testTag("airket-code-field"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    Text("Appareils trouvés sur le Wi-Fi", color = theme.secondary, style = Ket27.Type.footnote)
    if (ui.found.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
        ActivitySpinner(); Spacer(Modifier.width(12.dp))
        Text("Recherche des appareils sur le Wi-Fi…", color = theme.secondary, style = Ket27.Type.subheadline)
    }
    ui.found.forEach { found ->
        val ready = AirKetCode.normalize(code) != null
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.onBackground.copy(alpha = .06f))
            .clickable(enabled = ready, role = Role.Button) { onPick(found) }.padding(14.dp).testTag("airket-found"),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Wifi, null, tint = theme.accent, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(found.name, localize = false, color = theme.onBackground, style = Ket27.Type.headline)
                Text(if (ready) "Se connecter" else "Entrez d'abord le code", color = theme.secondary, style = Ket27.Type.footnote)
            }
        }
    }
}

@Composable
private fun Offer(offer: AirKetOffer, onClose: () -> Unit) {
    val theme = LocalTheme.current
    var withDownloads by rememberSaveable { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    Text(AppText.t("Depuis") + " " + offer.senderName, localize = false, color = theme.onBackground, style = Ket27.Type.title3)
    OfferRow("Sauvegarde personnelle", AirKetFormat.size(offer.backupBytes))
    Text("Réglages, favoris, playlists, artistes, historique, Replay et Wrapped, paroles personnelles, points de mix Ketmix.",
        color = theme.secondary, style = Ket27.Type.footnote)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Avec les téléchargements", color = theme.onBackground, style = Ket27.Type.body)
            Text(AppText.t("Titres") + " : " + offer.titles + " · " + AirKetFormat.size(offer.downloadBytes), localize = false,
                color = theme.secondary, style = Ket27.Type.footnote)
        }
        LiquidGlassSwitch(withDownloads, { withDownloads = it }, modifier = Modifier.testTag("airket-downloads"))
    }
    if (withDownloads && offer.heldBytes > 0) Text(AppText.t("Déjà reçu") + " : " + AirKetFormat.size(offer.heldBytes), localize = false,
        color = theme.secondary, style = Ket27.Type.footnote)
    if (offer.leftOutDash > 0) Text(AppText.t("Titres hors ligne en streaming, non transférables") + " : " + offer.leftOutDash, localize = false,
        color = theme.secondary, style = Ket27.Type.footnote)
    SettingsAction("Recevoir", { if (offer.localHasData) asking = true else AirKet.accept(withDownloads, replace = false) }, primary = true,
        modifier = Modifier.fillMaxWidth().testTag("airket-accept"))
    SettingsAction("Annuler", { AirKet.closeReceive(); onClose() }, modifier = Modifier.fillMaxWidth())
    if (asking) ReplaceOrMerge(onChoose = { replace -> asking = false; AirKet.accept(withDownloads, replace) }, onCancel = { asking = false })
}

@Composable
private fun OfferRow(label: String, value: String) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = theme.secondary); Text(value, localize = false, color = theme.onBackground)
    }
}

/** Asked once, before anything on this phone is overwritten. */
@Composable
internal fun ReplaceOrMerge(onChoose: (replace: Boolean) -> Unit, onCancel: () -> Unit) {
    val theme = LocalTheme.current
    AppAlertDialog(onDismissRequest = onCancel,
        title = { Text("Remplacer ou fusionner ?") },
        text = { Text("Ce téléphone a déjà des playlists ou des favoris. « Fusionner » garde ce qui est ici et ajoute le reste ; « Remplacer » les remplace par ceux de l'autre téléphone.",
            color = theme.secondary) },
        confirmButton = { KetTextButton("Fusionner", { onChoose(false) }, primary = true) },
        dismissButton = { Row { KetTextButton("Annuler", onCancel); KetTextButton("Remplacer", { onChoose(true) }, destructive = true) } })
}

@Composable
private fun Receiving(ui: AirKetReceiveUi) {
    val theme = LocalTheme.current
    Text("Réception en cours", color = theme.onBackground, style = Ket27.Type.title3)
    val total = ui.totalBytes
    KetProgressBar(if (total > 0) ({ ui.doneBytes.toFloat() / total }) else null, Modifier.fillMaxWidth().testTag("airket-progress"))
    Text(ui.label, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1)
    if (total > 0) Text(AirKetFormat.size(ui.doneBytes) + " / " + AirKetFormat.size(total) + " · " + AirKetFormat.speed(ui.bytesPerSecond),
        localize = false, color = theme.secondary, style = Ket27.Type.footnote)
    SettingsAction("Annuler", { AirKet.cancel() }, modifier = Modifier.fillMaxWidth().testTag("airket-cancel"))
}

@Composable
private fun Failure(error: AirKetReceiveUi.Error?, onRetry: () -> Unit) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val text = when (error) {
        AirKetReceiveUi.Error.NoWifi -> "Connectez ce téléphone à un Wi-Fi privé."
        AirKetReceiveUi.Error.WrongCode -> "Code incorrect."
        AirKetReceiveUi.Error.Closed -> "Cet appareil n'attend plus : le code a expiré ou l'envoi est fermé."
        AirKetReceiveUi.Error.NoRoom -> "Pas assez d'espace libre pour les téléchargements."
        AirKetReceiveUi.Error.Interrupted -> "Transfert interrompu. Ce qui a été vérifié est conservé."
        AirKetReceiveUi.Error.Cancelled -> "Transfert annulé. Ce qui a été vérifié est conservé."
        else -> "Le transfert a échoué."
    }
    Text(text, color = theme.onBackground, style = Ket27.Type.subheadline, modifier = Modifier.testTag("airket-error"))
    if (error == AirKetReceiveUi.Error.Interrupted || error == AirKetReceiveUi.Error.Cancelled)
        SettingsAction("Reprendre", { AirKet.resume() }, primary = true, modifier = Modifier.fillMaxWidth())
    SettingsAction("Réessayer", onRetry, primary = error != AirKetReceiveUi.Error.Interrupted && error != AirKetReceiveUi.Error.Cancelled,
        modifier = Modifier.fillMaxWidth())
    SettingsAction("Effacer ce qui a été reçu", { AirKet.discardStaging(context) }, modifier = Modifier.fillMaxWidth())
}

// ---------------------------------------------------------------------------------------------- the end

@Composable
internal fun ColumnScope.AirKetSummaryContent(summary: AirKetSummary, onDone: () -> Unit) {
    val theme = LocalTheme.current
    Text("Transfert terminé", color = theme.onBackground, style = Ket27.Type.title3, modifier = Modifier.testTag("airket-summary"))
    OfferRow("Playlists", summary.playlists.toString())
    OfferRow("Favoris", summary.favourites.toString())
    if (summary.withDownloads) {
        OfferRow("Titres reçus", summary.titles.toString())
        OfferRow("Fichiers", summary.files.toString())
        if (summary.failed > 0) OfferRow("Fichiers non reçus", summary.failed.toString())
    }
    OfferRow("Données reçues", AirKetFormat.size(summary.bytes))
    OfferRow("Durée", AirKetFormat.clock(summary.seconds.toInt()))
    OfferRow("Playlists et favoris", AppText.t(if (summary.replaced) "Remplacés" else "Fusionnés"))
    if (summary.leftOutDash > 0) Text(AppText.t("Titres hors ligne en streaming, non transférables") + " : " + summary.leftOutDash, localize = false,
        color = theme.secondary, style = Ket27.Type.footnote)
    Text("Laissé de côté : la session de votre compte, Discord et YouTube Music, les clés, les sources personnalisées, les fichiers locaux, les caches et les modèles de séparation (à retélécharger depuis les Réglages).",
        color = theme.secondary, style = Ket27.Type.footnote)
    SettingsAction("Terminé", onDone, primary = true, modifier = Modifier.fillMaxWidth())
}
