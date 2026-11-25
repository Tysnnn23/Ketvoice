package com.ketvoice.desktop.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.data.AppText
import com.ketvoice.app.social.FriendAction
import com.ketvoice.app.social.FriendLine
import com.ketvoice.app.social.FriendNow
import com.ketvoice.app.social.FriendStates
import com.ketvoice.desktop.ui.KText
import com.ketvoice.desktop.ui.LocalNavigator
import com.ketvoice.desktop.ui.LocalPlayer
import com.ketvoice.desktop.ui.LocalShell
import com.ketvoice.desktop.ui.Route
import com.ketvoice.desktop.ui.SettingsCard
import com.ketvoice.desktop.ui.ToggleRow
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * « Activité des amis »: what friends listen to now (only friends who switched « Partager mon écoute » on, and only
 * while they are friends), « Rejoindre » their room, adding a friend by name or link, and the requests.
 * Refreshed every 30 s while the tab is open, never in the background.
 */
@Composable
internal fun FriendsPanel() {
    val account by AccountSession.state.collectAsState()
    if (!account.signedIn) {
        Column(Modifier.fillMaxSize().testTag("panel:friends")) {
            PanelCard(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                KText("Activité des amis", Ket27Desk.Type.headline)
                KText("Connectez-vous à votre compte Ketvoice (Réglages › Compte Ketvoice) pour retrouver vos amis.",
                    Ket27Desk.Type.footnote, color = LocalKet.current.secondary, maxLines = 4)
            }
        }
        return
    }
    val me by Social.me.collectAsState()
    val friends by Social.friends.collectAsState()
    val now by Social.now.collectAsState()
    LaunchedEffect(account.userId) {
        if (Social.me.value == null) Social.refreshMe()
        while (true) { Social.refreshFriends(); Social.refreshNow(); delay(30_000) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("panel:friends"), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("share") { ShareSwitch(me) }
        item("now") { NowCard(now) }
        item("add") { AddFriend() }
        if (friends.incoming.isNotEmpty() || friends.outgoing.isNotEmpty()) item("requests") { Requests(friends.incoming, friends.outgoing) }
        if (friends.friends.isNotEmpty()) item("friends") { FriendList(friends.friends) }
    }
}

@Composable
private fun ShareSwitch(me: MyProfile?) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    Column {
        SettingsCard {
            ToggleRow("Partager mon écoute", me?.shareListening == true, "friends-share") { on ->
                scope.launch {
                    note = when {
                        on && me?.handle.isNullOrBlank() -> "Choisissez d'abord un nom d'utilisateur dans votre profil"
                        !Social.setShareListening(on) -> "Enregistrement impossible pour le moment."
                        else -> null
                    }
                }
            }
        }
        NoticeLine(note ?: "Vos amis voient ce que vous écoutez et la salle où vous êtes. Personne d'autre.")
    }
}

@Composable
private fun NowCard(now: List<FriendNow>) {
    val ket = LocalKet.current
    PanelCard(Modifier.testTag("friends-now")) {
        KText("En ce moment", Ket27Desk.Type.headline)
        if (now.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            KText("Aucun ami ne partage son écoute pour le moment.", Ket27Desk.Type.footnote, color = ket.secondary, maxLines = 3)
        }
        now.forEach { f -> Spacer(Modifier.height(10.dp)); NowRow(f) }
    }
}

@Composable
private fun NowRow(f: FriendNow) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val shell = LocalShell.current
    val nav = LocalNavigator.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(f.handle, f.name, f.accent, f.hasAvatar, 36.dp, Modifier.clickableIf(true) { nav.push(Route.Extra(SocialPages.PROFILE, f.handle)) })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            KText(nameOf(f.name, f.handle), Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
            val t = f.track
            KText(if (t == null) AppText.t("Entre deux titres") else (if (f.playing) "▶ " else "❚❚ ") + t.title + " · " + t.artist,
                Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        }
        val room = f.room
        if (room != null) SmallPill("Rejoindre", "friends-join:${f.handle}", filled = true) {
            DesktopTogether.openLink(room); shell.open(SocialPages.TOGETHER_TAB)
        } else f.track?.let { t -> SmallPill("Écouter", "friends-play:${f.handle}") { player.playQueue(listOf(t), 0) } }
    }
}

@Composable
private fun AddFriend() {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    fun send() {
        val handle = FriendStates.handleFrom(typed)
        if (handle == null) { note = "Entrez un nom d'utilisateur (@nom) ou le lien d'un profil"; return }
        scope.launch { note = FriendStates.message(Social.act(FriendAction.Ask, handle, null)) ?: "Envoi impossible pour le moment"; typed = "" }
    }
    PanelCard(Modifier.testTag("friends-add")) {
        KText("Ajouter un ami", Ket27Desk.Type.headline)
        Spacer(Modifier.height(8.dp))
        FieldLine(typed, "@nom ou lien du profil", "friends-handle", 300, onDone = ::send) { typed = it; note = null }
        Spacer(Modifier.height(8.dp))
        SmallPill("Envoyer la demande", "friends-ask", filled = true, onClick = ::send)
        NoticeLine(note)
    }
}

@Composable
private fun Requests(incoming: List<FriendLine>, outgoing: List<FriendLine>) {
    val scope = rememberCoroutineScope()
    PanelCard(Modifier.testTag("friends-requests")) {
        KText("Demandes", Ket27Desk.Type.headline)
        incoming.forEach { line ->
            Spacer(Modifier.height(10.dp))
            LineRow(line) {
                SmallPill("Accepter", "friends-accept:${line.handle}", filled = true) { scope.launch { Social.act(FriendAction.Accept, null, line.ref) } }
                Spacer(Modifier.width(6.dp))
                SmallPill("Refuser", "friends-decline:${line.handle}") { scope.launch { Social.act(FriendAction.Decline, null, line.ref) } }
            }
        }
        outgoing.forEach { line ->
            Spacer(Modifier.height(10.dp))
            LineRow(line, AppText.t("Demande envoyée")) {
                SmallPill("Annuler", "friends-cancel:${line.handle}") { scope.launch { Social.act(FriendAction.Cancel, null, line.ref) } }
            }
        }
    }
}

@Composable
private fun FriendList(friends: List<FriendLine>) {
    PanelCard(Modifier.testTag("friends-list")) {
        KText(AppText.t("Amis") + " · " + friends.size, Ket27Desk.Type.headline, translate = false)
        friends.forEach { line -> Spacer(Modifier.height(10.dp)); LineRow(line) {} }
    }
}

@Composable
private fun LineRow(line: FriendLine, under: String? = null, actions: @Composable () -> Unit) {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickableIf(true) { nav.push(Route.Extra(SocialPages.PROFILE, line.handle)) }, verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(line.handle, line.name, line.accent, line.hasAvatar, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                KText(nameOf(line.name, line.handle), Ket27Desk.Type.subheadline, translate = false)
                KText(under ?: "@${line.handle}", Ket27Desk.Type.caption, color = ket.secondary, translate = false)
            }
        }
        actions()
    }
}
