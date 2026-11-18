package com.ketvoice.app.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.sync.FollowLists
import com.ketvoice.app.sync.FollowPerson
import com.ketvoice.app.sync.FriendsFeed
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.account.ProfileUi
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.DESTRUCTIVE
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.Ket27PageBar
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.library.PersonAvatar
import kotlinx.coroutines.launch
import java.io.File

/** Which list is shown. */
enum class FollowTab { Followers, Following, Blocked }

/** What a button of a line asks: remove (a follower), block, unfollow or unblock. */
internal data class FollowRequest(val person: FollowPerson, val action: String)

/** « Abonnés », « Abonnements », « Bloqués »: remove or block a follower, unfollow, unblock. */
@Composable
fun FollowListsPage(modifier: Modifier = Modifier) {
    var lists by remember { mutableStateOf<FollowLists?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(FollowTab.Followers) }
    var asking by remember { mutableStateOf<FollowRequest?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attempt) {
        failed = false
        try { lists = FriendsFeed.lists() } catch (stopped: kotlinx.coroutines.CancellationException) { throw stopped } catch (_: Exception) { failed = true }
    }
    fun run(request: FollowRequest) {
        asking = null; notice = null
        scope.launch {
            val status = runCatching { FriendsFeed.manage(request.person, request.action) }.getOrNull()
            notice = when (status) { "done", "not_found" -> null; "limit" -> "Vous bloquez déjà 1 000 comptes."; else -> "Action impossible pour le moment." }
            attempt++
        }
    }
    FollowListsContent(lists, failed, tab, notice, modifier, onTab = { tab = it }, onBack = FriendsUi::back, onRetry = { attempt++ },
        onProfile = { ProfileUi.open(it.handle) },
        onAction = { request -> if (request.action in setOf("remove", "block")) asking = request else run(request) })
    asking?.let { request -> ConfirmFollowAction(request, onConfirm = { run(request) }, onDismiss = { asking = null }) }
}

@Composable
internal fun FollowListsContent(lists: FollowLists?, failed: Boolean, tab: FollowTab, notice: String?, modifier: Modifier = Modifier,
                                photos: Map<String, File> = emptyMap(), onTab: (FollowTab) -> Unit = {}, onBack: () -> Unit = {},
                                onRetry: () -> Unit = {}, onProfile: (FollowPerson) -> Unit = {}, onAction: (FollowRequest) -> Unit = {}) {
    val theme = LocalTheme.current
    Column(modifier.fillMaxSize().background(theme.background).statusBarsPadding().testTag("follow-lists")) {
        Ket27PageBar("Abonnés et abonnements", onBack)
        Tabs(lists, tab, onTab)
        notice?.let { Text(it, color = DESTRUCTIVE, style = Ket27.Type.footnote, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        when {
            lists == null && failed -> EmptyState("Listes indisponibles", "Impossible de charger vos abonnés pour le moment.", "Réessayer",
                Modifier.padding(top = 40.dp), icon = KetIcons.Refresh, onAction = onRetry)
            lists == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ActivitySpinner(size = 24.dp) }
            else -> PeopleList(people(lists, tab), tab, photos, onProfile, onAction)
        }
    }
}

private fun people(lists: FollowLists, tab: FollowTab) = when (tab) {
    FollowTab.Followers -> lists.followers
    FollowTab.Following -> lists.following
    FollowTab.Blocked -> lists.blocked
}

@Composable
private fun Tabs(lists: FollowLists?, tab: FollowTab, onTab: (FollowTab) -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(999.dp))
        .background(theme.onBackground.copy(alpha = .07f)).padding(3.dp)) {
        FollowTab.entries.forEach { entry ->
            val count = lists?.let { people(it, entry).size }
            val label = when (entry) { FollowTab.Followers -> "Abonnés"; FollowTab.Following -> "Abonnements"; FollowTab.Blocked -> "Bloqués" }
            Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(999.dp))
                .background(if (entry == tab) theme.card else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(role = Role.Tab) { onTab(entry) }.semantics { selected = entry == tab }.testTag("follow-tab-" + entry.name),
                contentAlignment = Alignment.Center) {
                Text(if (count != null && entry != FollowTab.Blocked) "$label ($count)" else label, color = theme.onBackground,
                    style = Ket27.Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PeopleList(people: List<FollowPerson>, tab: FollowTab, photos: Map<String, File>, onProfile: (FollowPerson) -> Unit,
                       onAction: (FollowRequest) -> Unit) {
    if (people.isEmpty()) {
        Text(when (tab) {
            FollowTab.Followers -> "Personne ne vous suit pour l'instant."
            FollowTab.Following -> "Vous ne suivez personne pour l'instant."
            FollowTab.Blocked -> "Aucun compte bloqué."
        }, color = LocalTheme.current.secondary, style = Ket27.Type.subheadline, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp).testTag("follow-empty"))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        items(people, key = { it.ref }) { person -> PersonLine(person, tab, publicAvatar(person.avatarRef, photos[person.avatarRef]), onProfile, onAction) }
    }
}

@Composable
private fun PersonLine(person: FollowPerson, tab: FollowTab, photo: File?, onProfile: (FollowPerson) -> Unit, onAction: (FollowRequest) -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().clickable(enabled = person.isPublic && person.handle.isNotBlank(), role = Role.Button) { onProfile(person) }
        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("person-" + person.handle), verticalAlignment = Alignment.CenterVertically) {
        PersonAvatar(person.name, photo, 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(person.name, localize = false, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(person.handle.takeIf { it.isNotBlank() }?.let { "@$it" },
                com.ketvoice.app.data.AppText.t("Profil privé").takeIf { !person.isPublic && tab != FollowTab.Blocked }).joinToString(" · ")
            if (line.isNotBlank()) Text(line, localize = false, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (tab) {
            FollowTab.Followers -> {
                KetTextButton("Retirer", { onAction(FollowRequest(person, "remove")) }, Modifier.testTag("follower-remove"))
                KetTextButton("Bloquer", { onAction(FollowRequest(person, "block")) }, Modifier.testTag("follower-block"), destructive = true)
            }
            FollowTab.Following -> KetTextButton("Ne plus suivre", { onAction(FollowRequest(person, "unfollow")) }, Modifier.testTag("following-unfollow"))
            FollowTab.Blocked -> KetTextButton("Débloquer", { onAction(FollowRequest(person, "unblock")) }, Modifier.testTag("blocked-unblock"))
        }
    }
}

/** Removing and blocking are confirmed: the other person is not told. */
@Composable
internal fun ConfirmFollowAction(request: FollowRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val block = request.action == "block"
    val who = request.person.handle.takeIf { it.isNotBlank() }?.let { "@$it" } ?: request.person.name
    AppAlertDialog(onDismiss, confirmButton = { KetTextButton(if (block) "Bloquer" else "Retirer", onConfirm, destructive = true) },
        dismissButton = { KetTextButton("Annuler", onDismiss) },
        title = { Text(if (block) "Bloquer $who ?" else "Retirer $who de vos abonnés ?", localize = true) },
        text = { Text(if (block) "Cette personne ne pourra plus vous suivre, voir votre profil ni réagir à votre activité. Elle n'en est pas avertie."
            else "Cette personne ne verra plus votre activité. Elle pourra vous suivre à nouveau.") })
}
