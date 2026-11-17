package com.ketvoice.app.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.compose.runtime.snapshotFlow
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.ServerClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Where the library's sync stands, for the account sheet. */
data class SyncStatus(val running: Boolean = false, val lastSuccess: Long = 0L, val offline: Boolean = false)

/** What the cloud side of a sync round needs (the real table, or a fake one in tests). */
internal interface CloudPort {
    suspend fun fetch(kind: String): CloudDoc?
    suspend fun insert(kind: String, doc: JSONObject, deviceId: String): Boolean
    suspend fun update(kind: String, doc: JSONObject, revision: Long, deviceId: String): Boolean
}

internal object RealCloud : CloudPort {
    override suspend fun fetch(kind: String) = CloudLibrary.fetch(kind)
    override suspend fun insert(kind: String, doc: JSONObject, deviceId: String) = CloudLibrary.insert(kind, doc, deviceId)
    override suspend fun update(kind: String, doc: JSONObject, revision: Long, deviceId: String) =
        CloudLibrary.update(kind, doc, revision, deviceId)
}

/**
 * One kind, one round: this device's changes since [previous] are stamped, merged with the cloud's copy
 * (union, newest per item, tombstones) and written back naming the revision read; when another device
 * wrote in between, the round reads and merges again. Returns the merged document both sides now hold.
 */
internal class SyncRound(private val cloud: CloudPort, private val deviceId: String, private val now: () -> Long) {
    suspend fun run(kind: String, previous: SyncDoc?, snapshot: Map<String, String>): SyncDoc {
        val stamped = SyncMerge.stamp(previous ?: SyncDoc(), snapshot, now(), initial = previous == null)
        repeat(ATTEMPTS) {
            val remote = cloud.fetch(kind)
            val remoteDoc = SyncMerge.decode(remote?.doc)
            val merged = SyncMerge.prune(SyncMerge.merge(stamped, remoteDoc), now())
            if (write(kind, remote, remoteDoc, merged)) return merged
        }
        throw IOException("The library kept changing on another device")
    }

    private suspend fun write(kind: String, remote: CloudDoc?, remoteDoc: SyncDoc, merged: SyncDoc): Boolean = when {
        remote == null -> cloud.insert(kind, SyncMerge.encode(merged), deviceId)
        merged == remoteDoc -> true
        else -> cloud.update(kind, SyncMerge.encode(merged), remote.revision, deviceId)
    }

    companion object { const val ATTEMPTS = 4 }
}

/**
 * The library follows the account across the listener's devices (phone, tablet, Quest): favourites,
 * playlists and their order, favourite and blocked artists, the settings that belong to the listener.
 * Only while signed in. Runs at sign-in (merging what this device and the account hold), when the app
 * comes to the front, and a few seconds after each change; without network, changes wait on the device.
 */
object LibrarySync {
    private const val DEBOUNCE_MS = 4_000L
    private val kinds: List<SyncKind> = listOf(FavoritesKind, PlaylistsKind, ArtistsKind, SettingsKind)
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null
    private var watchers: Job? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var context: Context? = null
    private var failures = 0

    internal fun signedIn(context: Context, fresh: Boolean) {
        this.context = context.applicationContext
        SyncedTracks.init(context)
        _status.value = _status.value.copy(lastSuccess = SyncStore.lastSuccess(context))
        watch(context.applicationContext)
        request(if (fresh) 0L else 1_500L)
    }

    internal fun signedOut(context: Context) {
        pending?.cancel(); watchers?.cancel(); watchers = null
        prefsListener?.let { context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
        SyncStore.forget(context)
        SyncedTracks.forget(context)
        _status.value = SyncStatus()
    }

    /** The app came to the front: what other devices changed meanwhile comes in. */
    fun onForeground() { if (AccountSession.userId() != null) request(800L) }

    /** The app goes to the background: what changed here goes out now. */
    fun onBackground() { if (AccountSession.userId() != null) request(0L) }

    fun request(delayMs: Long = DEBOUNCE_MS) {
        if (context == null || AccountSession.userId() == null) return
        pending?.cancel()
        pending = scope.launch { delay(delayMs); runCatching { syncAll() } }
    }

    /** Before signing out: the last changes are sent, for at most a few seconds. */
    internal suspend fun flush() {
        pending?.cancel()
        withTimeoutOrNull(5_000) { runCatching { syncAll() } }
    }

    private fun watch(context: Context) {
        if (watchers != null) return
        watchers = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            launch { snapshotFlow { com.ketvoice.app.data.AppSettings.favoriteIds.value }.drop(1).collect { request() } }
            launch { com.ketvoice.app.data.PlaylistStore.lists.drop(1).collect { request() } }
            launch { snapshotFlow { com.ketvoice.app.data.ArtistPrefs.blocked to com.ketvoice.app.data.ArtistPrefs.favourites }.drop(1).collect { request() } }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in SettingsKind.keys) request() }
        prefsListener = listener
        context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
    }

    suspend fun syncNow() = syncAll()

    private suspend fun syncAll() = mutex.withLock {
        val ctx = context ?: return@withLock
        val uid = AccountSession.userId() ?: return@withLock
        _status.value = _status.value.copy(running = true)
        try {
            val round = SyncRound(RealCloud, DeviceIdentity.id(ctx), ServerClock::now)
            val again = kinds.map { kind -> syncKind(ctx, uid, kind, round) }.any { it }
            failures = 0
            val at = System.currentTimeMillis()
            SyncStore.markSuccess(ctx, at)
            _status.value = SyncStatus(lastSuccess = at)
            if (again) request()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            _status.value = _status.value.copy(running = false, offline = true)
            retryLater()
        }
    }

    /** Returns true when this device changed during the round: its changes go out in the next one. */
    private suspend fun syncKind(context: Context, uid: String, kind: SyncKind, round: SyncRound): Boolean {
        val previous = SyncStore.read(context, uid, kind.name)
        val snapshot = withContext(Dispatchers.Main) { kind.snapshot(context, previous ?: SyncDoc()) }
        val merged = round.run(kind.name, previous, snapshot)
        if (merged.live != snapshot) {
            val still = withContext(Dispatchers.Main) { kind.snapshot(context, previous ?: SyncDoc()) }
            if (still != snapshot) return true
            withContext(Dispatchers.Main) { kind.apply(context, merged.live) }
        }
        SyncStore.write(context, uid, kind.name, merged)
        return false
    }

    private fun retryLater() {
        failures = (failures + 1).coerceAtMost(5)
        request(30_000L * (1L shl (failures - 1)))
    }
}

/** This device's copy of each kind as last agreed with the account, to tell what changed here since. */
internal object SyncStore {
    private fun dir(context: Context) = File(context.filesDir, "account/sync").apply { mkdirs() }

    fun read(context: Context, uid: String, kind: String): SyncDoc? = runCatching {
        val file = AtomicFile(File(dir(context), "$kind.json"))
        if (!file.baseFile.exists()) return null
        val json = JSONObject(String(file.readFully(), Charsets.UTF_8))
        if (json.optString("user") != uid) return null
        SyncMerge.decode(json.getJSONObject("doc"))
    }.getOrNull()

    fun write(context: Context, uid: String, kind: String, doc: SyncDoc) {
        val file = AtomicFile(File(dir(context), "$kind.json"))
        val bytes = JSONObject().put("user", uid).put("doc", SyncMerge.encode(doc)).toString().toByteArray(Charsets.UTF_8)
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (error: Exception) { file.failWrite(out); throw error }
    }

    fun lastSuccess(context: Context): Long =
        context.getSharedPreferences("ketvoice-account-sync", Context.MODE_PRIVATE).getLong("lastSuccess", 0L)

    fun markSuccess(context: Context, at: Long) {
        context.getSharedPreferences("ketvoice-account-sync", Context.MODE_PRIVATE).edit().putLong("lastSuccess", at).apply()
    }

    fun forget(context: Context) {
        dir(context).deleteRecursively()
        context.getSharedPreferences("ketvoice-account-sync", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
