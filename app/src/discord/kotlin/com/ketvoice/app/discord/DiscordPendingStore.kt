package com.ketvoice.app.discord

import android.content.Context
import com.ketvoice.app.KetvoiceApp

/**
 * Remembers an authorization in flight. Android can destroy the process while the
 * browser or WebView is in front; without this the redirect came back to a fresh
 * process whose `awaitingCode` was false, so Ketvoice returned "not connected".
 * Nothing secret is stored: the PKCE verifier is useless without the live code,
 * and it is erased as soon as the callback is handled.
 */
internal class DiscordPendingStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("ketvoice_discord", Context.MODE_PRIVATE)

    fun save(state: String, verifier: String) {
        prefs.edit()
            .putString(STATE, state)
            .putString(VERIFIER, verifier)
            .putLong(AT, System.currentTimeMillis())
            .apply()
    }

    fun state(): String? = prefs.getString(STATE, null)?.takeIf { it.isNotBlank() }

    fun verifier(): String? = prefs.getString(VERIFIER, null)?.takeIf { it.isNotBlank() }

    /** A pending authorization older than five minutes is stale by Discord rules. */
    fun fresh(now: Long = System.currentTimeMillis()): Boolean {
        val at = prefs.getLong(AT, 0L)
        return at > 0 && now - at in 0..STALE_MS && state() != null && verifier() != null
    }

    fun clear() {
        prefs.edit().remove(STATE).remove(VERIFIER).remove(AT).apply()
    }

    private companion object {
        const val STATE = "pending_state"
        const val VERIFIER = "pending_verifier"
        const val AT = "pending_at"
        const val STALE_MS = 5 * 60_000L
    }
}
