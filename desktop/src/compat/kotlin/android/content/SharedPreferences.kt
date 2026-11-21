package android.content

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Android's SharedPreferences contract, as the shared sources use it. */
interface SharedPreferences {
    val all: Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    operator fun contains(key: String): Boolean
    fun edit(): Editor
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)

    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?)
    }

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

/**
 * One JSON file per preferences name. Values keep their type (`{"t":"i","v":3}`), the map in
 * memory is the truth, and each write replaces the file atomically (temp file + move), so a
 * crash leaves either the old or the new file, never half of one. `apply` writes on one
 * background thread in order; `commit` writes before returning.
 */
internal class FileSharedPreferences private constructor(private val file: File) : SharedPreferences {
    private val values = HashMap<String, Any>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val lock = Any()

    init { load() }

    private fun load() {
        if (!file.isFile) return
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        for (key in root.keys()) {
            val entry = root.optJSONObject(key) ?: continue
            val v = entry.opt("v")
            values[key] = when (entry.optString("t")) {
                "s" -> v as? String ?: continue
                "i" -> (v as? Number)?.toInt() ?: continue
                "l" -> (v as? Number)?.toLong() ?: continue
                "f" -> (v as? Number)?.toFloat() ?: continue
                "b" -> v as? Boolean ?: continue
                "set" -> (v as? JSONArray)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: continue
                else -> continue
            }
        }
    }

    private fun snapshot(): String = synchronized(lock) {
        val root = JSONObject()
        for ((k, v) in values) {
            val (t, out) = when (v) {
                is String -> "s" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is Boolean -> "b" to v
                is Set<*> -> "set" to JSONArray(v.map { it.toString() })
                else -> continue
            }
            root.put(k, JSONObject().put("t", t).put("v", out))
        }
        root.toString()
    }

    private fun write(text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }.getOrDefault(false)

    override val all: Map<String, *> get() = synchronized(lock) { HashMap(values) }
    override fun getString(key: String, defValue: String?) = synchronized(lock) { values[key] as String? ?: defValue }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = synchronized(lock) { (values[key] as Set<String>?)?.toSet() ?: defValues }
    override fun getInt(key: String, defValue: Int) = synchronized(lock) { values[key] as Int? ?: defValue }
    override fun getLong(key: String, defValue: Long) = synchronized(lock) { values[key] as Long? ?: defValue }
    override fun getFloat(key: String, defValue: Float) = synchronized(lock) { values[key] as Float? ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean) = synchronized(lock) { values[key] as Boolean? ?: defValue }
    override fun contains(key: String) = synchronized(lock) { key in values }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += listener }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= listener }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>() // null removes
        private var clearAll = false
        override fun putString(key: String, value: String?) = also { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = also { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = also { changes[key] = value }
        override fun putLong(key: String, value: Long) = also { changes[key] = value }
        override fun putFloat(key: String, value: Float) = also { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = also { changes[key] = value }
        override fun remove(key: String) = also { changes[key] = null }
        override fun clear() = also { clearAll = true }

        private fun merge(): Pair<String, List<String>> {
            val changed = synchronized(lock) {
                if (clearAll) values.clear()
                for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
                changes.keys.toList()
            }
            return snapshot() to changed
        }

        private fun notify(keys: List<String>) {
            for (k in keys) for (l in listeners) l.onSharedPreferenceChanged(this@FileSharedPreferences, k)
        }

        override fun commit(): Boolean {
            val (text, keys) = merge()
            val ok = synchronized(writeLock) { write(text) }
            notify(keys)
            return ok
        }

        override fun apply() {
            val (text, keys) = merge()
            writer.execute { synchronized(writeLock) { write(text) } }
            notify(keys)
        }
    }

    private val writeLock = Any()

    companion object {
        private val open = ConcurrentHashMap<String, FileSharedPreferences>()
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "prefs-writer").apply { isDaemon = true } }

        fun open(file: File): FileSharedPreferences = open.getOrPut(file.absolutePath) { FileSharedPreferences(file) }

        /** Waits for every queued `apply` (shutdown, tests). */
        fun flush() { writer.submit {}.get() }
    }
}
