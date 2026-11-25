package com.ketvoice.desktop.library

import android.content.Context
import com.ketvoice.app.data.DownloadQueue
import com.ketvoice.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_ATTACHED_PIC
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import org.bytedeco.ffmpeg.global.avutil.AV_DICT_IGNORE_SUFFIX
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Music files on this computer: the folders the listener chose (kept in the `desktop` preferences),
 * scanned for audio files whose tags FFmpeg reads (title, artist, album, track number, duration and
 * the embedded cover). An index (path, size, date → title) spares reading unchanged files again.
 * Titles get `local:` ids, which the account sync keeps on this computer, and a `file:` address that
 * the player opens as `AudioSource.LocalFile`.
 */
object LocalFolders {
    val EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "wma", "alac", "ape", "wv", "mka")
    private const val KEY = "localFolders"
    private const val LIMIT = 20_000

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    /** The titles found, by album then track number. */
    val tracks: StateFlow<List<Track>> = _tracks
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var context: Context? = null
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var index = emptyMap<String, Entry>()

    private class Entry(val size: Long, val modified: Long, val track: Track?, val order: String)

    fun init(context: Context = Context.app, scope: CoroutineScope = this.scope) {
        this.context = context
        this.scope = scope
        _folders.value = prefs(context).getStringSet(KEY, emptySet()).orEmpty().sorted()
        index = loadIndex(context)
        _tracks.value = ordered(index.filterKeys { path -> _folders.value.any { inside(path, it) } && File(path).isFile }.values)
        if (_folders.value.isNotEmpty()) rescan()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("desktop", Context.MODE_PRIVATE)

    fun addFolder(path: String): Boolean {
        val dir = File(path).absoluteFile.normalize().takeIf { it.isDirectory } ?: return false
        if (_folders.value.any { inside(dir.path, it) }) return false
        // A parent of folders already kept replaces them.
        setFolders(_folders.value.filterNot { inside(it, dir.path) } + dir.path)
        return true
    }

    fun removeFolder(path: String) = setFolders(_folders.value - path)

    private fun setFolders(next: List<String>) {
        _folders.value = next.sorted()
        context?.let { prefs(it).edit().putStringSet(KEY, next.toSet()).apply() }
        rescan()
    }

    fun rescan(): Job = scope.launch { mutex.withLock { scan() } }

    internal suspend fun scan() {
        val ctx = context ?: return
        _scanning.value = true
        try {
            val files = _folders.value.asSequence().flatMap { folder ->
                File(folder).walkTopDown().onEnter { !it.isHidden && !it.name.startsWith(".") }
                    .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            }.distinctBy { it.path }.take(LIMIT).toList()
            val covers = File(ctx.cacheDir, "local-covers").apply { mkdirs() }
            val next = LinkedHashMap<String, Entry>()
            for (file in files) {
                val size = file.length(); val modified = file.lastModified()
                next[file.path] = index[file.path]?.takeIf { it.size == size && it.modified == modified }
                    ?: read(file, covers).let { (track, order) -> Entry(size, modified, track, order) }
            }
            index = next
            saveIndex(ctx, next)
            _tracks.value = ordered(next.values)
            noteSidecars(next.keys)
        } finally { _scanning.value = false }
    }

    /**
     * « Bibliothèque surveillée » (Windows 2.0, [LibraryWatch]): files and folders that changed under the chosen folders,
     * read again (added or changed), dropped (removed) or walked (a folder moved in); a `.lrc` that changed is noted for
     * its audio file. Unchanged files keep their entry.
     */
    fun update(changed: Set<String>): Job = scope.launch { mutex.withLock { applyChanges(changed) } }

    internal fun applyChanges(changed: Set<String>) {
        val ctx = context ?: return
        val covers = File(ctx.cacheDir, "local-covers").apply { mkdirs() }
        val next = LinkedHashMap(index)
        for (path in changed) {
            val f = File(path)
            if (_folders.value.none { inside(f.path, it) }) continue
            when {
                f.isDirectory -> f.walkTopDown().onEnter { it == f || (!it.isHidden && !it.name.startsWith(".")) }
                    .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }.take(LIMIT).forEach { reread(it, next, covers) }
                f.isFile && f.extension.lowercase() in EXTENSIONS -> reread(f, next, covers)
                // Gone: the file, or every file of a folder that went.
                !f.exists() -> next.keys.removeAll { it == f.path || inside(it, f.path) }
            }
        }
        index = next
        saveIndex(ctx, next)
        _tracks.value = ordered(next.values)
        noteSidecars(next.keys)
    }

    private fun reread(file: File, into: MutableMap<String, Entry>, covers: File) {
        val size = file.length(); val modified = file.lastModified()
        if (into[file.path]?.let { it.size == size && it.modified == modified } == true) return
        into[file.path] = read(file, covers).let { (track, order) -> Entry(size, modified, track, order) }
    }

    /** A `.lrc` beside an audio file, with the same name, is that title's lyrics (before any lookup). */
    private fun noteSidecars(paths: Collection<String>) {
        for (path in paths) {
            val audio = File(path)
            val lrc = File(audio.parentFile, audio.nameWithoutExtension + ".lrc").takeIf { it.isFile } ?: continue
            com.ketvoice.app.data.LocalLyrics.noteSidecar(idOf(audio), android.net.Uri.fromFile(lrc))
        }
    }

    private fun ordered(entries: Collection<Entry>): List<Track> = entries.filter { it.track != null }.sortedBy { it.order }.mapNotNull { it.track }

    private fun inside(path: String, folder: String): Boolean =
        path == folder || path.startsWith(folder.trimEnd(File.separatorChar) + File.separatorChar)

    internal fun idOf(file: File): String = "local:file:" + sha1(file.absolutePath).take(20)

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** A file's title as its tags tell it (the file name when it has none), and its place in the list. */
    internal fun read(file: File, covers: File?): Pair<Track?, String> {
        val fmt = AVFormatContext(null)
        if (avformat_open_input(fmt, BytePointer(file.path, pathCharset), null, null as AVDictionary?) < 0) return null to ""
        try {
            avformat_find_stream_info(fmt, null as PointerPointer<*>?)
            val streams = (0 until fmt.nb_streams()).map { fmt.streams(it) }
            val audio = streams.firstOrNull { it.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO && it.disposition() and AV_DISPOSITION_ATTACHED_PIC == 0 }
                ?: return null to ""
            // Ogg and Opus keep their tags on the stream, the other containers on the file.
            fun tag(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
                listOf(fmt.metadata(), audio.metadata()).firstNotNullOfOrNull { dict ->
                    dict?.let { av_dict_get(it, name, null, 0) }?.value()?.string?.trim()?.takeIf(String::isNotEmpty)
                }
            }
            val title = tag("title") ?: file.nameWithoutExtension
            val artist = tag("artist", "album_artist", "albumartist") ?: ""
            val album = tag("album") ?: ""
            val number = tag("track")?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            val disc = tag("disc")?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            val duration = fmt.duration().takeIf { it > 0 }?.div(1000) ?: 0L
            val id = idOf(file)
            val cover = streams.firstOrNull { it.disposition() and AV_DISPOSITION_ATTACHED_PIC != 0 }?.attached_pic()
                ?.takeIf { it.size() in 1..8_000_000 }?.let { packet ->
                    val bytes = ByteArray(packet.size()).also { packet.data().get(it) }
                    val png = bytes.size > 4 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
                    covers?.let { File(it, id.substringAfterLast(':') + if (png) ".png" else ".jpg").apply { writeBytes(bytes) } }
                }
            val track = Track(id, title, artist, album, durationMs = duration, streamUrl = file.toURI().toString(),
                coverUrl = cover?.toURI()?.toString())
            embeddedLyrics(fmt.metadata(), audio.metadata())?.let { keepLyrics(id, it) }
            val order = "%s\u0000%04d\u0000%04d\u0000%s".format(album.lowercase().ifEmpty { "￿" }, disc, number, title.lowercase())
            return track to order
        } finally {
            avformat_close_input(fmt)
        }
    }

    /** Lyrics written in the file's tags (ID3 `USLT` as `lyrics-xxx`, Vorbis and MP4 `LYRICS`/`UNSYNCEDLYRICS`). */
    private fun embeddedLyrics(vararg dicts: AVDictionary?): String? = dicts.firstNotNullOfOrNull { dict ->
        dict ?: return@firstNotNullOfOrNull null
        (av_dict_get(dict, "lyrics", null, AV_DICT_IGNORE_SUFFIX) ?: av_dict_get(dict, "unsyncedlyrics", null, 0))
            ?.value()?.string?.trim()?.takeIf { it.length in 8..200_000 }
    }

    /** Embedded lyrics go where LyricsRepo looks for a local file's lyrics (a `.lrc` beside the file still comes first). */
    private fun keepLyrics(id: String, text: String) {
        val lines = runCatching { com.ketvoice.app.data.CustomLyrics.parse(text).second }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        runCatching { com.ketvoice.app.data.LocalLyrics.keep(id, "Paroles intégrées" to lines) }
    }

    /**
     * How FFmpeg wants a path: UTF-8 on Windows (it turns it into the wide path, so accents and every script
     * open), the JVM's own file-name encoding elsewhere (the bytes Java used to name the file).
     */
    private val pathCharset: String =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "UTF-8" else System.getProperty("sun.jnu.encoding") ?: "UTF-8"

    private fun indexFile(context: Context) = File(context.filesDir, "local-music-v1.json")

    private fun loadIndex(context: Context): Map<String, Entry> = runCatching {
        val rows = JSONObject(indexFile(context).readText()).getJSONArray("files")
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.associate { row ->
            row.getString("path") to Entry(row.getLong("size"), row.getLong("modified"),
                row.optJSONObject("track")?.let { DownloadQueue.trackFromJson(it) }, row.optString("order"))
        }
    }.getOrDefault(emptyMap())

    private fun saveIndex(context: Context, entries: Map<String, Entry>) {
        val rows = JSONArray(entries.map { (path, e) ->
            JSONObject().put("path", path).put("size", e.size).put("modified", e.modified).put("order", e.order)
                .put("track", e.track?.let { DownloadQueue.trackToJson(it) } ?: JSONObject.NULL)
        })
        val file = indexFile(context)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("version", 1).put("files", rows).toString())
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Tests: forget what is in memory. */
    internal fun resetForTest() { context = null; index = emptyMap(); _folders.value = emptyList(); _tracks.value = emptyList() }
}
