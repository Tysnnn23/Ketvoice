package com.ketvoice.desktop.connect

import com.ketvoice.app.sync.ConnectionsKind
import com.ketvoice.app.sync.DiscordLink
import com.ketvoice.app.sync.LibrarySync
import com.ketvoice.desktop.remote.DesktopRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Discord in the account's `connections` document (9.2), the PC's side: « Statut Discord » is the account's
 * « show what I listen to » choice, and this PC tells the other devices whether the local Discord app answered and
 * under which name. Nothing secret exists here to send: the local IPC pipe needs no token.
 */
object DiscordAccount {
    fun install(scope: CoroutineScope) {
        ConnectionsKind.localLink = ::link
        ConnectionsKind.sharing = { ConnectPrefs.discord.value }
        ConnectionsKind.setSharing = { on -> ConnectPrefs.adopt(ConnectPrefs.discord, "discord", on) }
        ConnectionsKind.sharingChosen = { ConnectPrefs.chosen("discord") }
        scope.launch {
            DiscordPresence.user.drop(1).collect { name ->
                if (name.isNullOrBlank() || name == ConnectPrefs.discordUser.value) return@collect
                ConnectPrefs.setText(ConnectPrefs.discordUser, "discordUser", name)
                runCatching { LibrarySync.connectionsChanged() }
            }
        }
    }

    /** What this PC tells the account: the computer's name, the Discord user last seen and the choice. */
    internal fun link(): DiscordLink {
        val user = ConnectPrefs.discordUser.value.takeIf { it.isNotBlank() }
        return DiscordLink(DesktopRemote.computerName(), "windows", user != null, user, ConnectPrefs.discord.value)
    }
}
