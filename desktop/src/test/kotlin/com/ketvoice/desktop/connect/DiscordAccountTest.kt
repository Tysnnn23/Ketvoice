package com.ketvoice.desktop.connect

import android.content.Context
import com.ketvoice.app.sync.ConnectionsKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 9.2: what this PC tells the account about Discord, and how the account's choice lands in « Statut Discord ». */
class DiscordAccountTest {
    @Test fun thePcTellsItsLinkAndTakesTheAccountsChoice() {
        ConnectPrefs.init(Context(Files.createTempDirectory("ketvoice-discord").toFile()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            DiscordAccount.install(scope)
            ConnectPrefs.setText(ConnectPrefs.discordUser, "discordUser", "")
            val unlinked = DiscordAccount.link()
            assertEquals("windows", unlinked.platform)
            assertFalse(unlinked.linked); assertNull(unlinked.user)
            ConnectPrefs.setText(ConnectPrefs.discordUser, "discordUser", "ketfan")
            assertTrue(DiscordAccount.link().linked)
            assertEquals("ketfan", ConnectionsKind.localLink()?.user)
            // The account's choice is kept on this PC without being sent back as a new change.
            ConnectionsKind.setSharing(false)
            assertFalse(ConnectPrefs.discord.value)
            assertFalse(ConnectionsKind.sharing())
            assertTrue(ConnectionsKind.sharingChosen())
        } finally {
            ConnectPrefs.adopt(ConnectPrefs.discord, "discord", true)
            ConnectPrefs.setText(ConnectPrefs.discordUser, "discordUser", "")
            scope.cancel()
        }
    }
}
