package com.ketvoice.app.airket

import android.content.Context
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.CustomLyrics
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.PersonalBackup
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Where each kind of file lives under the app's private folder, and which names it may have. */
internal class AirKetLayout(private val files: File) {
    fun dirOf(kind: AirKetEntry.Kind): File = when (kind) {
        AirKetEntry.Kind.Canvas -> File(files, "canvas")
        AirKetEntry.Kind.Stem -> File(files, "stems/masks")
        AirKetEntry.Kind.CustomLyrics -> File(files, "custom-lyrics")
        else -> File(files, "downloads")
    }

    fun fileOf(entry: AirKetEntry) = File(dirOf(entry.kind), entry.name)

    /** The extension a kind of file must have: the receiver refuses anything else, whatever the sender says. */
    fun accepts(entry: AirKetEntry): Boolean = AirKetEntry.validName(entry.name) && when (entry.kind) {
        AirKetEntry.Kind.Audio -> entry.name.substringAfterLast('.') in AUDIO
        AirKetEntry.Kind.Meta -> entry.name.endsWith(".json") && !entry.name.endsWith(".lyrics.json")
        AirKetEntry.Kind.Lyrics -> entry.name.endsWith(".lyrics.json")
        AirKetEntry.Kind.Lrc -> entry.name.endsWith(".lrc")
        AirKetEntry.Kind.Cover -> entry.name.endsWith(".cover.jpg")
        AirKetEntry.Kind.Canvas -> entry.name.endsWith(".mp4")
        AirKetEntry.Kind.Stem -> entry.name.endsWith(".kvs")
        AirKetEntry.Kind.CustomLyrics -> entry.name.endsWith(".txt")
    }

    companion object { val AUDIO = setOf("flac", "mp3", "m4a", "opus", "wav") }
}

/** What this phone hands out: its backup, the list of its files and the files themselves. */
internal class AirKetDeviceSource(private val context: Context, private val deviceName: String) : AirKetSource {
    private val layout = AirKetLayout(context.filesDir)
    private var backup = ByteArray(0)
    var manifest = AirKetManifest(emptyList())
        private set

    /** Reads the backup and the folders once, on a background thread, before the code is shown. */
    fun prepare() {
        backup = PersonalBackup.encode(PersonalBackup.collect(context))
        manifest = scan()
    }

    val backupBytes: Int get() = backup.size

    private fun scan(): AirKetManifest {
        val found = linkedMapOf<String, AirKetEntry>()
        fun add(kind: AirKetEntry.Kind, file: File?) {
            if (file == null || !file.isFile || file.length() <= 0) return
            val entry = AirKetEntry(kind, file.name, file.length())
            if (layout.accepts(entry) && layout.fileOf(entry).absolutePath == file.absolutePath) found.putIfAbsent(entry.id, entry)
        }
        var dash = 0
        Downloader.items.value.forEach { item ->
            if (item.dashManifestUrl != null) { dash++; return@forEach }
            val stem = item.file.nameWithoutExtension
            val folder = item.file.parentFile
            add(AirKetEntry.Kind.Audio, item.file)
            add(AirKetEntry.Kind.Meta, File(folder, "$stem.json"))
            add(AirKetEntry.Kind.Lyrics, File(folder, "$stem.lyrics.json"))
            add(AirKetEntry.Kind.Lrc, File(folder, "$stem.lrc"))
            add(AirKetEntry.Kind.Cover, File(folder, "$stem.cover.jpg"))
            add(AirKetEntry.Kind.Canvas, item.canvasFile)
        }
        layout.dirOf(AirKetEntry.Kind.Stem).listFiles { f -> f.name.endsWith(".kvs") }?.forEach { add(AirKetEntry.Kind.Stem, it) }
        val index = CustomLyrics.index(context)
        val keptIndex = index.filter { (_, text) -> runCatching { JSONObject(text).getString("file") }.getOrNull()
            ?.let { name -> File(CustomLyrics.folder(context), name).isFile } == true }
        keptIndex.values.forEach { text ->
            runCatching { add(AirKetEntry.Kind.CustomLyrics, File(CustomLyrics.folder(context), JSONObject(text).getString("file"))) }
        }
        return AirKetManifest(found.values.toList(), keptIndex, dash)
    }

    override fun hello(): JSONObject = JSONObject().put("name", deviceName).put("backupBytes", backup.size)
        .put("titles", manifest.titles).put("files", manifest.downloadFiles).put("bytes", manifest.downloadBytes)
        .put("leftOutDash", manifest.leftOutDash)

    override fun document(id: String): ByteArray? = when (id) {
        "backup" -> backup
        "manifest" -> manifest.encode()
        else -> null
    }

    override fun file(id: String): AirKetFile? {
        val entry = manifest.entries.firstOrNull { it.id == id } ?: return null
        val file = layout.fileOf(entry).takeIf { it.isFile } ?: return null
        return AirKetFile(file.length()) { from -> FileInputStream(file).also { input -> var left = from; while (left > 0) { val n = input.skip(left); if (n <= 0) break; left -= n } } as InputStream }
    }
}

/** Puts verified files where the app reads them, and tells the app about them. */
internal class AirKetInstaller(private val context: Context, private val staging: AirKetStaging) {
    private val layout = AirKetLayout(context.filesDir)

    data class Result(val titles: Int, val files: Int, val skipped: Int)

    /** Moves every verified entry of [entries] into place. A title already here is kept as it is. */
    fun install(entries: List<AirKetEntry>, manifest: AirKetManifest): Result {
        var files = 0; var skipped = 0
        val adopted = mutableListOf<JSONObject>()
        entries.sortedBy { if (it.kind == AirKetEntry.Kind.Meta) 1 else 0 }.forEach { entry ->
            val ok = staging.verified(entry)
            if (!layout.accepts(entry) || !ok.isFile || ok.length() != entry.size) { skipped++; return@forEach }
            val dest = layout.fileOf(entry)
            dest.parentFile?.mkdirs()
            if (entry.kind == AirKetEntry.Kind.Audio && dest.isFile && dest.length() == entry.size) { ok.delete(); return@forEach }
            if (entry.kind == AirKetEntry.Kind.Meta) {
                val text = localMeta(ok.readText(Charsets.UTF_8), layout.dirOf(AirKetEntry.Kind.Canvas), layout.dirOf(AirKetEntry.Kind.Meta))
                if (text == null) { skipped++; return@forEach }
                dest.writeText(text.first, Charsets.UTF_8); adopted += text.second; ok.delete()
            } else if (!ok.renameTo(dest)) { ok.copyTo(dest, overwrite = true); ok.delete() }
            files++
        }
        Downloader.reload()
        adopted.forEach { o ->
            val name = o.optString("canvasSpotifyId")
            if (name.isNotBlank() && AirKetEntry.validName(name)) CanvasRepo.adopt(o.getString("id"), o.optString("album"), o.optString("artist"), name)
        }
        CustomLyrics.adopt(context, manifest.customLyrics)
        return Result(manifest.entries.count { it.kind == AirKetEntry.Kind.Audio && layout.fileOf(it).isFile }, files, skipped)
    }

    companion object {
        /**
         * A title's metadata made for this phone: the sender's absolute paths (Canvas, cover) are replaced by this
         * phone's, and anything that is not a plain title record is refused. Returns the text and the parsed record.
         */
        fun localMeta(text: String, canvasDir: File, downloads: File): Pair<String, JSONObject>? = runCatching {
            val o = JSONObject(text)
            require(o.getString("id").isNotBlank() && o.getString("title").isNotBlank() && AirKetEntry.validName(o.getString("file")))
            o.optString("canvasFile").takeIf { it.isNotBlank() }?.let { o.put("canvasFile", File(canvasDir, File(it).name).absolutePath) }
            val cover = File(downloads, o.getString("file").substringBeforeLast('.') + ".cover.jpg")
            if (o.optString("coverUrl").startsWith("file:")) o.put("coverUrl", "file://" + cover.absolutePath)
            o.remove("dashManifestUrl")
            o.toString() to o
        }.getOrNull()
    }
}
