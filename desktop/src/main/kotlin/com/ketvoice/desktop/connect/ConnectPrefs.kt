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
        state.value = on
        prefs()?.edit()?.putBoolean(key, on)?.apply()
    }

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
