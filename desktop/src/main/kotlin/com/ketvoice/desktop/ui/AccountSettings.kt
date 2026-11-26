package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.Profiles
import com.ketvoice.app.data.AppText
import com.ketvoice.app.sync.LibrarySync
import com.ketvoice.app.sync.SyncProblem
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.library.LocalFolders
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* ── Compte Ketvoice: sign-in (e-mail or Discord), sync, sign-out ── */

internal fun LazyListScope.accountSettings() {
    item("account-header") { SectionHeader("Compte Ketvoice") }
    item("account") { AccountCard() }
}

@Composable
private fun AccountCard() {
    val account by AccountSession.state.collectAsState()
    SettingsCard { if (account.signedIn) SignedIn(account.email) else SignInForm(account.message) }
}

@Composable
private fun SignedIn(email: String) {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    val profile by Profiles.profile.collectAsState()
    val sync by LibrarySync.status.collectAsState()
    var confirmOut by remember { mutableStateOf(false) }
    val name = profile.displayName.ifBlank { email.substringBefore('@') }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("account-signed-in"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(ket.accent), contentAlignment = Alignment.Center) {
            KText(name.take(1).uppercase(), Ket27Desk.Type.headline, color = androidx.compose.ui.graphics.Color.White, weight = FontWeight.SemiBold, translate = false)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            KText(name, Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
            KText(email, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        }
        CapsuleButton("Se déconnecter", null, { confirmOut = true }, filled = false, accent = ket.accent, modifier = Modifier.testTag("account-sign-out"))
    }
    Hairline()
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        KText(syncText(sync.running, sync.problem, sync.lastSuccess), Ket27Desk.Type.subheadline, Modifier.weight(1f).testTag("account-sync-status"),
            color = if (sync.problem == SyncProblem.NONE) ket.label else ket.secondary, translate = false)
        if (sync.running) Spinner() else KetTextLink("Synchroniser", "account-sync") { scope.launch { runCatching { LibrarySync.syncNow() } } }
    }
    Hairline()
    KText("Vos favoris, playlists, écoutes récentes, statistiques et réglages sur tous vos appareils.", Ket27Desk.Type.footnote,
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = ket.secondary, maxLines = 2)
    if (confirmOut) ConfirmDialog(AppText.t("Se déconnecter"),
        AppText.t("Votre bibliothèque reste sur cet appareil. Elle ne se synchronise plus jusqu'à la prochaine connexion."),
        "Se déconnecter", onConfirm = { scope.launch { runCatching { AccountSession.signOut() } } }, onDismiss = { confirmOut = false })
}

internal fun syncText(running: Boolean, problem: SyncProblem, lastSuccess: Long): String = when {
    running -> AppText.t("Synchronisation…")
    problem == SyncProblem.OFFLINE -> AppText.t("Hors ligne · vos changements partiront plus tard")
    problem == SyncProblem.SIGNED_OUT -> AppText.t("Session expirée · reconnectez-vous")
    problem == SyncProblem.SERVER -> AppText.t("Erreur du serveur · nouvel essai bientôt")
    lastSuccess > 0 -> AppText.t("Synchronisé") + " · " +
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(lastSuccess).atZone(ZoneId.systemDefault()))
    else -> AppText.t("Synchronisation en attente")
}

@Composable
private fun SignInForm(message: String?) {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(message) }
    var discord by remember { mutableStateOf<Job?>(null) }
    val waiting by AccountSession.discordWaiting.collectAsState()
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) error = e.message ?: "Connexion impossible pour le moment."
            } finally { busy = false }
        }
    }
    fun submit() = run {
        if (creating) AccountSession.signUp(name, email, password) else AccountSession.signIn(email, password)
    }
    Column(Modifier.fillMaxWidth().testTag("account-form")) {
        KText("Vos favoris, playlists, écoutes récentes, statistiques et réglages sur tous vos appareils.", Ket27Desk.Type.subheadline,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), maxLines = 2)
        KText("Rien n'est envoyé tant que vous n'êtes pas connecté.", Ket27Desk.Type.footnote,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp), color = ket.secondary, maxLines = 2)
        Hairline()
        if (creating) { Field("Nom affiché", name, "account-name") { name = it }; Hairline() }
        Field("E-mail", email, "account-email", keyboard = KeyboardType.Email, hint = EMAIL_HINT) { email = it }
        Hairline()
        Field("Mot de passe", password, "account-password", secret = true, onDone = ::submit) { password = it }
        Hairline()
        error?.let {
            KText(it, Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).testTag("account-error"),
                color = destructiveRed, maxLines = 3)
        }
        if (waiting) {
            Row(Modifier.fillMaxWidth().padding(16.dp).testTag("account-discord-waiting"), verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Spacer(Modifier.width(10.dp))
                KText("Terminez la connexion dans votre navigateur.", Ket27Desk.Type.subheadline, Modifier.weight(1f))
                KetTextLink("Annuler", "account-discord-cancel") { discord?.cancel() }
            }
        } else Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CapsuleButton(if (creating) "Créer un compte" else "Se connecter", null, ::submit, filled = true, accent = ket.accent,
                modifier = Modifier.testTag(if (creating) "account-sign-up" else "account-sign-in"))
            CapsuleButton("Continuer avec Discord", null, {
                error = null
                discord = scope.launch {
                    try { AccountSession.signInWithDiscord() } catch (e: Exception) {
                        if (e !is kotlinx.coroutines.CancellationException) error = e.message ?: "Connexion impossible pour le moment."
                    }
                }
            }, filled = false, accent = ket.accent, modifier = Modifier.testTag("account-discord"))
            Spacer(Modifier.weight(1f))
            if (busy) Spinner()
            KetTextLink(if (creating) "Se connecter" else "Créer un compte", "account-mode") { creating = !creating; error = null }
        }
    }
}

@Composable
private fun Field(label: String, value: String, tag: String, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
                  hint: String = "Requis", onDone: (() -> Unit)? = null, onChange: (String) -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.subheadline, Modifier.width(150.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) KText(hint, Ket27Desk.Type.subheadline, color = ket.tertiary, translate = hint != EMAIL_HINT)
        BasicTextField(value, { onChange(it.take(200)) }, Modifier.fillMaxWidth().textEntry().testTag(tag), singleLine = true,
            textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
            cursorBrush = SolidColor(ket.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }))
        }
    }
}

private const val EMAIL_HINT = "nom@exemple.com"

/** A text action in the accent (Ket 27's KetTextButton, desktop size). */
@Composable
internal fun KetTextLink(label: String, tag: String, onClick: () -> Unit) {
    val ket = LocalKet.current
    Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp).testTag(tag)) {
        KText(label, Ket27Desk.Type.subheadline, color = ket.accent, weight = FontWeight.Medium)
    }
}

/* ── Musique locale: folders on this computer ── */

internal fun LazyListScope.localMusicSettings() {
    item("local-header") { SectionHeader("Musique locale") }
    item("local") { LocalMusicCard() }
}

@Composable
private fun LocalMusicCard() {
    val ket = LocalKet.current
    val folders by LocalFolders.folders.collectAsState()
    val tracks by LocalFolders.tracks.collectAsState()
    val scanning by LocalFolders.scanning.collectAsState()
    SettingsCard {
        folders.forEach { folder ->
            Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 8.dp).testTag("local-folder"), verticalAlignment = Alignment.CenterVertically) {
                Icon(KetIcons.Folder, null, Modifier.size(17.dp), tint = ket.accent)
                Spacer(Modifier.width(10.dp))
                KText(folder, Ket27Desk.Type.subheadline, Modifier.weight(1f), translate = false)
                KetTextLink("Retirer", "local-remove") { LocalFolders.removeFolder(folder) }
            }
            Hairline()
        }
        Row(Modifier.fillMaxWidth().height(44.dp).clickable { pickFolder()?.let(LocalFolders::addFolder) }.padding(horizontal = 16.dp).testTag("local-add"),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Plus, null, Modifier.size(17.dp), tint = ket.accent)
            Spacer(Modifier.width(10.dp))
            KText("Choisir un dossier", Ket27Desk.Type.subheadline, color = ket.accent)
        }
        if (folders.isNotEmpty()) {
            Hairline()
            Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                KText(if (scanning) AppText.t("Analyse des fichiers…") else AppText.t("{0} titres sur cet ordinateur").replace("{0}", tracks.size.toString()),
                    Ket27Desk.Type.subheadline, Modifier.weight(1f).testTag("local-count"), color = ket.secondary, translate = false)
                if (scanning) Spinner() else KetTextLink("Actualiser", "local-rescan") { LocalFolders.rescan() }
            }
        }
    }
    KText("Les titres de ces dossiers apparaissent dans Titres, Albums et Artistes. Ils restent sur cet ordinateur.",
        Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), color = ket.secondary, maxLines = 2)
}

/** The system's folder chooser (Swing, in the look of Windows). */
private fun pickFolder(): String? = runCatching {
    runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
    val chooser = javax.swing.JFileChooser().apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = AppText.t("Choisir un dossier")
        isAcceptAllFileFilterUsed = false
    }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}.getOrNull()
