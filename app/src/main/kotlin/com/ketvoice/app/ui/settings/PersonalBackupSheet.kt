package com.ketvoice.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.PersonalBackup
import com.ketvoice.app.data.PersonalBackupData
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PersonalBackupSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme = LocalTheme.current
    var preview by remember { mutableStateOf<PersonalBackupData?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val bytes = PersonalBackup.encode(PersonalBackup.collect(context))
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                }
                message = "Sauvegarde enregistrée"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Impossible d'enregistrer la sauvegarde" }
            finally { busy = false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                preview = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { PersonalBackup.decode(PersonalBackup.readLimited(it)) }
                }
                message = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { preview = null; message = "Sauvegarde illisible ou incompatible" }
            finally { busy = false }
        }
    }
    SettingsSheet(onDismiss = { if (!busy) onClose() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSheetHeader("Sauvegarde personnelle", "Les comptes, clés, réglages sensibles et fichiers multimédias ne sont pas inclus.") {
                if (!busy) onClose()
            }
            val data = preview
            if (data == null) {
                SettingsAction("Exporter", { export.launch("ketvoice-backup.json") }, enabled = !busy, primary = true,
                    modifier = Modifier.fillMaxWidth())
                SettingsAction("Importer", { import.launch(arrayOf("application/json", "text/plain")) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
            } else {
                PersonalBackupPreview(data, busy, onImport = {
                    scope.launch {
                        busy = true
                        try {
                            PersonalBackup.merge(context, data)
                            preview = null
                            message = "Sauvegarde restaurée"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message = "Import incomplet : vos données existantes sont conservées" }
                        finally { busy = false }
                    }
                }, onCancel = { preview = null })
            }
            if (message.isNotBlank()) Text(message, color = theme.secondary, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
internal fun ColumnScope.PersonalBackupPreview(data: PersonalBackupData, busy: Boolean,
                                                onImport: () -> Unit, onCancel: () -> Unit) {
    val theme = LocalTheme.current
    Text("Aperçu de la sauvegarde", color = theme.onBackground, fontSize = 17.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Playlists", color = theme.secondary)
        Text(data.playlists.size.toString(), localize = false, color = theme.onBackground)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Favoris", color = theme.secondary)
        Text(data.favorites.size.toString(), localize = false, color = theme.onBackground)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Réglages", color = theme.secondary)
        Text(data.settings.size.toString(), localize = false, color = theme.onBackground)
    }
    Text("Les playlists existantes sont conservées ; les favoris et l'historique sont fusionnés. Les réglages sauvegardés remplacent ceux de cet appareil.",
        color = theme.secondary, fontSize = 13.sp)
    Text("Les fichiers locaux devront être reconnectés sur un autre appareil.", color = theme.secondary, fontSize = 13.sp)
    SettingsAction("Importer", onImport, enabled = !busy, primary = true, modifier = Modifier.fillMaxWidth())
    SettingsAction("Annuler", onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth())
}
