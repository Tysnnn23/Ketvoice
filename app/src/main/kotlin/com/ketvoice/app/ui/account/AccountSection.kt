package com.ketvoice.app.ui.account

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.Profiles
import com.ketvoice.app.data.AppText
import com.ketvoice.app.sync.LibrarySync
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the account sheet shows on top of itself: a form, the profile editor, an alert. */
object AccountUi {
    enum class Form { SignUp, SignIn }
    val form = mutableStateOf<Form?>(null)
    val profileOpen = mutableStateOf(false)
    val confirmSignOut = mutableStateOf(false)
    val confirmDelete = mutableStateOf(false)
    /** A short message under the account card (a finished sign-in through Discord, an error). */
    val notice = mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Discord sent the listener back to the app: the session is finished and the account sheet shows it. */
    fun completeOAuth(uri: Uri) {
        AppUi.showAccount.value = true
        notice.value = null
        scope.launch {
            runCatching { AccountSession.completeOAuth(uri) }
                .onFailure { notice.value = it.message ?: "Connexion impossible pour le moment." }
        }
    }
}

/** The Ketvoice account's circle: the photo, or the first letter of the name on the accent. */
@Composable
fun KetvoiceAvatarContent(size: Dp) {
    val photo by Profiles.avatar.collectAsState()
    val profile by Profiles.profile.collectAsState()
    val state by AccountSession.state.collectAsState()
    val file = photo
    if (file != null) {
        AsyncImage(file, profile.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        val letter = profile.displayName.ifBlank { state.email }.take(1).uppercase().ifBlank { "K" }
        Box(Modifier.fillMaxSize().background(LocalTheme.current.accent), contentAlignment = Alignment.Center) {
            Text(letter, localize = false, color = Color.White, fontSize = (size.value * .42f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The Ketvoice account at the top of the account sheet. */
@Composable
fun KetvoiceAccountSection(modifier: Modifier = Modifier) {
    val state by AccountSession.state.collectAsState()
    Column(modifier.fillMaxWidth().testTag("ketvoice-account")) {
        if (state.signedIn) SignedInCard() else SignedOutCard(state.message)
        AccountUi.notice.value?.let { Notice(it) }
    }
    AccountOverlays()
}

@Composable
private fun Notice(text: String) {
    Text(text, color = LocalTheme.current.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SignedOutCard(message: String?) {
    val theme = LocalTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(theme.onBackground.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(KetIcons.Account, null, tint = theme.secondary, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text("Compte Ketvoice", color = theme.onBackground, style = Ket27.Type.title3)
            Text("Vos favoris, playlists, écoutes récentes, statistiques et réglages sur tous vos appareils.", color = theme.secondary, style = Ket27.Type.footnote)
        }
    }
    if (message != null) Text(message, color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 10.dp))
    AccountButton("Créer un compte", primary = true, modifier = Modifier.padding(top = 16.dp).testTag("account-sign-up")) {
        AccountUi.notice.value = null; AccountUi.form.value = AccountUi.Form.SignUp
    }
    AccountButton("Se connecter", modifier = Modifier.padding(top = 8.dp).testTag("account-sign-in")) {
        AccountUi.notice.value = null; AccountUi.form.value = AccountUi.Form.SignIn
    }
    DiscordButton()
    Text("Rien n'est envoyé tant que vous n'êtes pas connecté.", color = theme.secondary, style = Ket27.Type.caption,
        modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun DiscordButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    AccountButton("Continuer avec Discord", modifier = Modifier.padding(top = 8.dp), tint = Color(0xFF5865F2)) {
        AccountUi.notice.value = null
        runCatching { AccountSession.startDiscord(context) }.onFailure { AccountUi.notice.value = "Connexion impossible pour le moment." }
    }
}

/** A full-width capsule: the main action tinted with the accent, the others on the page's ink. */
@Composable
internal fun AccountButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true,
                           tint: Color? = null, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val fill = tint ?: if (primary) theme.accent else theme.onBackground.copy(alpha = .08f)
    val ink = if (tint != null || primary) Color.White else theme.onBackground
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
        .background(fill.copy(alpha = if (enabled) fill.alpha else fill.alpha * .5f))
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = ink.copy(alpha = if (enabled) 1f else .6f), style = Ket27.Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun SignedInCard() {
    val theme = LocalTheme.current
    val profile by Profiles.profile.collectAsState()
    val state by AccountSession.state.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(CircleShape)) { KetvoiceAvatarContent(56.dp) }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(profile.displayName.ifBlank { AppText.t("Compte Ketvoice") }, localize = false, color = theme.onBackground,
                style = Ket27.Type.title3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = if (profile.handle.isNotBlank()) "@" + profile.handle else state.email
            Text(line, localize = false, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    SyncLine()
    PublicProfileSettings()
    com.ketvoice.app.ui.friends.FriendsSettings()
    AccountButton("Modifier le profil", modifier = Modifier.padding(top = 14.dp).testTag("account-edit-profile")) { AccountUi.profileOpen.value = true }
    AccountButton("Se déconnecter", modifier = Modifier.padding(top = 8.dp)) { AccountUi.confirmSignOut.value = true }
    KetTextButton("Supprimer le compte", { AccountUi.confirmDelete.value = true }, Modifier.fillMaxWidth().padding(top = 4.dp), destructive = true)
}

@Composable
private fun SyncLine() {
    val theme = LocalTheme.current
    val status by LibrarySync.status.collectAsState()
    val text = when {
        status.running -> AppText.t("Synchronisation…")
        status.problem == com.ketvoice.app.sync.SyncProblem.OFFLINE -> AppText.t("Hors ligne · vos changements partiront plus tard")
        status.problem == com.ketvoice.app.sync.SyncProblem.SIGNED_OUT -> AppText.t("Session expirée · reconnectez-vous")
        status.problem == com.ketvoice.app.sync.SyncProblem.SERVER -> AppText.t("Erreur du serveur · nouvel essai bientôt")
        status.lastSuccess > 0 -> AppText.t("Synchronisé") + " · " + android.text.format.DateUtils.getRelativeTimeSpanString(
            status.lastSuccess, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
        else -> AppText.t("Synchronisation en attente")
    }
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (status.problem != com.ketvoice.app.sync.SyncProblem.NONE) Color(0xFFFF9F0A) else Color(0xFF34C759)))
        Text(text, localize = false, color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(start = 8.dp).weight(1f))
        val scope = rememberCoroutineScope()
        KetTextButton("Synchroniser", { scope.launch { runCatching { LibrarySync.syncNow() } } }, enabled = !status.running)
    }
}
