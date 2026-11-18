package com.ketvoice.app.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.PublicProfile
import com.ketvoice.app.sync.FriendsFeed
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.account.ProfileUi
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.launch

/** « Suivre » on someone else's profile page: whether the listener follows, a note after a refusal, and blocking. */
class ProfileFollow(val following: Boolean, val busy: Boolean, val note: String?, val onToggle: () -> Unit, val onBlock: () -> Unit)

/** The words a refusal of `ketvoice_follow` is shown with. */
internal fun followNote(status: String): String? = when (status) {
    "following", "already", "removed", "not_following" -> null
    "needs_handle" -> "Choisissez d'abord un nom d'utilisateur (Modifier le profil) pour suivre des comptes."
    "limit" -> "Vous suivez déjà 500 comptes."
    else -> "Ce profil ne peut pas être suivi."
}

/** The follow controls of [profile] (null for the listener's own profile or while it loads); asks before blocking. */
@Composable
fun rememberProfileFollow(profile: PublicProfile?): ProfileFollow? {
    profile ?: return null
    if (profile.isMe) return null
    var following by remember(profile.handle, profile.following) { mutableStateOf(profile.following) }
    var busy by remember(profile.handle) { mutableStateOf(false) }
    var note by remember(profile.handle) { mutableStateOf<String?>(null) }
    var asking by remember(profile.handle) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun toggle() {
        if (busy) return
        busy = true; note = null
        scope.launch {
            val status = runCatching { if (following) FriendsFeed.unfollow(profile.handle) else FriendsFeed.follow(profile.handle) }.getOrNull()
            when (status) {
                "following", "already" -> following = true
                "removed", "not_following" -> following = false
                null -> note = "Action impossible pour le moment."
                else -> note = followNote(status)
            }
            busy = false
        }
    }
    if (asking) BlockAlert(profile.handle, onDismiss = { asking = false }) {
        asking = false
        scope.launch {
            val status = runCatching { FriendsFeed.block(profile.handle) }.getOrNull()
            if (status == "blocked") ProfileUi.close() else note = "Action impossible pour le moment."
        }
    }
    return ProfileFollow(following, busy, note, ::toggle) { asking = true }
}

@Composable
private fun BlockAlert(handle: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AppAlertDialog(onDismiss, confirmButton = { KetTextButton("Bloquer", onConfirm, destructive = true) },
        dismissButton = { KetTextButton("Annuler", onDismiss) },
        title = { Text("Bloquer @$handle ?") },
        text = { Text("Cette personne ne pourra plus vous suivre, voir votre profil ni réagir à votre activité. Elle n'en est pas avertie.") })
}

/** The capsule under the name: « Suivre » on the accent, « Abonné » outlined (touching it stops following). */
@Composable
fun FollowButton(follow: ProfileFollow, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(999.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(min = 132.dp).height(40.dp).clip(shape)
            .then(if (follow.following) Modifier.border(1.dp, theme.secondary.copy(alpha = .5f), shape) else Modifier.background(theme.accent))
            .clickable(enabled = !follow.busy, role = Role.Button, onClick = follow.onToggle).padding(horizontal = 20.dp)
            .testTag(if (follow.following) "profile-unfollow" else "profile-follow"), contentAlignment = Alignment.Center) {
            if (follow.busy) ActivitySpinner(size = 18.dp, color = if (follow.following) theme.secondary else Color.White)
            else Text(if (follow.following) "Abonné" else "Suivre", color = if (follow.following) theme.onBackground else Color.White,
                style = Ket27.Type.headline)
        }
        follow.note?.let { Text(it, color = theme.secondary, style = Ket27.Type.footnote, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** At the foot of someone else's profile page. */
@Composable
fun BlockProfileButton(follow: ProfileFollow, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
        KetTextButton("Bloquer ce profil", follow.onBlock, Modifier.testTag("profile-block"), destructive = true)
    }
}
