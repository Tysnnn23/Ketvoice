package com.ketvoice.app.ui.friends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.KetIcons
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.sync.FeedItem
import com.ketvoice.app.sync.FriendsFeed
import com.ketvoice.app.ui.account.ProfileUi
import com.ketvoice.app.ui.common.KetTextButton
import kotlinx.coroutines.delay
import java.io.File

/**
 * « Activité de vos amis » on Home: the three latest lines of the feed and « Tout voir ». Shown only while
 * signed in and once there is something to show; read again every few minutes while Home is on screen.
 */
@Composable
fun FriendsHomeSection(modifier: Modifier = Modifier) {
    val account by AccountSession.state.collectAsState()
    if (!account.signedIn) return
    LaunchedEffect(Unit) { while (true) { FriendsFeed.refresh(); delay(FriendsFeed.EVERY_MS) } }
    val items = feedState().items
    if (items.isEmpty()) return
    FriendsHomeContent(items.take(3), System.currentTimeMillis(), modifier)
}

@Composable
internal fun FriendsHomeContent(items: List<FeedItem>, now: Long, modifier: Modifier = Modifier, photos: Map<String, File> = emptyMap(),
                                onAll: () -> Unit = FriendsUi::openFeed, onOpen: (FeedItem) -> Unit = FriendsUi::open) {
    val actions = remember(onOpen) { FeedActions(onOpen = onOpen, onProfile = ProfileUi::open) }
    Column(modifier.fillMaxWidth().testTag("home-friends")) {
        SectionTitle("Activité de vos amis", onAll)
        items.forEach { item -> FeedRow(item, now, actions, publicAvatar(item.avatarRef, photos[item.avatarRef]), reactions = false) }
        KetTextButton("Tout voir", onAll, Modifier.padding(start = 4.dp).testTag("home-friends-all"))
    }
}

/** The section's title, whole on a phone (the editorial header keeps half the width for its trailing space). */
@Composable
private fun SectionTitle(title: String, onAll: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(start = com.ketvoice.app.ui.common.pageMargin, end = 8.dp, top = 26.dp, bottom = 8.dp)
        .clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onAll), verticalAlignment = Alignment.CenterVertically) {
        com.ketvoice.app.ui.common.SectionHeader(title, Modifier.weight(1f, fill = false))
        Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}
