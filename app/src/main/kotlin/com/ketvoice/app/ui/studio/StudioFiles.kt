package com.ketvoice.app.ui.studio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.ketvoice.app.data.StudioCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Files the Studio prepares before sending them, in one private folder deleted on close. */
internal class StudioFiles(private val context: Context) {
    val folder: File = File(context.cacheDir, "studio/${UUID.randomUUID()}").apply { mkdirs() }
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun nameOf(file: File?): String? = file?.let { names[it.absolutePath] }
    fun newFile(extension: String): File = File(folder, "${UUID.randomUUID()}.$extension")
    fun clear() = folder.deleteRecursively()

    suspend fun copy(uri: Uri, max: Long, extensions: Set<String>): File = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: error("Nom de fichier inaccessible")
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in extensions) { "Format de fichier non pris en charge" }
        val file = newFile(extension)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Fichier inaccessible")
            input.use { source -> file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024); var size = 0L
                while (true) {
                    val count = source.read(buffer); if (count < 0) break
                    size += count; require(size <= max) { "Fichier trop volumineux" }; out.write(buffer, 0, count)
                }
            } }
            require(file.length() > 0) { "Fichier vide" }
            names[file.absolutePath] = name.take(200)
            file
        } catch (failure: Exception) { file.delete(); throw failure }
    }

    /** What the tags of an audio file say, read on the device. */
    data class AudioTags(val title: String, val artist: String, val album: String, val durationMs: Long, val cover: File?)

    suspend fun readAudio(file: File): AudioTags = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                "Choisissez un fichier audio, pas une vidéo"
            }
            // The artwork embedded in the file becomes the cover when it fits the server's limit.
            val picture = retriever.embeddedPicture?.takeIf { it.size in 1..4 * 1024 * 1024 }
            val cover = picture?.let { bytes ->
                val ext = when {
                    bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
                    bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
                    else -> null
                }
                ext?.let { newFile(it).apply { writeBytes(bytes); names[absolutePath] = "Pochette du fichier" } }
            }
            AudioTags(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                cover)
        } finally { retriever.release() }
    }

    /** The checks shown under a chosen Canvas. */
    suspend fun canvasChecks(file: File): List<StudioCanvas.Check> = withContext(Dispatchers.IO) {
        val header = ByteArray(12).also { bytes -> file.inputStream().use { it.read(bytes) } }
        val retriever = MediaMetadataRetriever()
        try {
            runCatching { retriever.setDataSource(file.absolutePath) }
            fun number(key: Int) = runCatching { retriever.extractMetadata(key)?.toLongOrNull() }.getOrNull() ?: 0L
            val rotation = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            var width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            var height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            if (rotation == 90L || rotation == 270L) { val w = width; width = height; height = w }
            StudioCanvas.checks(file.length(), number(MediaMetadataRetriever.METADATA_KEY_DURATION), width, height,
                StudioCanvas.isMp4(header))
        } finally { retriever.release() }
    }
}
