package com.ketvoice.app.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.data.AppText
import com.ketvoice.app.sync.FeedItem
import com.ketvoice.app.sync.FeedProblem
import com.ketvoice.app.sync.FeedReactions
import com.ketvoice.app.sync.FeedState
import com.ketvoice.app.sync.FriendsFeed
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.account.ProfileUi
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.Ket27PageBar
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.RowHighlight
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.library.PersonAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** What the feed page does when touched (kept apart so host renders draw it without any network). */
internal class FeedActions(val onBack: () -> Unit = {}, val onLists: () -> Unit = {}, val onRetry: () -> Unit = {},
                           val onSignIn: () -> Unit = {}, val onMore: () -> Unit = {}, val onOpen: (FeedItem) -> Unit = {},
                           val onProfile: (String) -> Unit = {}, val onReact: (FeedItem, String) -> Unit = { _, _ -> })

/** « Amis »: the activity of the public profiles the listener follows, read again every few minutes while shown. */
@Composable
fun FriendsFeedPage(modifier: Modifier = Modifier) {
    val account by AccountSession.state.collectAsState()
    val state = feedState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(account.signedIn) {
        if (account.signedIn) while (true) { FriendsFeed.refresh(); delay(FriendsFeed.EVERY_MS) }
    }
    val actions = remember {
        FeedActions(onBack = FriendsUi::back, onLists = FriendsUi::openLists, onRetry = { scope.launch { FriendsFeed.refresh() } },
            onSignIn = { FriendsUi.closeAll(); AppUi.showAccount.value = true }, onMore = { scope.launch { FriendsFeed.loadMore() } },
            onOpen = FriendsUi::open, onProfile = ProfileUi::open, onReact = FriendsUi::react)
    }
    FriendsFeedContent(state, account.signedIn, actions, System.currentTimeMillis(), modifier)
}

@Composable
internal fun FriendsFeedContent(state: FeedState, signedIn: Boolean, actions: FeedActions, now: Long, modifier: Modifier = Modifier,
                                photos: Map<String, File> = emptyMap()) {
    val theme = LocalTheme.current
    Column(modifier.fillMaxSize().background(theme.background).statusBarsPadding().testTag("friends-feed")) {
        Ket27PageBar("Amis", actions.onBack) {
            if (signedIn) Box(Modifier.size(44.dp).clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button, onClick = actions.onLists)
                .testTag("friends-open-lists"), contentAlignment = Alignment.Center) {
                Icon(KetIcons.Account, AppText.t("Abonnés et abonnements"), tint = theme.accent, modifier = Modifier.size(22.dp))
            }
        }
        when {
            !signedIn -> EmptyState("Fil des amis", "Connectez-vous à votre compte Ketvoice pour suivre des profils publics.",
                "Se connecter", Modifier.padding(top = 40.dp), icon = KetIcons.Account, onAction = actions.onSignIn)
            state.items.isEmpty() && (state.loading || !state.loaded) && state.problem == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ActivitySpinner(size = 24.dp) }
            state.items.isEmpty() && state.problem != null -> EmptyState("Fil indisponible",
                if (state.problem == FeedProblem.Offline) "Hors ligne : l'activité de vos amis reviendra une fois connecté."
                else "Impossible de charger l'activité de vos amis pour le moment.",
                "Réessayer", Modifier.padding(top = 40.dp), icon = KetIcons.Refresh, onAction = actions.onRetry)
            else -> FeedList(state, actions, now, photos)
        }
    }
}

@Composable
private fun FeedList(state: FeedState, actions: FeedActions, now: Long, photos: Map<String, File>) {
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && !state.endReached && state.items.isNotEmpty()) actions.onMore() }
    LazyColumn(Modifier.fillMaxSize().testTag("friends-feed-list"), state = list, contentPadding = PaddingValues(bottom = 120.dp)) {
        item(key = "search") { FindProfile(actions.onProfile) }
        if (state.problem != null) item(key = "offline") { OfflineNote(state.problem) }
        if (state.items.isEmpty()) item(key = "empty") {
            EmptyState("Aucune activité pour l'instant", "Suivez des profils publics : leurs nouvelles playlists et ce qu'ils écoutent apparaîtront ici.",
                "Actualiser", icon = KetIcons.Account, onAction = actions.onRetry)
        }
        items(state.items, key = { it.id }) { item ->
            FeedRow(item, now, actions, photo = publicAvatar(item.avatarRef, photos[item.avatarRef]))
        }
        if (state.loadingMore) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { ActivitySpinner() }
        }
    }
}

/** « Rechercher un profil » at the top of the feed: opens the page of a handle, where « Suivre » is. */
@Composable
private fun FindProfile(onOpen: (String) -> Unit) {
    var handle by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) {
            KetTextField(handle, { handle = it.take(25) }, "Rechercher un profil", Modifier.fillMaxWidth().testTag("friends-search-field"))
        }
        KetTextButton("Ouvrir", { onOpen(handle) }, Modifier.testTag("friends-search-open"), primary = true,
            enabled = handle.trim().removePrefix("@").length >= 3)
    }
}

@Composable
private fun OfflineNote(problem: FeedProblem) {
    Text(if (problem == FeedProblem.Offline) "Hors ligne · dernière activité enregistrée" else "Actualisation impossible pour le moment.",
        color = LocalTheme.current.secondary, style = Ket27.Type.footnote,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("friends-offline"))
}

/** One line: the photo and name (to the profile), what happened and when, the cover; touching it plays. */
@Composable
internal fun FeedRow(item: FeedItem, now: Long, actions: FeedActions, photo: File?, reactions: Boolean = true) {
    val theme = LocalTheme.current
    val highlight = remember(theme.onBackground) { RowHighlight(theme.onBackground) }
    Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, highlight, role = Role.Button) { actions.onOpen(item) }
        .padding(horizontal = 16.dp, vertical = 10.dp).testTag("feed-item-" + item.id), verticalAlignment = Alignment.Top) {
        PersonAvatar(item.name, photo, 44.dp, Modifier.clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button) { actions.onProfile(item.handle) })
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            FeedHeadline(item, now)
            Text(FeedWords.subject(item), localize = false, color = theme.onBackground, style = Ket27.Type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (reactions) ReactionBar(item) { actions.onReact(item, it) }
        }
        Cover(item.playlist?.id ?: item.track?.id ?: item.id, item.coverUrl, 56.dp, 10.dp)
    }
}

@Composable
private fun FeedHeadline(item: FeedItem, now: Long) {
    val theme = LocalTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(item.name, localize = false, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(" · " + FeedWords.ago(item.atMs, now), localize = false, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1)
    }
    Text(FeedWords.action(item, now), localize = false, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
}

/** The five reactions, each with its count; the listener's own is tinted, and touching it again takes it back. */
@Composable
private fun ReactionBar(item: FeedItem, onReact: (String) -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.padding(top = 8.dp).testTag("feed-reactions"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FeedReactions.keys.forEach { key ->
            val mine = item.mine == key
            val count = item.reactions[key] ?: 0
            val label = FeedWords.reactionName(key)
            Row(Modifier.height(30.dp).clip(RoundedCornerShape(999.dp))
                .background(if (mine) theme.accent.copy(alpha = .18f) else theme.onBackground.copy(alpha = .06f))
                .then(if (mine) Modifier.border(1.dp, theme.accent.copy(alpha = .7f), RoundedCornerShape(999.dp)) else Modifier)
                .clickable(role = Role.Button) { onReact(key) }
                .semantics { contentDescription = if (count > 0) "$label, $count" else label; selected = mine }
                .padding(horizontal = 9.dp).testTag("reaction-" + key), verticalAlignment = Alignment.CenterVertically) {
                Text(FeedReactions.emoji(key), localize = false, fontSize = 14.sp)
                if (count > 0) Text(count.toString(), localize = false, color = if (mine) theme.accent else theme.secondary,
                    style = Ket27.Type.caption, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
