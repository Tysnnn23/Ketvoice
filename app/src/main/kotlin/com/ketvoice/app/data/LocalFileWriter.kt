package com.ketvoice.app.data

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * « Écrire dans le fichier » (7.0) on the device: only when the listener chose it, only for MP3 and FLAC
 * ([LocalTagWriter]), and only where Android grants the write — the file's own folder grant (a folder chosen
 * in Ketvoice), or the system's confirmation for files of the whole device ([writeRequest]).
 *
 * The new file is checked to carry the same audio before anything is written, a copy of the original waits in
 * the cache while it is replaced, and the original comes back if the write fails.
 */
internal object LocalFileWriter {
    enum class Outcome { Written, Unsupported, Denied, Failed }

    private const val MAX_BYTES = 300L * 1024 * 1024

    /** Files of the whole device need Android's confirmation first: one request for all of them. */
    fun writeRequest(context: Context, uris: List<Uri>): PendingIntent? {
        val media = uris.filter { it.authority == MediaStore.AUTHORITY }
        if (media.isEmpty() || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return runCatching { MediaStore.createWriteRequest(context.contentResolver, media) }.getOrNull()
    }

    fun update(changes: Map<LocalMatch.Field, String>): LocalTagWriter.Update = LocalTagWriter.Update(
        title = changes[LocalMatch.Field.Title], artist = changes[LocalMatch.Field.Artist],
        album = changes[LocalMatch.Field.Album], albumArtist = changes[LocalMatch.Field.AlbumArtist])

    fun write(context: Context, uri: Uri, update: LocalTagWriter.Update): Outcome {
        val resolver = context.contentResolver
        val original = try { read(context, uri) ?: return Outcome.Failed } catch (_: SecurityException) { return Outcome.Denied }
        val rewritten = LocalTagWriter.rewrite(original, update) ?: return Outcome.Unsupported
        if (!LocalTagWriter.sameAudio(original, rewritten)) return Outcome.Unsupported
        val backup = File(context.cacheDir, "local-tag-backup-${System.nanoTime()}")
        return try {
            backup.writeBytes(original)
            replace(context, uri, rewritten, backup)
        } catch (_: SecurityException) { Outcome.Denied }
        catch (_: Exception) { Outcome.Failed }
        finally { backup.delete() }
    }

    private fun read(context: Context, uri: Uri): ByteArray? {
        val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (size > MAX_BYTES) return null
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    /** Writes [bytes] over the file and reads it back; on any failure the original from [backup] is put back. */
    private fun replace(context: Context, uri: Uri, bytes: ByteArray, backup: File): Outcome {
        val resolver = context.contentResolver
        try {
            resolver.openOutputStream(uri, "rwt")?.use { it.write(bytes) } ?: return Outcome.Failed
            val back = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (back != null && back.contentEquals(bytes)) return Outcome.Written
        } catch (denied: SecurityException) {
            throw denied
        } catch (_: Exception) { }
        runCatching { resolver.openOutputStream(uri, "rwt")?.use { out -> backup.inputStream().use { it.copyTo(out) } } }
        return Outcome.Failed
    }
}
