package com.ketvoice.desktop.library

import android.content.Context
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.sync.LibrarySync
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The account on the desktop, as the phone runs it: the session comes back at start, the library
 * syncs then, and the shared [LibrarySync] looks at the account's revisions every few minutes (a
 * full round only when another device wrote), plus when the window comes to the front. A window
 * stays open for hours, so the look happens at most once a minute however often focus changes.
 */
object DesktopAccount {
    private const val FOREGROUND_EVERY_MS = 60_000L
    @Volatile private var lastForeground = 0L

    fun start(context: Context = Context.app) {
        AccountSession.init(context)
        foreground()
    }

    /** Ketvoice came to the front (start, window focus). */
    fun foreground(now: Long = System.currentTimeMillis()) {
        if (now - lastForeground < FOREGROUND_EVERY_MS) return
        lastForeground = now
        runCatching { LibrarySync.onForeground() }
    }

    /** Quitting: the last changes go out, for at most a few seconds. */
    fun quit() {
        if (AccountSession.userId() == null) return
        runBlocking { withTimeoutOrNull(4_000) { runCatching { LibrarySync.flush() } } }
    }
}
