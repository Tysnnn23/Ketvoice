package com.ketvoice.app.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * « Qui écoute ? » (Ketvoice TV 1.1): several Ketvoice accounts on one TV, each signed in here by QR code. Like the PC's
 * LocalProfiles, every profile has its own data (settings, library, Ketmix's settings, listening history, downloads),
 * while what is the same bytes for everyone (the stream cache, covers, Canvas, lyrics, Ketmix's measurements, the
 * models) is shared. The app's singletons read their files once per process, so a profile's data is put in the app's
 * own folders before anything opens them, at the start of the process ([TvDataMount]); switching restarts Ketvoice.
 * Everything here is plain files, tested on the JVM.
 */

/** One listener of this TV: a Ketvoice account ([id] is its user id) and what the picker shows of it. */
data class TvProfile(val id: String, val name: String = "", val email: String = "")

/**
 * The profiles of this TV. [mounted]: whose data is in the app's folders now ([TvProfiles.NOBODY]: the data of before
 * any profile, signed out). [pending]: whose data to put there at the next start, [drop]: a profile whose data goes at
 * that start (one removed while in use). [skipPicker]: the next start comes from a switch, the picker is not shown.
 */
data class TvProfileBook(
    val profiles: List<TvProfile> = emptyList(),
    val mounted: String = TvProfiles.NOBODY,
    val pending: String? = null,
    val drop: String? = null,
    val skipPicker: Boolean = false,
) {
    fun has(id: String): Boolean = profiles.any { it.id == id }

    /** The picker shows at launch once two listeners share the TV. */
    val asksAtLaunch: Boolean get() = profiles.size >= 2

    fun upsert(profile: TvProfile): TvProfileBook =
        copy(profiles = if (has(profile.id)) profiles.map { if (it.id == profile.id) profile else it } else profiles + profile)

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("mounted", mounted).put("pending", pending ?: JSONObject.NULL)
        .put("drop", drop ?: JSONObject.NULL).put("skipPicker", skipPicker)
        .put("profiles", JSONArray(profiles.map { JSONObject().put("id", it.id).put("name", it.name).put("email", it.email) }))

    companion object {
        private val ID = Regex("[0-9a-fA-F-]{36}")

        fun validId(id: String?): Boolean = id != null && ID.matches(id)

        /** A damaged or foreign file reads as an empty book; unknown ids are left out. */
        fun parse(text: String): TvProfileBook = runCatching {
            val json = JSONObject(text)
            val rows = json.optJSONArray("profiles") ?: JSONArray()
            val list = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { o ->
                o.optString("id").takeIf(::validId)?.let { TvProfile(it, clean(o.optString("name"), 40), clean(o.optString("email"), 200)) }
            }.distinctBy { it.id }
            fun area(key: String): String? = json.optString(key).takeIf { !json.isNull(key) && (validId(it) || it == TvProfiles.NOBODY) }
            TvProfileBook(list, area("mounted") ?: TvProfiles.NOBODY, area("pending"), area("drop")?.takeIf(::validId),
                json.optBoolean("skipPicker"))
        }.getOrDefault(TvProfileBook())

        fun clean(text: String, max: Int): String = text.filterNot(Char::isISOControl).trim().take(max)
    }
}

/** Where the profiles live: `book.json`, each stored profile's data under `areas/<id>`, a copy of each photo. */
class TvProfileStore(val root: File) {
    private val book = File(root, "book.json")

    fun load(): TvProfileBook = runCatching { TvProfileBook.parse(book.readText()) }.getOrDefault(TvProfileBook())

    /** Written whole and synced to the disk before it replaces the old file: a switch relies on it surviving a restart. */
    fun save(value: TvProfileBook) {
        root.mkdirs()
        val tmp = File(root, "book.json.tmp")
        java.io.FileOutputStream(tmp).use { out -> out.write(value.toJson().toString().toByteArray()); out.fd.sync() }
        java.nio.file.Files.move(tmp.toPath(), book.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    fun area(id: String): File = File(root, "areas/$id")

    fun avatar(id: String): File = File(root, "avatars/$id.jpg")

    /** A profile that leaves: its stored data and its photo (never the data in use, which is mounted elsewhere). */
    fun forget(id: String) {
        area(id).deleteRecursively()
        avatar(id).delete()
    }
}

/**
 * Puts one profile's data in the app's folders and takes it out again. Only `shared_prefs` and `files` are moved,
 * entry by entry, with a rename (the store is on the same disk): databases, the cache and what [shared] names stay for
 * everyone. Both moves are idempotent, so a start interrupted half way finishes the next time.
 */
class TvDataMount(private val dataDir: File) {
    /** Removed when a profile's data is put away: switching stops playback and clears the queue. */
    private val dropOnUnmount = setOf("playback-session-v1.json")

    fun shared(dir: String, name: String): Boolean = when (dir) {
        PREFS -> name.removeSuffix(".bak").removeSuffix(".xml").let { base -> base in SHARED_PREFS || SHARED_PREF_PREFIXES.any(base::startsWith) }
        else -> SHARED_FILES.any { name == it || name.startsWith("$it.") }
    }

    /** The data of whoever is mounted goes to [area]. */
    fun unmount(area: File) {
        for (dir in DIRS) {
            val from = File(dataDir, dir)
            val entries = from.listFiles().orEmpty().filterNot { shared(dir, it.name) }
            if (entries.isEmpty()) continue
            val to = File(area, dir).apply { mkdirs() }
            for (entry in entries) {
                if (dir == FILES && entry.name in dropOnUnmount) { entry.delete(); continue }
                move(entry, File(to, entry.name))
            }
        }
    }

    /** The data kept in [area] comes back into the app's folders; the area is gone afterwards. */
    fun mount(area: File) {
        for (dir in DIRS) {
            val from = File(area, dir)
            val entries = from.listFiles().orEmpty()
            if (entries.isEmpty()) continue
            val to = File(dataDir, dir).apply { mkdirs() }
            for (entry in entries) if (!shared(dir, entry.name)) move(entry, File(to, entry.name))
        }
        area.deleteRecursively()
    }

    private fun move(from: File, to: File) {
        if (to.exists()) to.deleteRecursively()
        if (from.renameTo(to)) return
        // Another disk (never expected): copied, then the original removed.
        from.copyRecursively(to, overwrite = true)
        from.deleteRecursively()
    }

    companion object {
        const val PREFS = "shared_prefs"
        const val FILES = "files"
        private val DIRS = listOf(PREFS, FILES)

        /**
         * Preferences of the TV itself: the encrypted sessions (each profile keeps its own there, under its own name),
         * the updater, the crash loop guard, the device's id, the catalogue's covers, Canvas and overrides, this TV's flags.
         */
        val SHARED_PREFS = setOf("private-sessions", "ketvoice_updates", "ketvoice-crash-loop", "ketvoice-device", "ketvoice_art",
            "ketvoice_canvas", "ketvoice_canvas_compact", "ketvoice_overrides", TvModelOffer.PREFS)
        private val SHARED_PREF_PREFIXES = listOf("androidx.", "WebView")

        /** Files that are the same bytes for everyone: the stream cache, images, Canvas, lyrics, measurements, models, logs. */
        val SHARED_FILES = setOf("songs-v2", "canvas", "images", "artwork-permanent", "artist-artwork-v1.json", "artist-cache-v1.json",
            "lyrics-v2", "lyrics-index.json", "ketmix-waves", "automix-analysis.json", "beat", "stems", "logs", "diagnostic",
            TvProfiles.STORE_DIR)
    }
}

/** The encrypted session store as the switch sees it (a map in tests). */
interface TvSessionSlots {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun remove(name: String)
}

/**
 * The account in use keeps its session where the shared code reads it ([ACTIVE]); the others wait under their own name
 * in the same encrypted store. A refresh token works once, so there is only ever one copy of each session.
 */
object TvSessions {
    const val ACTIVE = "account-session"

    fun slot(id: String): String = "tv-profile:$id"

    /** [userOf] reads the user id of a stored session. Idempotent: done again after an interruption, it changes nothing. */
    fun swap(slots: TvSessionSlots, to: String, userOf: (String) -> String?) {
        val active = slots.get(ACTIVE)
        val activeUser = active?.let(userOf)
        if (activeUser != null && activeUser == to) return
        if (active != null && activeUser != null) slots.put(slot(activeUser), active)
        val next = if (TvProfileBook.validId(to)) slots.get(slot(to)) else null
        if (next != null) slots.put(ACTIVE, next) else slots.remove(ACTIVE)
        if (TvProfileBook.validId(to)) slots.remove(slot(to))
    }
}

/** What a start does with the book: the pending switch, then a profile removed while in use. Returns the book after it. */
object TvProfileSwitch {
    fun run(book: TvProfileBook, store: TvProfileStore, mount: TvDataMount, slots: TvSessionSlots, userOf: (String) -> String?): TvProfileBook {
        val target = book.pending ?: return book
        if (target != book.mounted) {
            TvSessions.swap(slots, target, userOf)
            mount.unmount(store.area(book.mounted))
            mount.mount(store.area(target))
        }
        val drop = book.drop
        if (drop != null && drop != target) { store.forget(drop); slots.remove(TvSessions.slot(drop)) }
        val profiles = if (drop != null && drop != target) book.profiles.filter { it.id != drop } else book.profiles
        val done = TvProfileBook(profiles, target, pending = null, drop = null, skipPicker = book.skipPicker)
        store.save(done)
        return done
    }
}
