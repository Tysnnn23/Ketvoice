package com.ketvoice.desktop.connect

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.ketvoice.app.data.SecureSessionStore

/**
 * The « Connexions » settings of this PC (profile), in their own preferences file: never part of the synced settings
 * document, never exported with them. Secrets (the ListenBrainz token) go through [SecureSessionStore] (DPAPI on
 * Windows), never into these preferences.
 */
object ConnectPrefs {
    private const val FILE = "ketvoice_connections"
    private const val LB_TOKEN = "listenbrainz-token"
    private var context: Context? = null
    private val secrets by lazy { context?.let { runCatching { SecureSessionStore(it) }.getOrNull() } }

    /** « Statut Discord »: on by default (owner's pick), shown only while a title plays. */
    val discord = mutableStateOf(true)
    /** The Discord user the local Discord app last named (display only; the account's other devices see it, 9.2). */
    val discordUser = mutableStateOf("")
    /** « Masquer le titre »: Discord shows that Ketvoice plays, not what. */
    val discordHideTitle = mutableStateOf(false)
    /** « Synchronisation en direct »: this device's playback goes to the account while signed in. */
    val liveSync = mutableStateOf(true)
    val lastfmOn = mutableStateOf(true)
    val listenBrainzOn = mutableStateOf(true)
    /** Display only: the names the services gave. */
    val lastfmUser = mutableStateOf("")
    val listenBrainzUser = mutableStateOf("")
    /** When each service last accepted something (epoch ms, 0 never). */
    val lastfmSyncAt = mutableLongStateOf(0L)
    val listenBrainzSyncAt = mutableLongStateOf(0L)

    fun init(context: Context) {
        if (this.context != null) return
        this.context = context
        val p = prefs() ?: return
        discord.value = p.getBoolean("discord", true)
        discordHideTitle.value = p.getBoolean("discordHideTitle", false)
        discordUser.value = p.getString("discordUser", "").orEmpty()
        liveSync.value = p.getBoolean("liveSync", true)
        lastfmOn.value = p.getBoolean("lastfmOn", true)
        listenBrainzOn.value = p.getBoolean("listenBrainzOn", true)
        lastfmUser.value = p.getString("lastfmUser", "").orEmpty()
        listenBrainzUser.value = p.getString("listenBrainzUser", "").orEmpty()
        lastfmSyncAt.longValue = p.getLong("lastfmSyncAt", 0L)
        listenBrainzSyncAt.longValue = p.getLong("listenBrainzSyncAt", 0L)
    }

    private fun prefs() = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun set(state: MutableState<Boolean>, key: String, on: Boolean) {
        adopt(state, key, on)
        // « Statut Discord » follows the account (9.2): the other devices take the latest choice.
        if (key == "discord") runCatching { com.ketvoice.app.sync.LibrarySync.connectionsChanged() }
    }

    /** A value that came from the account: kept here, not sent back. */
    fun adopt(state: MutableState<Boolean>, key: String, on: Boolean) {
        state.value = on
        prefs()?.edit()?.putBoolean(key, on)?.apply()
    }

    /** Whether the listener ever set [key] on this PC (a default never overrides the other devices' choice). */
    fun chosen(key: String): Boolean = prefs()?.contains(key) == true

    fun setText(state: MutableState<String>, key: String, value: String) {
        state.value = value
        prefs()?.edit()?.putString(key, value)?.apply()
    }

    fun synced(lastfm: Boolean, at: Long = System.currentTimeMillis()) {
        if (lastfm) lastfmSyncAt.longValue = at else listenBrainzSyncAt.longValue = at
        prefs()?.edit()?.putLong(if (lastfm) "lastfmSyncAt" else "listenBrainzSyncAt", at)?.apply()
    }

    fun listenBrainzToken(): String? = secrets?.get(LB_TOKEN)?.takeIf { it.isNotBlank() }

    fun setListenBrainzToken(token: String?) {
        val store = secrets ?: return
        if (token.isNullOrBlank()) store.remove(LB_TOKEN) else store.put(LB_TOKEN, token)
    }
}
