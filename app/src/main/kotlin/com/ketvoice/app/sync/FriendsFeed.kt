package com.ketvoice.app.sync

import android.content.Context
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.RealRestHttp
import com.ketvoice.app.account.RestHttp
import com.ketvoice.app.account.RestRejected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** How the merging of pages works, apart so it can be tested. */
internal object FeedMerge {
    const val PAGE = 30

    /** Newest first; lines written in the same instant keep one order everywhere. */
    fun sorted(items: Collection<FeedItem>): List<FeedItem> =
        items.sortedWith(compareByDescending<FeedItem> { it.atMs }.thenByDescending { it.at }.thenByDescending { it.id })

    /**
     * A fresh first page over what is shown. A line comes once (the fresh copy wins: an « a ajouté » line moves
     * up when more titles are added). A shown line the page should hold but does not is gone (unpublished,
     * hidden, a block): only lines older than a full page are kept, for the pages already read below it.
     */
    fun head(shown: List<FeedItem>, page: List<FeedItem>, limit: Int = PAGE): List<FeedItem> {
        if (page.size < limit) return sorted(page.distinctBy { it.id })
        val ids = page.mapTo(HashSet()) { it.id }
        val oldest = page.minOf { it.atMs }
        return sorted(page.distinctBy { it.id } + shown.filter { it.id !in ids && it.atMs < oldest })
    }

    /** A further page below what is shown: each line once, the newer copy winning. */
    fun more(shown: List<FeedItem>, page: List<FeedItem>): List<FeedItem> {
        val byId = LinkedHashMap<String, FeedItem>()
        shown.forEach { byId[it.id] = it }
        page.forEach { byId[it.id] = it }
        return sorted(byId.values)
    }

    /** Where the next page starts: the server's own time text of the last line shown. */
    fun cursor(shown: List<FeedItem>): String? = shown.lastOrNull()?.at
}

/** The last first page, kept on the device to show the feed offline. Goes with the account at sign-out. */
internal class FeedCache(private val file: File) {
    fun load(): List<FeedItem> = runCatching {
        if (!file.exists()) return emptyList()
        FeedParser.page(file.readText(Charsets.UTF_8))
    }.getOrDefault(emptyList())

    fun save(items: List<FeedItem>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONArray(items.take(FeedMerge.PAGE).map(FeedParser::json)).toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    fun clear() { file.delete() }
}

/** Why the feed shows no fresh lines: no network (the cached page stays), or the server refused or is not ready. */
enum class FeedProblem { Offline, Unavailable }

data class FeedState(val items: List<FeedItem> = emptyList(), val loading: Boolean = false, val loadingMore: Boolean = false,
                     val endReached: Boolean = false, val problem: FeedProblem? = null, val loaded: Boolean = false,
                     /** When the lines shown were read from the server (0: only the cached page so far). */
                     val updatedAt: Long = 0L)

/** The feed's logic over a client and a cache (no Android, no session): what [FriendsFeed] runs and tests drive. */
internal class FeedModel(private val client: FriendsClient, private val cache: FeedCache?, private val now: () -> Long = System::currentTimeMillis) {
    val state = MutableStateFlow(FeedState(items = cache?.load().orEmpty()))
    private val lock = Mutex()

    private fun problem(error: Exception): FeedProblem = if (error is RestRejected) FeedProblem.Unavailable else FeedProblem.Offline

    suspend fun refresh(limit: Int = FeedMerge.PAGE) = lock.withLock {
        state.value = state.value.copy(loading = true)
        try {
            val page = client.feed(null, limit)
            val items = FeedMerge.head(state.value.items, page, limit)
            state.value = state.value.copy(items = items, loading = false, problem = null, loaded = true, updatedAt = now(),
                endReached = page.size < limit)
            cache?.save(items)
        } catch (stopped: CancellationException) { state.value = state.value.copy(loading = false); throw stopped
        } catch (error: Exception) { state.value = state.value.copy(loading = false, problem = problem(error)) }
    }

    suspend fun loadMore(limit: Int = FeedMerge.PAGE) = lock.withLock {
        val shown = state.value
        if (shown.endReached || shown.items.isEmpty() || shown.loadingMore) return@withLock
        state.value = shown.copy(loadingMore = true)
        try {
            val page = client.feed(FeedMerge.cursor(shown.items), limit)
            state.value = state.value.copy(items = FeedMerge.more(state.value.items, page), loadingMore = false, endReached = page.size < limit)
        } catch (stopped: CancellationException) { state.value = state.value.copy(loadingMore = false); throw stopped
        } catch (error: Exception) { state.value = state.value.copy(loadingMore = false, problem = problem(error)) }
    }

    /** The caller's reaction, shown at once; the server's counts replace it, and a failure puts the old one back. */
    suspend fun react(id: String, reaction: String?): Boolean {
        val before = state.value.items.firstOrNull { it.id == id } ?: return false
        val next = if (reaction == before.mine) null else reaction
        replace(FeedReactions.apply(before, next))
        return try {
            val answer = client.react(id, next)
            if (answer == null) { remove(id); false } else { replace(before.copy(reactions = answer.first, mine = answer.second)); true }
        } catch (stopped: CancellationException) { replace(before); throw stopped
        } catch (error: Exception) { replace(before); false }
    }

    private fun replace(item: FeedItem) {
        state.value = state.value.copy(items = state.value.items.map { if (it.id == item.id) item else it })
        cache?.save(state.value.items)
    }

    /** A line the server no longer shows (the author hid it or a block came between). */
    private fun remove(id: String) {
        state.value = state.value.copy(items = state.value.items.filterNot { it.id == id })
        cache?.save(state.value.items)
    }

    /** After unfollowing or blocking someone: their lines leave at once, before the next refresh confirms it. */
    fun forget(handle: String) {
        state.value = state.value.copy(items = state.value.items.filterNot { it.handle == handle })
        cache?.save(state.value.items)
    }
}

/**
 * « Activité de vos amis »: what the public profiles the listener follows publish and listen to. Read when
 * Home or the feed page opens and every few minutes while one of them is shown; the last page is kept on
 * the device for offline display. Nothing is read or kept while signed out.
 */
object FriendsFeed {
    /** How often a shown feed is read again. */
    const val EVERY_MS = 3 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var http: RestHttp = RealRestHttp
    private var context: Context? = null
    private var model: FeedModel? = null
    /** What a page shows while signed out. */
    internal val idle: StateFlow<FeedState> = MutableStateFlow(FeedState())
    private val _model = MutableStateFlow<FeedModel?>(null)
    private val _shareActivity = MutableStateFlow(true)
    /** « Publier mon activité » (on by default; only meaningful while the profile is public). */
    val shareActivity: StateFlow<Boolean> = _shareActivity

    internal fun client() = FriendsClient(http)
    private fun cacheFile(context: Context) = File(File(context.filesDir, "account"), "friends-feed.json")

    internal fun signedIn(context: Context) {
        this.context = context.applicationContext
        if (model == null) model = FeedModel(client(), FeedCache(cacheFile(context))).also { _model.value = it }
        _shareActivity.value = prefs()?.getBoolean("shareActivity", true) ?: true
        scope.launch { readShareActivity() }
    }

    internal fun signedOut(context: Context) {
        model = null; _model.value = null
        FeedCache(cacheFile(context)).clear()
        prefs()?.edit()?.remove("shareActivity")?.apply()
        _shareActivity.value = true
    }

    /** The model as a flow, so a page that opened before sign-in follows the account. */
    internal val current: StateFlow<FeedModel?> get() = _model

    private fun prefs() = context?.getSharedPreferences("ketvoice-account", Context.MODE_PRIVATE)

    // Beside the main thread (6.6): Home refreshes the feed from its own coroutine, and each refresh parses the
    // page and writes the cached copy of it to the disk.
    suspend fun refresh() { withContext(Dispatchers.IO) { if (AccountSession.userId() != null) model?.refresh() } }
    suspend fun loadMore() { withContext(Dispatchers.IO) { model?.loadMore() } }
    suspend fun react(id: String, reaction: String?): Boolean = withContext(Dispatchers.IO) { model?.react(id, reaction) ?: false }
    fun forget(handle: String) { model?.forget(handle) }

    /** Follows a public profile: following, already, unavailable, self, needs_handle or limit. */
    suspend fun follow(handle: String): String = client().follow(handle).also { if (it == "following") scope.launch { refresh() } }

    suspend fun unfollow(handle: String): String = client().unfollow(handle).also { forget(handle.removePrefix("@").lowercase()) }

    suspend fun block(handle: String): String = client().block(handle).also { if (it == "blocked") forget(handle.removePrefix("@").lowercase()) }

    suspend fun lists(): FollowLists = client().lists()

    /** unfollow, remove, block or unblock a line of the lists. */
    suspend fun manage(person: FollowPerson, action: String): String =
        client().manage(person.ref, action).also { if (it == "done" && action in setOf("unfollow", "block")) forget(person.handle) }

    private suspend fun readShareActivity() {
        val user = AccountSession.userId() ?: return
        runCatching { client().shareActivity(user) }.getOrNull()?.let { keepShareActivity(it) }
    }

    private fun keepShareActivity(on: Boolean) {
        _shareActivity.value = on
        prefs()?.edit()?.putBoolean("shareActivity", on)?.apply()
    }

    /** Saves « Publier mon activité » on the server, which stops writing (and showing) the activity when off. */
    suspend fun setShareActivity(on: Boolean) {
        val user = AccountSession.userId() ?: error("Connexion requise")
        client().setShareActivity(user, on)
        keepShareActivity(on)
    }

    /** For host renders: a feed shown without any network. */
    internal fun showForTest(state: FeedState) {
        val shown = FeedModel(client(), null).also { it.state.value = state }
        model = shown; _model.value = shown
    }

    internal fun resetForTest() { model = null; _model.value = null }
}
