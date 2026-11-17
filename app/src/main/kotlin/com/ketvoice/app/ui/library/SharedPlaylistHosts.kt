package com.ketvoice.app.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.RemoteAvatars
import com.ketvoice.app.account.RestRejected
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.sync.SharedInvite
import com.ketvoice.app.sync.SharedLinks
import com.ketvoice.app.sync.SharedMember
import com.ketvoice.app.sync.SharedPlaylists
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.account.ProfileUi
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.player.PlayerSheetScrim
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** What the shared playlists' sheets and dialogs are showing. Opened from a playlist's menu, a link or the Library. */
object SharedUi {
    /** The Library id of the playlist whose invitation is shown. */
    val share = mutableStateOf<String?>(null)
    val members = mutableStateOf<String?>(null)
    /** A personal playlist the listener asked to share: confirmed first, because it becomes a shared one. */
    val confirmShare = mutableStateOf<LocalPlaylist?>(null)
    /** An invitation to answer: the code from a link, or "" while the listener types one. */
    val join = mutableStateOf<String?>(null)

    fun openJoin(code: String?) { join.value = code ?: "" }
}

/** Every sheet and dialog of the shared playlists; placed once, over the pages, by AppRoot. */
@Composable
fun SharedPlaylistHosts() {
    SharedUi.share.value?.let { ShareHost(it) }
    SharedUi.members.value?.let { MembersHost(it) }
    SharedUi.confirmShare.value?.let { ConfirmShareDialog(it) }
    SharedUi.join.value?.let { JoinDialog(it) }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, AppText.t(text), Toast.LENGTH_SHORT).show()

private fun reason(error: Throwable, fallback: String): String = when {
    error is IOException -> "Hors ligne : réessayez une fois connecté."
    error is RestRejected && error.code == "54000" -> "Trop d'essais. Réessayez dans quelques minutes."
    else -> fallback
}

@Composable
private fun ShareHost(id: String) {
    val context = LocalContext.current
    val lists by PlaylistStore.lists.collectAsState()
    val name = lists.firstOrNull { it.id == id }?.name.orEmpty()
    var state by remember(id) { mutableStateOf<ShareState>(ShareState.Loading) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        state = ShareState.Loading
        state = try { ShareState.Ready(SharedPlaylists.invite(id)) } catch (stopped: kotlinx.coroutines.CancellationException) { throw stopped
        } catch (error: Exception) { ShareState.Failed(reason(error, "Impossible de créer l'invitation pour le moment.")) }
    }
    val close = { SharedUi.share.value = null }
    BackHandler { close() }
    PlayerSheetScrim(close) {
        ShareSheet(name, state, onCopyCode = { (state as? ShareState.Ready)?.let { copy(context, "Code", it.invite.code); toast(context, "Code copié") } },
            onCopyLink = { (state as? ShareState.Ready)?.let { copy(context, "Lien", it.invite.link); toast(context, "Lien copié") } },
            onShare = { (state as? ShareState.Ready)?.let { sendInvitation(context, name, it.invite) } },
            onRetry = { attempt++ }, onClose = close)
    }
}

private fun copy(context: Context, label: String, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Ketvoice $label", text))
}

/** The invitation as text for any app: the playlist's name, the code and the link that opens Ketvoice on it. */
internal fun invitationText(name: String, invite: SharedInvite): String =
    name + "\n" + AppText.t("Code d'invitation") + " : " + invite.code + "\n" + invite.link

private fun sendInvitation(context: Context, name: String, invite: SharedInvite) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TITLE, name)
        putExtra(Intent.EXTRA_TEXT, invitationText(name, invite))
    }
    runCatching { context.startActivity(Intent.createChooser(send, AppText.t("Partager la playlist")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun MembersHost(id: String) {
    val lists by PlaylistStore.lists.collectAsState()
    val name = lists.firstOrNull { it.id == id }?.name.orEmpty()
    val status by SharedPlaylists.status.collectAsState()
    var members by remember(id) { mutableStateOf<List<SharedMember>?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var reload by remember(id) { mutableIntStateOf(0) }
    var removing by remember(id) { mutableStateOf<SharedMember?>(null) }
    var leaving by remember(id) { mutableStateOf(false) }
    val avatars = remember(id) { mutableStateMapOf<String, File>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(id, reload) {
        failed = false
        try { members = SharedPlaylists.members(id) } catch (stopped: kotlinx.coroutines.CancellationException) { throw stopped
        } catch (error: Exception) { failed = true }
    }
    LaunchedEffect(members) {
        members.orEmpty().filter { it.avatarPath.isNotBlank() && it.userId !in avatars }.forEach { member ->
            RemoteAvatars.get(RemoteAvatars.PRIVATE, member.avatarPath)?.let { avatars[member.userId] = it }
        }
    }
    val close = { SharedUi.members.value = null }
    BackHandler { close() }
    PlayerSheetScrim(close) {
        MembersSheet(name, members, failed, AccountSession.userId().orEmpty(), SharedPlaylists.isOwner(id), avatars, status.pending > 0,
            onProfile = { member -> close(); ProfileUi.open(member.handle) },
            onRemove = { removing = it }, onLeave = { leaving = true }, onClose = close)
    }
    removing?.let { member ->
        AppAlertDialog(onDismissRequest = { removing = null }, title = { Text("Retirer ce membre ?") },
            text = { Text("Cette personne ne pourra plus modifier la playlist.") },
            confirmButton = { KetTextButton("Retirer", {
                removing = null
                scope.launch {
                    runCatching { SharedPlaylists.removeMember(id, member.userId) }
                        .onSuccess { reload++ }.onFailure { toast(context, "Impossible de retirer ce membre pour le moment.") }
                }
            }, primary = true, destructive = true) },
            dismissButton = { KetTextButton("Annuler", { removing = null }) })
    }
    if (leaving) AppAlertDialog(onDismissRequest = { leaving = false }, title = { Text("Quitter la playlist ?") },
        text = { Text("Elle disparaît de votre bibliothèque. Les autres membres la gardent.") },
        confirmButton = { KetTextButton("Quitter", {
            leaving = false; close()
            scope.launch { runCatching { SharedPlaylists.leave(id) } }
            if (AppUi.playlistId.value == id) AppUi.closeOverlay()
        }, primary = true, destructive = true) },
        dismissButton = { KetTextButton("Annuler", { leaving = false }) })
}

@Composable
private fun ConfirmShareDialog(playlist: LocalPlaylist) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember(playlist.id) { mutableStateOf(false) }
    AppAlertDialog(onDismissRequest = { if (!busy) SharedUi.confirmShare.value = null }, title = { Text("Partager cette playlist ?") },
        text = { Text("Ses titres sont copiés dans une playlist partagée dont vous êtes propriétaire. Elle remplace la playlist actuelle sur cet appareil.") },
        confirmButton = { KetTextButton("Partager", {
            busy = true
            scope.launch {
                runCatching { SharedPlaylists.share(playlist) }.onSuccess { id ->
                    if (AppUi.playlistId.value == playlist.id) AppUi.playlistId.value = id
                    SharedUi.confirmShare.value = null
                    SharedUi.share.value = id
                }.onFailure { toast(context, "Impossible de partager la playlist pour le moment."); SharedUi.confirmShare.value = null }
            }
        }, primary = true, enabled = !busy) },
        dismissButton = { KetTextButton("Annuler", { SharedUi.confirmShare.value = null }, enabled = !busy) })
}

/** « Rejoindre la playlist »: from a ketvoice://join/<code> link (the code is filled in) or typed. */
@Composable
private fun JoinDialog(initial: String) {
    val account by AccountSession.state.collectAsState()
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var typed by remember(initial) { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val close = { SharedUi.join.value = null }
    if (!account.signedIn) {
        AppAlertDialog(onDismissRequest = close, title = { Text("Rejoindre la playlist ?") },
            text = { Text("Connectez-vous à votre compte Ketvoice pour rejoindre une playlist partagée.") },
            confirmButton = { KetTextButton("Se connecter", { AppUi.showAccount.value = true }, primary = true) },
            dismissButton = { KetTextButton("Annuler", close) })
        return
    }
    AppAlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Rejoindre la playlist ?") },
        text = {
            Column {
                Text("Vous pourrez ajouter, retirer et réordonner des titres avec les autres membres.")
                KetTextField(typed, { typed = it.take(24); message = null }, "Code d'invitation", Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !busy)
                message?.let { Text(it, color = com.ketvoice.app.ui.common.DESTRUCTIVE, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = { KetTextButton("Rejoindre", {
            val code = SharedLinks.cleanCode(typed)
            if (code == null) { message = "Ce code n'est pas valable ou a expiré."; return@KetTextButton }
            busy = true
            scope.launch {
                try {
                    val result = SharedPlaylists.join(code)
                    when (result.status) {
                        "joined", "already" -> {
                            close(); toast(context, if (result.status == "joined") "Playlist rejointe" else "Vous êtes déjà membre de cette playlist.")
                            result.playlistId?.let { AppUi.openPlaylist(PlaylistRules.sharedLocalId(it)) }
                        }
                        "full" -> message = "Cette playlist est complète."
                        else -> message = "Ce code n'est pas valable ou a expiré."
                    }
                } catch (stopped: kotlinx.coroutines.CancellationException) { throw stopped
                } catch (error: Exception) { message = reason(error, "Impossible de rejoindre pour le moment.") }
                busy = false
            }
        }, primary = true, enabled = !busy && typed.isNotBlank()) },
        dismissButton = { KetTextButton("Annuler", close, enabled = !busy) })
}
