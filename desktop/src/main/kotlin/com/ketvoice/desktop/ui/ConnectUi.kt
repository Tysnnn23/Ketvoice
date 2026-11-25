package com.ketvoice.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.connect.ConnectPrefs
import com.ketvoice.desktop.connect.Scrobbler
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/**
 * Hooks « Connexions » into the Windows 2.0 shell through its registries only: the `connections` page (sidebar), the
 * `my-data` page, the « Connexions » and « Profils sur ce PC » sections of Settings, and the lyrics editor's preview
 * left out of scrobbling.
 */
object ConnectUi {
    const val PAGE = "connections"
    const val MY_DATA_PAGE = "my-data"
    /** The import feature's page, when it is in this build (guarded: the row says « Bientôt disponible » otherwise). */
    const val IMPORT_PAGE = "import"
    private var excludedEditor = false

    fun register() {
        PageRegistry.register(PageRegistry.Page(PAGE, "Connexions", KetIcons.Link, sidebar = true, order = 120) { ConnectionsPage() })
        PageRegistry.register(PageRegistry.Page(MY_DATA_PAGE, "Mes données", KetIcons.Shield, sidebar = false, order = 121) { MyDataPage() })
        SettingsSections.register(SettingsSections.Section("connections", "Connexions", after = "Compte Ketvoice",
            rows = listOf("Statut Discord pendant la lecture", "Masquer le titre", "Synchronisation en direct", "Last.fm", "ListenBrainz",
                "Se connecter avec un QR code", "Appareils connectés", "Mes données")) { ConnectSettings() })
        SettingsSections.register(SettingsSections.Section("profiles", "Profils sur ce PC", after = "Windows",
            rows = listOf("Profils sur ce PC", "Ajouter un profil")) { LocalProfilesCard() })
        registerExclusion()
    }

    /** Tests: the shell as it is without this feature. */
    internal fun unregister() {
        PageRegistry.unregister(PAGE)
        PageRegistry.unregister(MY_DATA_PAGE)
        SettingsSections.unregister("connections")
        SettingsSections.unregister("profiles")
    }

    private fun registerExclusion() {
        // The lyrics editor plays its title through the player: a play heard while it is open on that title is left out.
        if (excludedEditor) return
        excludedEditor = true
        Scrobbler.exclusions += {
            val editing = LyricsEditorDesk.request.value
            editing != null && editing.id == com.ketvoice.app.player.Player.state.value.current?.id
        }
    }
}

/** Settings › Connexions: the switches at hand, sign-in by QR code when signed out, and the way to the page. */
@Composable
private fun ConnectSettings() {
    val account by AccountSession.state.collectAsState()
    val nav = LocalNavigator.current
    SettingsCard {
        if (!account.signedIn) {
            LinkRow("Se connecter avec un QR code", "settings-qr") { nav.root(Route.Extra(ConnectUi.PAGE)) }
            Hairline()
        }
        ToggleRow("Statut Discord pendant la lecture", ConnectPrefs.discord.value, "settings-discord") { ConnectPrefs.set(ConnectPrefs.discord, "discord", it) }
        Hairline()
        ToggleRow("Masquer le titre", ConnectPrefs.discordHideTitle.value, "settings-discord-hide") {
            ConnectPrefs.set(ConnectPrefs.discordHideTitle, "discordHideTitle", it)
        }
        Hairline()
        ToggleRow("Synchronisation en direct", ConnectPrefs.liveSync.value, "settings-live-sync") { ConnectPrefs.set(ConnectPrefs.liveSync, "liveSync", it) }
        Hairline()
        LinkRow("Last.fm, ListenBrainz et appareils", "settings-open-connections") { nav.root(Route.Extra(ConnectUi.PAGE)) }
        Hairline()
        LinkRow("Mes données", "settings-open-my-data") { nav.push(Route.Extra(ConnectUi.MY_DATA_PAGE)) }
    }
}

@Composable
private fun LinkRow(label: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).settingTarget(label).clickable(onClick = onClick).padding(horizontal = 16.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.subheadline, Modifier.weight(1f))
        com.ketvoice.app.ui.common.Icon(KetIcons.ChevronRight, null, Modifier.size(12.dp), tint = LocalKet.current.tertiary)
    }
}
