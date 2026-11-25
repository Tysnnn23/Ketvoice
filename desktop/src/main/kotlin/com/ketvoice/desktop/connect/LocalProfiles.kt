package com.ketvoice.desktop.connect

import com.ketvoice.app.util.moveOver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Several profiles on one PC: each has a name, an avatar colour and its own data folder (settings, session, library,
 * downloads' index, caches), while the audio cache is shared (the same title is the same bytes for everyone). The
 * first profile (« main ») is the data folder of before, untouched; the others live in `profiles/<id>` under it.
 * The list and the active one are in `profiles.json` there. Switching relaunches Ketvoice on the other profile (the
 * account, the player and every store are single objects started once; a relaunch is quick and loses nothing).
 */
object LocalProfiles {
    const val MAIN = "main"
    const val ARG = "--profile="
    private const val FILE = "profiles.json"
    private val ID = Regex("[a-z0-9]{8}")
    /** The avatar colours offered (ARGB). */
    val COLOURS = listOf(0xFFFA2D48, 0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFBF5AF2, 0xFF64D2FF, 0xFFFF375F, 0xFF8E8E93)

    data class Profile(val id: String, val name: String, val colour: Long)
    data class Book(val active: String, val profiles: List<Profile>)

    /** The folder every profile hangs from (the data folder of before), set once at start. */
    @Volatile var base: File = File(".")
        private set
    @Volatile var current: String = MAIN
        private set

    /**
     * First thing in main(), before anything opens the data folder: picks the profile ([ARG] from a relaunch, else the
     * active one), points the data folder at it and shares the audio cache. Returns the profile's folder.
     */
    fun applyAtStart(args: List<String>, root: File = android.content.Context.defaultDataDir()): File {
        base = root.also { it.mkdirs() }
        val book = load(root)
        val wanted = args.firstNotNullOfOrNull { it.removePrefix(ARG).takeIf { _ -> it.startsWith(ARG) } } ?: book.active
        current = book.profiles.firstOrNull { it.id == wanted }?.id ?: MAIN
        val dir = dirOf(root, current)
        if (current != MAIN) {
            System.setProperty("ketvoice.dataDir", dir.absolutePath)
            com.ketvoice.desktop.audio.StreamCache.dir = sharedAudioCache(root)
        }
        return dir
    }

    fun dirOf(root: File, id: String): File = if (id == MAIN) root else File(root, "profiles/$id")

    /** The audio cache every profile shares: the main profile's own. */
    fun sharedAudioCache(root: File): File = File(root, "cache/audio")

    fun load(root: File = base): Book = runCatching {
        val json = JSONObject(File(root, FILE).readText())
        val rows = json.optJSONArray("profiles") ?: JSONArray()
        val list = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::parse) }
        normal(Book(json.optString("active", MAIN), list))
    }.getOrDefault(normal(Book(MAIN, emptyList())))

    /** The main profile is always there, first; ids are unique; the active one exists. */
    internal fun normal(book: Book): Book {
        val main = book.profiles.firstOrNull { it.id == MAIN } ?: Profile(MAIN, "Principal", COLOURS[0])
        val others = book.profiles.filter { it.id != MAIN && ID.matches(it.id) }.distinctBy { it.id }
        val all = listOf(main) + others
        return Book(book.active.takeIf { a -> all.any { it.id == a } } ?: MAIN, all)
    }

    private fun parse(o: JSONObject): Profile? {
        val id = o.optString("id").takeIf { it == MAIN || ID.matches(it) } ?: return null
        return Profile(id, cleanName(o.optString("name")), o.optLong("colour", COLOURS[0]))
    }

    fun cleanName(name: String): String = name.filterNot(Char::isISOControl).trim().take(24).ifBlank { "Profil" }

    fun save(book: Book, root: File = base) {
        val json = JSONObject().put("active", book.active).put("profiles", JSONArray(book.profiles.map {
            JSONObject().put("id", it.id).put("name", it.name).put("colour", it.colour)
        }))
        val file = File(root, FILE)
        val tmp = File(root, "$FILE.tmp")
        tmp.writeText(json.toString(1))
        tmp.moveOver(file)
    }

    fun create(name: String, colour: Long, root: File = base, random: SecureRandom = SecureRandom()): Profile {
        val book = load(root)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        var id: String
        do { id = (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") } while (book.profiles.any { it.id == id })
        val profile = Profile(id, cleanName(name), colour)
        dirOf(root, id).mkdirs()
        save(book.copy(profiles = book.profiles + profile), root)
        return profile
    }

    fun rename(id: String, name: String, colour: Long, root: File = base) {
        val book = load(root)
        save(book.copy(profiles = book.profiles.map { if (it.id == id) it.copy(name = cleanName(name), colour = colour) else it }), root)
    }

    /** Removes a profile and its folder; never the main profile nor the one in use. */
    fun delete(id: String, root: File = base): Boolean {
        if (id == MAIN || id == current) return false
        val book = load(root)
        if (book.profiles.none { it.id == id }) return false
        save(book.copy(profiles = book.profiles.filter { it.id != id }, active = if (book.active == id) MAIN else book.active), root)
        return dirOf(root, id).deleteRecursively()
    }

    /** Makes [id] the active profile and restarts Ketvoice on it; [quit] closes this window the normal way. */
    fun switchTo(id: String, quit: () -> Unit, root: File = base): Boolean {
        val book = load(root)
        if (id == current || book.profiles.none { it.id == id }) return false
        save(book.copy(active = id), root)
        val command = relaunchCommand(id) ?: return false
        runCatching { ProcessBuilder(command).inheritIO().start() }.onFailure { return false }
        quit()
        return true
    }

    /** This very program again, with its arguments, on profile [id]. */
    internal fun relaunchCommand(id: String, info: ProcessHandle.Info = ProcessHandle.current().info()): List<String>? {
        val exe = info.command().orElse(null) ?: return null
        val args = info.arguments().orElse(emptyArray()).filterNot { it.startsWith(ARG) }
        // Started as a bare JVM (`gradlew run`, where Windows does not report the arguments): the class path again.
        if (File(exe).nameWithoutExtension.equals("java", true) && args.isEmpty()) {
            return listOf(exe, "-cp", System.getProperty("java.class.path"), "com.ketvoice.desktop.MainKt", "$ARG$id")
        }
        return listOf(exe) + args + "$ARG$id"
    }
}
