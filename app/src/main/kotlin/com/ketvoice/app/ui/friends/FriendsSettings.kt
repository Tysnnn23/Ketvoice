package com.ketvoice.app.ui.friends

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.Profiles
import com.ketvoice.app.sync.FriendsFeed
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.account.AccountButton
import com.ketvoice.app.ui.account.SwitchRow
import com.ketvoice.app.ui.common.DESTRUCTIVE
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.launch

/** The friends part of the account sheet: « Publier mon activité », the feed and the lists. */
@Composable
internal fun FriendsSettings(modifier: Modifier = Modifier) {
    val profile by Profiles.profile.collectAsState()
    val share by FriendsFeed.shareActivity.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FriendsSettingsContent(profile.isPublic, share, error, modifier, onShare = { on ->
        error = null
        scope.launch { runCatching { FriendsFeed.setShareActivity(on) }.onFailure { error = "Enregistrement impossible pour le moment." } }
    })
}

@Composable
internal fun FriendsSettingsContent(isPublic: Boolean, share: Boolean, error: String?, modifier: Modifier = Modifier,
                                    onShare: (Boolean) -> Unit = {}, onFeed: () -> Unit = FriendsUi::openFeed,
                                    onLists: () -> Unit = FriendsUi::openLists) {
    val theme = LocalTheme.current
    Column(modifier.fillMaxWidth().padding(top = 14.dp).testTag("friends-settings")) {
        Text("Amis", color = theme.onBackground, style = Ket27.Type.headline, modifier = Modifier.padding(bottom = 2.dp))
        SwitchRow("Publier mon activité",
            if (isPublic) "Vos abonnés voient vos nouvelles playlists publiques et, si vous le montrez, ce que vous écoutez."
            else "Disponible une fois votre profil public.",
            share && isPublic, isPublic, onShare, Modifier.testTag("switch-share-activity"))
        error?.let { Text(it, color = DESTRUCTIVE, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 4.dp)) }
        AccountButton("Fil des amis", modifier = Modifier.padding(top = 8.dp).testTag("account-friends-feed"), onClick = onFeed)
        AccountButton("Abonnés et abonnements", modifier = Modifier.padding(top = 8.dp).testTag("account-follow-lists"), onClick = onLists)
    }
}
