package com.ketvoice.app.discord

import com.ketvoice.app.sync.ConnectionsKind
import com.ketvoice.app.sync.DeviceIdentity
import com.ketvoice.app.sync.DiscordLink
import com.ketvoice.app.sync.LibrarySync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The phone's side of Discord in the account's `connections` document (9.2): whether Discord is linked here and
 * under which name, so Ketvoice for Windows shows « connected on Pixel 9 ». The Social SDK's tokens never leave it.
 */
internal object DiscordAccountSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        ConnectionsKind.localLink = { link(Discord.state.value) }
        ConnectionsKind.setSharing = { on ->
            com.ketvoice.app.data.AppSettings.discordPresence.value = on
            com.ketvoice.app.data.SettingsStore.save()
            if (!on) runCatching { Discord.clearPresence() }
        }
        scope.launch {
            Discord.state.map { link(it) }.distinctUntilChanged().drop(1).collect { runCatching { LibrarySync.connectionsChanged() } }
        }
    }

    internal fun link(state: DiscordState): DiscordLink? = if (!state.available) null else DiscordLink(DeviceIdentity.name(), "android",
        state.linked || state.presenceOnly, state.userName?.takeIf { state.linked && it.isNotBlank() },
        com.ketvoice.app.data.AppSettings.discordPresence.value)
}
