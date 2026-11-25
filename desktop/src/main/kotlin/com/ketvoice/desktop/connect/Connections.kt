package com.ketvoice.desktop.connect

import android.content.Context
import com.ketvoice.desktop.platform.AppLog
import kotlinx.coroutines.CoroutineScope

/**
 * Windows 2.0 « Connexions », started behind the window: Discord's status, scrobbling, live sync between devices, and
 * the page and settings in the shell. Each part sends nothing until it is used (see « Mes données »).
 */
object Connections {
    fun start(context: Context, scope: CoroutineScope) {
        ConnectPrefs.init(context)
        javax.swing.SwingUtilities.invokeLater { com.ketvoice.desktop.ui.ConnectUi.register() }
        runCatching { DiscordPresence.start(scope) }.onFailure { AppLog.w("connect", "discord: $it") }
        runCatching { Scrobbler.start(context, scope) }.onFailure { AppLog.w("connect", "scrobbler: $it") }
        runCatching { LiveSync.start(context, scope) }.onFailure { AppLog.w("connect", "live sync: $it") }
        AppLog.i("connect", "profile ${LocalProfiles.current}")
    }

    /** Quitting: Discord forgets the activity at once. */
    fun quit() { runCatching { DiscordPresence.stop() } }
}
