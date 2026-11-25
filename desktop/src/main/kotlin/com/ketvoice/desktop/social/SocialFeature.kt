package com.ketvoice.desktop.social

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.ui.IslandMarks
import com.ketvoice.desktop.ui.KText
import com.ketvoice.desktop.ui.MenuItem
import com.ketvoice.desktop.ui.PageRegistry
import com.ketvoice.desktop.ui.PanelTabs
import com.ketvoice.desktop.ui.ShellHooks
import com.ketvoice.desktop.ui.ShellMetrics
import com.ketvoice.desktop.ui.TrackMenuSlots
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The social part of Ketvoice for Windows 2.0, kept small and private by default: listening rooms (« Ensemble »),
 * « Activité des amis », the profile page, « Partager » in the menus and `ketvoice://` links. Everything plugs into the
 * shell's registries ([PanelTabs], [PageRegistry], [IslandMarks], [ShellHooks], [TrackMenuSlots]); nothing reaches the
 * network until the listener signs in and opens one of them, and nothing is shared until they switch it on.
 */
object SocialFeature {
    /** The window's pieces; called before the window opens (and by tests). */
    fun register() {
        PanelTabs.register(PanelTabs.Tab(SocialPages.TOGETHER_TAB, "Ensemble", SocialGlyphs.Together, 50) { TogetherPanel() })
        PanelTabs.register(PanelTabs.Tab(SocialPages.FRIENDS_TAB, "Activité des amis", SocialGlyphs.Friends, 60) { FriendsPanel() })
        PageRegistry.register(PageRegistry.Page(SocialPages.PROFILE, "Profil", KetIcons.Account, sidebar = true, order = 120) { ProfilePage(it) })
        PageRegistry.register(PageRegistry.Page(SocialPages.SHARED_PLAYLIST, "Playlist partagée", KetIcons.Playlist, sidebar = false) { SharedPlaylistPage(it) })
        IslandMarks.register(IslandMarks.Mark("together") { TogetherMark() })
        ShellHooks.register(ShellHooks.Hook("social") { DeepLinks.Hook(); ShareNotice() })
        ShareLinks.register()
        if (TrackMenuSlots.extra.none { it === addToRoom }) TrackMenuSlots.extra += addToRoom
    }

    /** « Ajouter à Ensemble » in a title's menu while in a room: the host adds it, a guest proposes it to the host. */
    private val addToRoom: (com.ketvoice.app.data.Track) -> MenuItem? = { track ->
        if (!DesktopTogether.active || !com.ketvoice.app.social.ShareUrls.shareableId(track.id)) null
        else MenuItem("Ajouter à Ensemble", SocialGlyphs.Together, "together-add") { DesktopTogether.add(track) }
    }

    /** Behind the window: the room's beat, ketvoice:// for this PC, the profile with the account, « Partager mon écoute ». */
    fun start(scope: CoroutineScope) {
        // Discord's « Écouter sur Ketvoice » button uses the same smart link.
        com.ketvoice.desktop.connect.ShareLinks.provider = { track -> ShareLinks.trackUrl(track) }
        DesktopTogether.start(scope)
        UrlScheme.register()
        scope.launch {
            AccountSession.state.map { it.userId }.distinctUntilChanged().collect { uid ->
                if (uid == null) Social.clear() else runCatching { Social.refreshMe() }
            }
        }
        scope.launch { while (isActive) { delay(5_000); runCatching { Presence.step(System.currentTimeMillis()) } } }
    }
}

/** « Lien copié » and the other words of « Partager », a few seconds above the island. */
@Composable
private fun ShareNotice() {
    val notice by ShareLinks.notice.collectAsState()
    val ket = LocalKet.current
    LaunchedEffect(notice) { if (notice != null) { delay(3_000); ShareLinks.notice.value = null } }
    if (notice == null) return
    Box(Modifier.fillMaxSize().padding(bottom = ShellMetrics.ISLAND_HEIGHT + ShellMetrics.ISLAND_FOOT * 2 + 8.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(true, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.shadow(12.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(ket.raised)
                .border(.5.dp, ket.hairline, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 11.dp).testTag("share-notice")) {
                KText(notice.orEmpty(), Ket27Desk.Type.subheadline)
            }
        }
    }
}
