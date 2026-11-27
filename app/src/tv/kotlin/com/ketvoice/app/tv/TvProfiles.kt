package com.ketvoice.app.tv

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.ketvoice.app.account.AccountProfile
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.AccountState
import com.ketvoice.app.account.Profiles
import com.ketvoice.app.account.SupabaseHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File

/**
 * The profiles of this TV at run time: the book ([TvProfileStore]) as a state, the switch done at the start of the
 * process ([TvProfileMount]), adding a profile from a QR sign-in, switching (a restart, [TvRestart]) and removing.
 */
object TvProfiles {
    /** The area of the data of before any profile (signed out). */
    const val NOBODY = "nobody"
    const val STORE_DIR = "tv-profiles"

    private val _book = MutableStateFlow(TvProfileBook())
    val book: StateFlow<TvProfileBook> = _book

    /** « Qui écoute ? » is on screen. */
    val picking = MutableStateFlow(false)

    /** The profile whose removal is being confirmed (« Retirer ce profil ? »). */
    val confirming = MutableStateFlow<TvProfile?>(null)

    /** Bumped when a profile's photo copy changes, so the picker draws it again. */
    val avatars = MutableStateFlow(0)

    /** A QR sign-in added this profile: once the sign-in page said so, the TV restarts into it ([finishAdd]). */
    @Volatile var added: String? = null
        private set

    @Volatile private var store: TvProfileStore? = null
    @Volatile private var watching = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How a QR sign-in ended. */
    enum class Outcome { InPlace, Already, Added }

    fun storeOf(context: Context) = TvProfileStore(File(dataDir(context), STORE_DIR))

    private fun dataDir(context: Context): File = context.filesDir.parentFile ?: context.filesDir

    /**
     * The first thing the main process does ([TvProfileMount], before the settings or any list is read): a switch asked
     * before the restart is carried out here. A failure leaves the book as it was; the next start tries again.
     */
    fun atStart(context: Context) {
        val s = storeOf(context)
        store = s
        val loaded = s.load()
        _book.value = if (loaded.pending == null) loaded else runCatching {
            TvProfileSwitch.run(loaded, s, TvDataMount(dataDir(context)), SecureSlots(context), ::userOf)
        }.getOrDefault(loaded)
    }

    private fun userOf(text: String): String? = AccountSession.decode(text)?.userId

    private fun save(next: TvProfileBook) {
        _book.value = next
        runCatching { store?.save(next) }
    }

    fun avatarOf(id: String): File? = store?.avatar(id)?.takeIf { it.isFile }

    /** From TvActivity, once: the profile in use keeps its name, e-mail and photo in the book, for the picker. */
    fun watch(context: Context) {
        if (store == null) atStart(context)
        if (watching) return
        watching = true
        scope.launch {
            combine(AccountSession.state, Profiles.profile, Profiles.avatar) { a, p, f -> Triple(a, p, f) }
                .collect { (a, p, f) -> runCatching { remember(a, p, f) } }
        }
    }

    /** At launch: the picker when two listeners or more share the TV, except right after a switch. */
    fun launch() {
        val b = _book.value
        picking.value = b.asksAtLaunch && !b.skipPicker
        if (b.skipPicker) save(b.copy(skipPicker = false))
    }

    internal fun remember(account: AccountState, profile: AccountProfile, avatar: File?) {
        val uid = account.userId ?: return
        var b = _book.value
        // The first sign-in on this TV's data (or a TV of 1.0.0 already signed in): this data becomes that profile's.
        if (b.mounted == NOBODY && b.profiles.isEmpty()) b = b.copy(mounted = uid)
        if (b.mounted != uid) return
        val known = b.profiles.firstOrNull { it.id == uid }
        val next = TvProfile(uid, TvProfileBook.clean(profile.displayName, 40).ifBlank { known?.name.orEmpty() },
            account.email.ifBlank { known?.email.orEmpty() })
        if (known != next || b != _book.value) save(b.upsert(next))
        keepAvatar(uid, avatar, profile.avatarPath.isBlank())
    }

    private fun keepAvatar(uid: String, avatar: File?, none: Boolean) {
        val copy = store?.avatar(uid) ?: return
        when {
            avatar != null && avatar.isFile && (copy.length() != avatar.length() || copy.lastModified() < avatar.lastModified()) -> {
                copy.parentFile?.mkdirs()
                avatar.copyTo(copy, overwrite = true)
                avatars.value++
            }
            avatar == null && none && copy.exists() -> { copy.delete(); avatars.value++ }
        }
    }

    /**
     * A QR code was approved ([TvSignIn]): its account is signed in here when it is the profile whose data is in use (or
     * the TV's first), else kept as a new profile whose data starts empty, which the TV restarts into. The publishing
     * account is refused here too (the server already never signs it in this way).
     */
    suspend fun signInWithLink(context: Context, tokenHash: String): Outcome {
        remember(AccountSession.state.value, Profiles.profile.value, Profiles.avatar.value)
        val session = AccountSession.verifyLink(tokenHash)
        if (session.email.equals(com.ketvoice.app.data.PublisherAccess.EMAIL, ignoreCase = true)) {
            com.ketvoice.app.account.AuthApi.signOut(session.access)
            error(TvSignIn.DENIED)
        }
        val b = _book.value
        return when {
            AccountSession.userId() == session.userId -> { com.ketvoice.app.account.AuthApi.signOut(session.access); Outcome.Already }
            b.mounted == session.userId || (b.mounted == NOBODY && b.profiles.isEmpty() && AccountSession.userId() == null) -> {
                AccountSession.adopt(session); Outcome.InPlace
            }
            else -> { keep(context, session); Outcome.Added }
        }
    }

    private suspend fun keep(context: Context, session: com.ketvoice.app.account.AuthSession) = withContext(Dispatchers.IO) {
        val slots = SecureSlots(context)
        val slot = TvSessions.slot(session.userId)
        // The same account signed in again: its older session here ends, so only one stays alive.
        slots.get(slot)?.let(AccountSession::decode)?.let { com.ketvoice.app.account.AuthApi.signOut(it.access) }
        slots.put(slot, AccountSession.encode(session))
        val name = runCatching { fetchCard(session) }.getOrNull().orEmpty()
        val known = _book.value.profiles.firstOrNull { it.id == session.userId }
        save(_book.value.upsert(TvProfile(session.userId, name.ifBlank { known?.name.orEmpty() }, session.email)))
        added = session.userId
    }

    /** The new profile's name and photo, read with its own session (its row and photo are readable by it only). */
    private fun fetchCard(session: com.ketvoice.app.account.AuthSession): String {
        val r = SupabaseHttp.request("GET", "/rest/v1/ketvoice_profiles?select=display_name,avatar_url&id=eq.${session.userId}",
            token = session.access)
        if (!r.ok) return ""
        val row = JSONArray(r.text).optJSONObject(0) ?: return ""
        val path = if (row.isNull("avatar_url")) "" else row.optString("avatar_url")
        if (path.startsWith(session.userId + "/")) runCatching {
            val photo = SupabaseHttp.request("GET", "/storage/v1/object/authenticated/${Profiles.BUCKET}/$path", token = session.access)
            val copy = store?.avatar(session.userId)
            if (photo.ok && copy != null && photo.bytes.size <= com.ketvoice.app.account.AvatarImage.MAX_BYTES) {
                copy.parentFile?.mkdirs(); copy.writeBytes(photo.bytes); avatars.value++
            }
        }
        return if (row.isNull("display_name")) "" else TvProfileBook.clean(row.optString("display_name"), 40)
    }

    /** After the sign-in page showed « Connexion réussie »: the TV restarts on the profile just added. */
    fun finishAdd(activity: Activity): Boolean {
        val id = added ?: return false
        added = null
        switchTo(activity, id)
        return true
    }

    /**
     * « Qui écoute ? »: [id]'s data and session take the place of the current ones at a restart. Playback stops, the queue
     * is cleared and the library's last changes are sent first.
     */
    fun switchTo(activity: Activity, id: String) {
        val b = _book.value
        picking.value = false
        if (id == b.mounted || (id != NOBODY && !b.has(id))) return
        scope.launch {
            withContext(Dispatchers.Main) { runCatching { com.ketvoice.app.player.Player.pause() } }
            withTimeoutOrNull(5_000) { runCatching { com.ketvoice.app.sync.LibrarySync.flush() } }
            withContext(Dispatchers.Main) { runCatching { com.ketvoice.app.data.SettingsStore.save() } }
            save(_book.value.copy(pending = id, skipPicker = true))
            withContext(Dispatchers.Main) { TvRestart.now(activity) }
        }
    }

    /**
     * « Retirer ce profil »: signed out on the server and its data on this TV deleted (its account and online library
     * stay). The profile in use is removed at a restart onto another one (or the signed-out data).
     */
    suspend fun remove(activity: Activity, id: String) {
        val b = _book.value
        if (!b.has(id)) return
        if (id == b.mounted) {
            runCatching { AccountSession.signOut() }
            val next = b.profiles.firstOrNull { it.id != id }?.id ?: NOBODY
            withContext(Dispatchers.Main) { runCatching { com.ketvoice.app.player.Player.pause() } }
            save(_book.value.copy(pending = next, drop = id, skipPicker = true))
            withContext(Dispatchers.Main) { TvRestart.now(activity) }
            return
        }
        withContext(Dispatchers.IO) {
            val slots = SecureSlots(activity)
            slots.get(TvSessions.slot(id))?.let(AccountSession::decode)?.let { s ->
                // Its access token may have expired while it waited: renewed once, then ended.
                runCatching { com.ketvoice.app.account.AuthApi.refresh(s.refresh, System::currentTimeMillis) }.getOrNull()
                    ?.let { com.ketvoice.app.account.AuthApi.signOut(it.access) }
            }
            slots.remove(TvSessions.slot(id))
            store?.forget(id)
        }
        save(_book.value.copy(profiles = _book.value.profiles.filter { it.id != id }))
        avatars.value++
    }

    /** For host renders and tests: a book shown without files or Keystore. */
    internal fun showForTest(value: TvProfileBook, root: File? = null) {
        if (root != null) store = TvProfileStore(root)
        _book.value = value
    }
}

/** The account's encrypted store (Android Keystore), as [TvSessions] reads it. */
private class SecureSlots(context: Context) : TvSessionSlots {
    private val store by lazy { com.ketvoice.app.data.SecureSessionStore(context.applicationContext) }
    override fun get(name: String): String? = store.get(name)
    override fun put(name: String, value: String) = store.put(name, value)
    override fun remove(name: String) = store.remove(name)
}

/**
 * Runs first in the main process, before Application.onCreate and before any other provider (highest initOrder): the
 * profile switch moves files that the settings and lists read once per process.
 */
class TvProfileMount : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { runCatching { TvProfiles.atStart(it) } }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * A restart of Ketvoice TV (switching profiles): the main process asks this activity, in its own small process, to
 * end it and open the TV again, so the next process starts on the other profile's data (as ProcessPhoenix does).
 */
object TvRestart {
    const val PID = "ketvoice.mainPid"

    fun now(activity: Activity) {
        val me = android.os.Process.myPid()
        // The work process (separation) goes too: it starts again when needed, on the new profile's data.
        runCatching {
            activity.getSystemService(android.app.ActivityManager::class.java)?.runningAppProcesses
                ?.filter { it.pid != me && it.uid == android.os.Process.myUid() }?.forEach { android.os.Process.killProcess(it.pid) }
        }
        activity.startActivity(Intent(activity, TvRestartActivity::class.java).putExtra(PID, me).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        activity.finish()
        Runtime.getRuntime().exit(0)
    }
}

class TvRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(TvRestart.PID, -1)
        if (pid > 0 && pid != android.os.Process.myPid()) android.os.Process.killProcess(pid)
        startActivity(Intent(this, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        Runtime.getRuntime().exit(0)
    }
}
