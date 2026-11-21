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

/** Why the last sync round failed: the account sheet tells the listener which one, not always « offline ». */
enum class SyncProblem { NONE, OFFLINE, SIGNED_OUT, SERVER }

/** Where the library's sync stands, for the account sheet. */
data class SyncStatus(val running: Boolean = false, val lastSuccess: Long = 0L, val problem: SyncProblem = SyncProblem.NONE) {
    val offline: Boolean get() = problem == SyncProblem.OFFLINE
}

/** The server answered a library call with an error status (not a network failure). */
internal class CloudHttpError(val status: Int, message: String) : IOException(message)

internal object SyncProblems {
    /**
     * Only a failure to reach the server is « offline ». A refused session asks to sign in again; anything
     * else (an error status, an unexpected answer, a bug such as network on the main thread) is a server or
     * app problem that the next round retries: it must never read as « offline » on a working Wi-Fi.
     */
    fun of(error: Throwable): SyncProblem {
        var e: Throwable? = error
        repeat(4) {
            when (val cur = e ?: return SyncProblem.SERVER) {
                is com.ketvoice.app.account.SessionRejected -> return SyncProblem.SIGNED_OUT
                is CloudHttpError -> return if (cur.status == 401 || cur.status == 403) SyncProblem.SIGNED_OUT else SyncProblem.SERVER
                is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException,
                is java.net.SocketTimeoutException, is java.net.SocketException, is javax.net.ssl.SSLHandshakeException -> return SyncProblem.OFFLINE
                else -> e = cur.cause?.takeIf { it !== cur }
            }
        }
        return SyncProblem.SERVER
    }
}

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
    private var poller: Job? = null
    private var libraryToken: String? = null
    private var foregroundRefreshAt = 0L
    /** The account's table; host tests put a fake one here. */
    internal var cloud: CloudPort = RealCloud
    private const val POLL_MS = 3 * 60_000L

    internal fun signedIn(context: Context, fresh: Boolean) {
        this.context = context.applicationContext
        SyncedTracks.init(context)
        _status.value = SyncStatus(lastSuccess = SyncStore.lastSuccess(context))
        watch(context.applicationContext)
        request(if (fresh) 0L else 1_500L)
    }

    internal fun signedOut(context: Context) {
        pending?.cancel(); watchers?.cancel(); watchers = null; poller?.cancel(); poller = null; libraryToken = null
        prefsListener?.let { context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
        SyncStore.forget(context)
        SyncedTracks.forget(context)
        com.ketvoice.app.remote.RemoteKeyStore.forget(context)
        _status.value = SyncStatus()
    }

    /**
     * The app came to the front: what other devices (or the web player) changed meanwhile comes in: the
     * library, the profile (name, photo) and the catalogue's Canvas and covers. While it stays in front, a
     * small check of the account's revisions every few minutes brings later changes.
     */
    fun onForeground() {
        val now = System.currentTimeMillis()
        if (now - foregroundRefreshAt > 60_000L) {
            foregroundRefreshAt = now
            // Public catalogue overrides (Canvas, covers): their files are named per upload, so a new one is a new address.
            scope.launch { runCatching { com.ketvoice.app.data.MediaOverrides.refresh() } }
        }
        if (AccountSession.userId() == null) return
        request(800L); SharedPlaylists.onForeground()
        scope.launch { runCatching { com.ketvoice.app.account.Profiles.refresh() } }
        poller?.cancel()
        poller = scope.launch { while (true) { delay(POLL_MS); runCatching { poll() } } }
    }

    /** The app goes to the background: what changed here goes out now. */
    fun onBackground() {
        poller?.cancel(); poller = null
        if (AccountSession.userId() != null) { request(0L); SharedPlaylists.onBackground() }
    }

    /** One small read (each kind's revision); a full round only when another device wrote since the last look. */
    private suspend fun poll() {
        if (AccountSession.userId() == null) return
        val token = CloudLibrary.revisions(kinds.map { it.name })
        if (ChangeToken.changed(libraryToken, token)) request(0L)
        libraryToken = token
        // The profile row is as small; its photo is named per upload, so only a new photo is downloaded.
        com.ketvoice.app.account.Profiles.refresh()
    }

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

    /**
     * The « Synchroniser » button. It is called from the screen's (main-thread) scope: the round runs on
     * the IO dispatcher, or Android refuses the network call on the main thread, which used to be caught
     * and shown as « offline » on a working Wi-Fi. A manual round starts the retry delay over.
     */
    suspend fun syncNow(): SyncProblem {
        pending?.cancel()
        failures = 0
        return syncAll()
    }

    private suspend fun syncAll(): SyncProblem = withContext(Dispatchers.IO) { mutex.withLock {
        val ctx = context ?: return@withLock SyncProblem.NONE
        val uid = AccountSession.userId() ?: return@withLock SyncProblem.SIGNED_OUT
        _status.value = _status.value.copy(running = true)
        try {
            val round = SyncRound(cloud, DeviceIdentity.id(ctx), ServerClock::now)
            val again = kinds.map { kind -> syncKind(ctx, uid, kind, round) }.any { it }
            failures = 0
            val at = System.currentTimeMillis()
            SyncStore.markSuccess(ctx, at)
            _status.value = SyncStatus(lastSuccess = at)
            if (again) request()
            SyncProblem.NONE
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val problem = SyncProblems.of(error)
            _status.value = _status.value.copy(running = false, problem = problem)
            // A refused session waits for the listener to sign in again; the rest is retried later.
            if (problem != SyncProblem.SIGNED_OUT) retryLater()
            problem
        }
    } }

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
