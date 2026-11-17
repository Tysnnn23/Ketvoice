package com.ketvoice.app.ui.library

import android.content.Context
import android.widget.Toast
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.PublicProfiles
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.ui.AppUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** « Partager la playlist » from a playlist's menu: signed out, the account sheet; a personal playlist is confirmed first. */
internal fun shareFromMenu(playlist: LocalPlaylist) {
    if (!AccountSession.state.value.signedIn) { AppUi.showAccount.value = true; return }
    if (PlaylistRules.isShared(playlist.id)) SharedUi.share.value = playlist.id else SharedUi.confirmShare.value = playlist
}

/** « Rendre cette playlist publique »: publishes it on the profile (which must itself be public to show it), or takes it off. */
internal fun publicFromMenu(playlist: LocalPlaylist, context: Context) {
    if (!AccountSession.state.value.signedIn) { AppUi.showAccount.value = true; return }
    val publish = playlist.id !in PublicProfiles.published.value
    scope.launch {
        val done = runCatching { PublicProfiles.setPublished(playlist, publish) }.isSuccess
        val text = when {
            !done -> "Impossible d'enregistrer la playlist"
            publish && com.ketvoice.app.account.Profiles.profile.value.isPublic -> "Playlist publique sur votre profil."
            publish -> "Playlist publique. Activez « Profil public » dans votre compte pour qu'elle apparaisse."
            else -> "Playlist retirée de votre profil."
        }
        Toast.makeText(context, AppText.t(text), Toast.LENGTH_LONG).show()
    }
}
