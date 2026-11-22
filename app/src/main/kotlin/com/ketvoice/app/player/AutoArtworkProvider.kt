package com.ketvoice.app.player

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Covers for Android Auto. A car does not load web images itself: it reads a content URI of the
 * app. [uri] wraps the cover the app shows (https, the device's album art, a downloaded file's
 * cover) in such a URI, signed so the provider only ever fetches what Ketvoice handed out.
 * Declared in the mobile manifest only: on Quest the covers stay the plain https addresses.
 */
internal object AutoArtwork {
    private const val PREFS = "auto-artwork"
    @Volatile private var secret: ByteArray? = null
    @Volatile private var available: Boolean? = null

    fun authority(context: Context) = context.packageName + ".autoart"

    private fun secret(context: Context): ByteArray = secret ?: synchronized(this) {
        secret ?: run {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString("key", null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            val key = saved?.takeIf { it.size >= 32 } ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
                .also { prefs.edit().putString("key", Base64.encodeToString(it, Base64.NO_WRAP)).apply() }
            key.also { secret = it }
        }
    }

    internal fun sign(key: ByteArray, source: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal(source.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    }

    internal fun verify(key: ByteArray, source: String, token: String): Boolean =
        MessageDigest.isEqual(sign(key, source).toByteArray(), token.toByteArray())

    internal fun encode(source: String): String =
        Base64.encodeToString(source.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    internal fun decode(value: String): String? =
        runCatching { String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8) }.getOrNull()

    /** Sources a car may get through the provider: web covers over https, the device's album art and the app's own files. */
    internal fun allowed(source: String): Boolean {
        val uri = Uri.parse(source)
        return when (uri.scheme) {
            "https" -> !uri.host.isNullOrBlank()
            "content" -> uri.authority == "media"
            "file" -> !uri.path.isNullOrBlank()
            else -> false
        }
    }

    private fun providerAvailable(context: Context): Boolean = available ?: runCatching {
        context.packageManager.resolveContentProvider(authority(context), 0) != null
    }.getOrDefault(false).also { available = it }

    /** Whether this build declares Android Auto (the mobile flavour declares the cover provider with it). */
    fun declared(context: Context): Boolean = providerAvailable(context)

    /** The cover as a car can read it, or null when there is none. */
    fun uri(context: Context, source: String?): Uri? {
        val value = source?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.startsWith("android.resource://")) return Uri.parse(value)
        if (!allowed(value)) return null
        if (!providerAvailable(context)) return if (value.startsWith("https://")) Uri.parse(value) else null
        return Uri.Builder().scheme("content").authority(authority(context))
            .appendPath(sign(secret(context), value)).appendPath(encode(value)).build()
    }

    /** The signed source behind a provider URI, or null for a URI Ketvoice did not hand out. */
    fun source(context: Context, uri: Uri): String? {
        val parts = uri.pathSegments
        if (parts.size != 2) return null
        val value = decode(parts[1]) ?: return null
        return value.takeIf { allowed(it) && verify(secret(context), it, parts[0]) }
    }

    /** Largest side of a cover stored for the car: enough for its grid and its now-playing screen. */
    const val MAX_SIDE = 720
    private const val MAX_BYTES = 6L * 1024 * 1024
    private const val MAX_FILES = 400

    fun cacheDir(context: Context) = File(context.cacheDir, "auto-artwork").apply { mkdirs() }

    /** The cover's file for the car: fetched, scaled and kept once. */
    fun file(context: Context, source: String): File {
        val dir = cacheDir(context)
        val name = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).take(20).joinToString("") { "%02x".format(it) }
        val target = File(dir, "$name.jpg")
        if (target.isFile && target.length() > 0 && System.currentTimeMillis() - target.lastModified() < 14L * 24 * 3600_000) return target
        val bytes = read(context, source) ?: throw FileNotFoundException("No cover")
        val scaled = scale(bytes) ?: throw FileNotFoundException("Not an image")
        val temp = File(dir, "$name.${System.nanoTime()}.tmp")
        try {
            temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!temp.renameTo(target)) { target.delete(); if (!temp.renameTo(target)) throw FileNotFoundException("Cache") }
        } finally { temp.delete(); scaled.recycle() }
        prune(dir)
        return target
    }

    private fun read(context: Context, source: String): ByteArray? {
        val uri = Uri.parse(source)
        return when (uri.scheme) {
            "https" -> {
                val connection = URL(source).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5_000; connection.readTimeout = 8_000
                    connection.setRequestProperty("User-Agent", "Ketvoice")
                    if (connection.responseCode !in 200..299) return null
                    connection.inputStream.use { limited(it) }
                } finally { connection.disconnect() }
            }
            "content" -> context.contentResolver.openInputStream(uri)?.use { limited(it) }
            "file" -> {
                // Only the app's own files (downloaded covers), never another path on the device.
                val file = File(uri.path ?: return null).canonicalFile
                val roots = listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null)).map { it.canonicalPath + File.separator }
                if (roots.none { file.path.startsWith(it) } || !file.isFile) return null
                file.inputStream().use { limited(it) }
            }
            else -> null
        }
    }

    private fun limited(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Decoded at most at twice [MAX_SIDE], then brought down to it. */
    internal fun scale(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = maxOf(bitmap.width, bitmap.height)
        if (side <= MAX_SIDE) return bitmap
        val ratio = MAX_SIDE.toFloat() / side
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { file -> file.name.endsWith(".jpg") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES * 3 / 4).forEach { it.delete() }
    }
}

/** Serves [AutoArtwork] covers to Android Auto, read-only. */
class AutoArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        val context = context ?: throw FileNotFoundException()
        val source = AutoArtwork.source(context, uri) ?: throw FileNotFoundException("Unknown cover")
        val file = try { AutoArtwork.file(context, source) }
            catch (missing: FileNotFoundException) { throw missing }
            catch (error: Exception) { throw FileNotFoundException(error.message) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
