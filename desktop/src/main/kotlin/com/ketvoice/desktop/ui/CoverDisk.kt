package com.ketvoice.desktop.ui

import com.ketvoice.app.data.DiskBudget
import java.io.File

/**
 * Covers as their services sent them, kept on disk (`<data>/cache/covers`, one file per address): a window opened again,
 * offline or on a slow network, shows its covers at once instead of the grey placeholder. Least recently used first
 * out past [BUDGET_BYTES] (each read refreshes a file's date).
 */
internal object CoverDisk {
    const val BUDGET_BYTES = 384L shl 20
    private const val TRIM_EVERY = 40
    /** The folder; the tests point it elsewhere (null: no disk cache). */
    @Volatile var dir: File? = null
        get() = field ?: runCatching { File(android.content.Context.app.cacheDir, "covers").apply { mkdirs() } }.getOrNull()?.also { field = it }
    @Volatile private var writes = 0

    fun fileOf(url: String): File? = dir?.let { File(it, key(url) + ".img") }

    fun read(url: String): ByteArray? {
        val file = fileOf(url)?.takeIf { it.isFile && it.length() > 0 } ?: return null
        file.setLastModified(System.currentTimeMillis())
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun write(url: String, bytes: ByteArray) {
        val file = fileOf(url) ?: return
        val part = File(file.parentFile, file.name + ".part")
        runCatching {
            part.writeBytes(bytes)
            java.nio.file.Files.move(part.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { part.delete() }
        if (++writes % TRIM_EVERY == 0) trim()
    }

    /** A file that did not decode goes: the next load fetches it again. */
    fun drop(url: String) { fileOf(url)?.delete() }

    fun trim(): Long = dir?.let { DiskBudget.trim(it, BUDGET_BYTES) } ?: 0L

    /** The file name of an address: its SHA-256, so any address (query strings, `=w…` suffixes) is a safe name. */
    fun key(url: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).take(20).joinToString("") { "%02x".format(it) }
}
