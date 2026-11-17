package com.ketvoice.app.sync

import android.content.Context
import android.util.AtomicFile
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.RealRestHttp
import com.ketvoice.app.account.RestRejected
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.PlaylistStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID

/** The state of the shared playlists in memory: read by everyone, changed one writer at a time. */
internal class SharedStateBox(initial: SharedState = SharedState()) {
    private val lock = Any()
    @Volatile var value: SharedState = initial
        private set
    /** The writes now on their way to the server: an edit never cancels them, it follows them. */
    @Volatile var sending: List<SharedOp> = emptyList()
    /** The lists whose row is in the Library (see [SharedMerge.edit]). */
    @Volatile var shown: Set<String> = emptySet()

    fun update(block: (SharedState) -> SharedState): SharedState = synchronized(lock) { block(value).also { value = it } }
}

/**
 * Reads the shared playlists from the server and sends this device's writes (they are kept in [box] until the
 * server accepted them, so what was done offline goes out later). No Android in here: tests drive it with a
 * fake [client].
 */
internal class SharedEngine(private val client: SharedClient, private val box: SharedStateBox, private val changed: () -> Unit) {
    private val flushLock = Mutex()
    private val refreshLock = Mutex()

    /** The Library's playlists changed: what that means for the shared ones is queued. True when the store must be put right. */
    fun onEdit(lists: List<LocalPlaylist>): Boolean {
        var remirror = false
        val before = box.value
        val after = box.update { state ->
            SharedMerge.edit(state, lists, box.shown, box.sending).also { remirror = it.remirror }.state
        }
        if (after != before) changed()
        return remirror
    }

    /** Sends the queue in order; false when the network stopped it (the rest stays queued). */
    suspend fun flush(): Boolean = flushLock.withLock { flushQueue() }

    private suspend fun flushQueue(): Boolean {
        while (true) {
            val first = box.value.ops.firstOrNull() ?: return true
            val batch = if (first is SharedOp.Add) box.value.ops.takeWhile { it is SharedOp.Add && it.list == first.list }.take(BATCH) else listOf(first)
            box.sending = batch
            try {
                if (first is SharedOp.Add) client.addAll(batch.filterIsInstance<SharedOp.Add>()) else client.send(first)
            } catch (refused: RestRejected) {
                // The server will never accept it (the playlist is gone, we left it…): drop it, the next read puts the view right.
            } catch (stopped: CancellationException) {
                box.sending = emptyList(); throw stopped
            } catch (offline: Exception) {
                box.sending = emptyList(); return false
            }
            box.update { state -> state.copy(ops = state.ops.toMutableList().also { rest -> batch.forEach { rest.remove(it) } }) }
            box.sending = emptyList()
            changed()
        }
    }

    /** Sends what waits, then reads every shared playlist; writes still waiting are shown on top. */
    suspend fun refresh(): Boolean = refreshLock.withLock {
        flush()
        val heads = client.lists()
        val fetched = heads.map { it to client.rows(it.id) }
        box.update { state ->
            val leaving = state.ops.filter { it is SharedOp.Delete || it is SharedOp.Leave }.map { it.list }.toSet()
            val lists = LinkedHashMap<String, SharedList>()
            fetched.filter { it.first.id !in leaving }.forEach { (head, rows) -> lists[head.id] = SharedList(head.id, head.name, head.owner, rows) }
            val unsent = state.ops.filterIsInstance<SharedOp.Create>().map { it.list }.toSet()
            state.lists.filter { it.key in unsent }.forEach { lists.putIfAbsent(it.key, it.value) }
            state.copy(lists = lists, ops = state.ops.filter { it.list in lists || it.list in leaving })
        }
        changed()
        true
    }

    /** Puts a whole playlist on the server (it becomes shared): created, then its titles in order. */
    fun share(id: String, name: String, tracks: List<com.ketvoice.app.data.Track>) {
        box.update { state ->
            val create = SharedOp.Create(id, name)
            val adds = tracks.mapIndexed { index, track -> SharedOp.Add(id, UUID.randomUUID().toString(), track, (index + 1).toDouble()) }
            state.copy(lists = state.lists + (id to SharedList(id, name, state.user, emptyList())), ops = state.ops + create + adds)
        }
        changed()
    }

    private companion object { const val BATCH = 50 }
}

/** Where the shared playlists stand, for the Library and the sheets. */
data class SharedStatus(val pending: Int = 0, val offline: Boolean = false, val lastRead: Long = 0L)

/**
 * Playlists several Ketvoice accounts edit together (6.3). They show in the Library like any playlist (a
 * « Partagée » badge) under the id `shared:<server id>`, play like any other, and every edit made to them
 * there is turned into a write for the server at once (queued on the device while offline). They are read
 * when the Library opens and every few minutes while the app is in front. They never enter the personal
 * library sync. Rules: [SharedMerge]; server: [SharedClient] and the migration of 2026-11-18.
 */
object SharedPlaylists {
    private const val EVERY_MS = 4 * 60_000L
    private const val STALE_MS = 20_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val box = SharedStateBox()
    private var context: Context? = null
    private var engine: SharedEngine? = null
    private var loop: Job? = null
    private var retry: Job? = null
    private var failures = 0
    private val writeLock = Mutex()
    @Volatile private var foreground = true
    @Volatile private var lastRead = 0L
    private val _status = MutableStateFlow(SharedStatus())
    val status: StateFlow<SharedStatus> = _status

    private fun file(context: Context) = AtomicFile(File(File(context.filesDir, "account").apply { mkdirs() }, "shared-playlists.json"))

    internal fun signedIn(context: Context) {
        val uid = AccountSession.userId() ?: return
        this.context = context.applicationContext
        val saved = runCatching { file(context).takeIf { it.baseFile.exists() }?.readFully()?.toString(Charsets.UTF_8) }.getOrNull()
            ?.let(SharedSnapshot::decode)?.takeIf { it.user == uid }
        box.update { saved ?: SharedState(user = uid) }
        engine = SharedEngine(SharedClient(RealRestHttp) { uid }, box, ::changed)
        PlaylistStore.sharedHook = ::onStoreChanged
        // What the Library already shows of the saved lists is theirs; a list it lacks is never taken for deleted.
        box.shown = box.value.lists.keys.filter { id -> PlaylistStore.lists.value.any { it.id == PlaylistRules.sharedLocalId(id) } }.toSet()
        loop?.cancel()
        loop = scope.launch {
            mirror()
            refreshNow()
            while (isActive) { delay(EVERY_MS); if (foreground) refreshNow() }
        }
    }

    internal fun signedOut(context: Context) {
        loop?.cancel(); retry?.cancel()
        PlaylistStore.sharedHook = null
        engine = null
        box.update { SharedState() }
        box.shown = emptySet()
        runCatching { file(context).delete() }
        _status.value = SharedStatus()
        scope.launch { runCatching { PlaylistStore.putShared { emptyList() } } }
    }

    fun onForeground() { foreground = true; refreshSoon() }
    fun onBackground() { foreground = false; scope.launch { runCatching { engine?.flush() } } }

    /** The Library opened (or came back): read now unless it was read a moment ago. */
    fun refreshSoon() {
        if (engine == null || System.currentTimeMillis() - lastRead < STALE_MS) return
        scope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        val engine = engine ?: return
        try {
            engine.refresh()
            lastRead = System.currentTimeMillis(); failures = 0
            _status.value = _status.value.copy(offline = false, lastRead = lastRead)
            mirror()
        } catch (stopped: CancellationException) { throw stopped
        } catch (offline: Exception) {
            _status.value = _status.value.copy(offline = true)
            retryLater()
        }
    }

    private fun retryLater() {
        failures = (failures + 1).coerceAtMost(5)
        retry?.cancel()
        retry = scope.launch { delay(30_000L * (1L shl (failures - 1))); refreshNow() }
    }

    /** Persists the state and tells the sheets how many writes wait. Called after every change of the state. */
    private fun changed() {
        _status.value = _status.value.copy(pending = box.value.ops.size)
        val ctx = context ?: return
        val text = SharedSnapshot.encode(box.value)
        scope.launch {
            writeLock.withLock {
                val target = file(ctx)
                val out = target.startWrite()
                try { out.write(text.toByteArray(Charsets.UTF_8)); target.finishWrite(out) } catch (error: Exception) { target.failWrite(out) }
            }
        }
    }

    /** Runs inside the playlist store's lock after every committed change. */
    private fun onStoreChanged(lists: List<LocalPlaylist>) {
        val engine = engine ?: return
        val remirror = engine.onEdit(lists)
        if (box.value.ops.isNotEmpty()) scope.launch {
            if (engine.flush()) { failures = 0 } else retryLater()
        }
        if (remirror) scope.launch { mirror() }
    }

    /** The Library's rows for the shared playlists follow the state (the server's lists with the unsent writes on top). */
    private suspend fun mirror() {
        runCatching { PlaylistStore.putShared { current -> mirrorRows(current) } }
    }

    private fun mirrorRows(current: List<LocalPlaylist>): List<LocalPlaylist> {
        val state = box.value
        return SharedMerge.mirror(state, current).also { box.shown = state.lists.keys.toSet() }
    }

    fun isOwner(localId: String): Boolean {
        val id = PlaylistRules.sharedServerId(localId) ?: return false
        return box.value.lists[id]?.owner == box.value.user
    }

    /**
     * « Partager la playlist » on a personal playlist: its titles (those another account can hear) move to a
     * new shared playlist the listener owns, which replaces the personal one. Returns the shared playlist's id.
     */
    suspend fun share(playlist: LocalPlaylist): String {
        val engine = engine ?: throw IOException("Connexion requise")
        val id = UUID.randomUUID().toString()
        val tracks = playlist.entries.mapNotNull { it.track }.filter(SharedTracks::shareable).distinctBy { it.id }.take(SharedMerge.MAX_TRACKS)
        // Inside the store's lock: no edit can come between the new list and its row.
        PlaylistStore.putShared { current ->
            engine.share(id, PlaylistRules.name(playlist.name), tracks)
            mirrorRows(current)
        }
        if (!PlaylistRules.isShared(playlist.id)) PlaylistStore.delete(playlist.id)
        scope.launch { if (engine.flush()) failures = 0 else retryLater() }
        return PlaylistRules.sharedLocalId(id)
    }

    /** A code for the playlist; what waits to be sent goes first so that the server knows the playlist. */
    suspend fun invite(localId: String): SharedInvite {
        val id = PlaylistRules.sharedServerId(localId) ?: throw IOException("Playlist invalide")
        val engine = engine ?: throw IOException("Connexion requise")
        if (!engine.flush()) throw IOException("Hors ligne")
        return SharedClient(RealRestHttp) { box.value.user }.invite(id)
    }

    suspend fun members(localId: String): List<SharedMember> {
        val id = PlaylistRules.sharedServerId(localId) ?: throw IOException("Playlist invalide")
        return SharedClient(RealRestHttp) { box.value.user }.members(id)
    }

    suspend fun removeMember(localId: String, userId: String) {
        val id = PlaylistRules.sharedServerId(localId) ?: throw IOException("Playlist invalide")
        SharedClient(RealRestHttp) { box.value.user }.removeMember(id, userId)
    }

    /** Joins with a code (typed or from ketvoice://join/<code>) and reads the playlist in. */
    suspend fun join(code: String): JoinResult {
        val uid = AccountSession.userId() ?: throw IOException("Connexion requise")
        val result = SharedClient(RealRestHttp) { uid }.join(code)
        if (result.status == "joined" || result.status == "already") refreshNow()
        return result
    }

    /** Leave (or, for the owner, delete) a shared playlist: removing it from the Library is what asks for it. */
    suspend fun leave(localId: String) { PlaylistStore.delete(localId) }

    /** For host renders and tests. */
    internal fun showForTest(state: SharedState) { box.update { state } }
}
