package android.content

import java.io.File

/**
 * Desktop stand-in for the few members of Android's Context that the shared sources use.
 * Everything lives under one data directory: `%LOCALAPPDATA%\Ketvoice\Data` on Windows,
 * `$XDG_DATA_HOME/ketvoice` (or `~/.local/share/ketvoice`) elsewhere; the
 * `ketvoice.dataDir` system property overrides it (tests point it into build/).
 */
open class Context(val dataDir: File) {
    open val applicationContext: Context get() = this
    open val packageName: String get() = "com.ketvoice.app"
    open val filesDir: File get() = File(dataDir, "files").also { it.mkdirs() }
    open val cacheDir: File get() = File(dataDir, "cache").also { it.mkdirs() }
    open val assets: android.content.res.AssetManager get() = sharedAssets
    open val contentResolver: ContentResolver get() = sharedResolver

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        FileSharedPreferences.open(File(dataDir, "shared_prefs/$name.json"))

    companion object {
        const val MODE_PRIVATE = 0
        private val sharedAssets = android.content.res.AssetManager()
        private val sharedResolver = ContentResolver()

        fun defaultDataDir(): File {
            System.getProperty("ketvoice.dataDir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val os = System.getProperty("os.name").orEmpty()
            if (os.startsWith("Windows")) {
                val local = System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Local")
                // The per-user MSI installs the program into %LOCALAPPDATA%\Ketvoice: data keeps its own folder there.
                return File(local, "Ketvoice\\Data")
            }
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            return if (xdg != null) File(xdg, "ketvoice") else File(System.getProperty("user.home"), ".local/share/ketvoice")
        }

        /** The application's one Context. */
        val app: Context by lazy { Context(defaultDataDir().also { it.mkdirs() }) }
    }
}
