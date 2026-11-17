package com.ketvoice.app.airket

import android.content.Context
import android.os.StatFs
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.PersonalBackup
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.handoff.LanBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** One receiving session: connect, show what is offered, then fetch, verify and install. */
internal class AirKetReceiving(private val context: Context, private val staging: AirKetStaging,
                               private val onProgress: (label: String, done: Long, total: Long) -> Unit) {
    private var client: AirKetClient? = null
    private var manifest = AirKetManifest(emptyList())
    private val layout = AirKetLayout(context.filesDir)

    /** Connects, proves the code and reads what the sender has. Throws [AirKetRefused] for a wrong code or a closed sender. */
    suspend fun connect(boundary: LanBoundary, found: AirKetFound, code: String): AirKetOffer = withContext(Dispatchers.IO) {
        val session = AirKetClient.connect(boundary, found.address, found.port, code)
        client = session
        manifest = AirKetManifest.decode(requireNotNull(session.document("manifest")) { "Manifest" })
        val hello = session.hello
        AirKetOffer(hello.optString("name", found.name), hello.optLong("backupBytes"), manifest.titles, manifest.downloadFiles,
            manifest.downloadBytes, manifest.leftOutDash, hasLocalData(), staging.held(wanted(true)))
    }

    private fun hasLocalData() = PlaylistStore.lists.value.isNotEmpty() || AppSettings.favoriteIds.value.isNotEmpty()

    private fun wanted(withDownloads: Boolean) = manifest.entries.filter { layout.accepts(it) && (withDownloads || !it.kind.optional) }
        .sortedBy { if (it.kind == AirKetEntry.Kind.CustomLyrics) 0 else 1 }

    /** The whole transfer after the listener's choice. */
    suspend fun run(withDownloads: Boolean, replace: Boolean): AirKetSummary = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val session = requireNotNull(client)
        onProgress("Sauvegarde", 0, 0)
        val data = PersonalBackup.decode(requireNotNull(session.document("backup")) { "Backup" })
        PersonalBackup.apply(context, data, replace)
        val entries = wanted(withDownloads)
        require(withDownloads.not() || hasRoom(entries)) { "Space" }
        val fetched = fetchAll(session, entries)
        onProgress("Installation", fetched.bytes, fetched.bytes)
        val installed = AirKetInstaller(context, staging).install(entries.filter { staging.verified(it).isFile }, manifest)
        staging.clear()
        runCatching { session.close() }
        AirKetSummary(data.playlists.size, data.favorites.size, installed.titles, installed.files, fetched.failed, fetched.bytes,
            (System.currentTimeMillis() - started) / 1000, manifest.leftOutDash, withDownloads, replace)
    }

    private class Fetched(val bytes: Long, val failed: Int)

    private fun hasRoom(entries: List<AirKetEntry>): Boolean {
        val need = entries.sumOf { it.size - staging.held(listOf(it)) }
        return StatFs(context.filesDir.path).availableBytes >= need + 256L * 1024 * 1024
    }

    private suspend fun fetchAll(session: AirKetClient, entries: List<AirKetEntry>): Fetched {
        val total = entries.sumOf { it.size }
        var done = staging.held(entries)
        var failed = 0
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            if (staging.verified(entry).let { it.isFile && it.length() == entry.size }) continue
            onProgress(entry.name, done, total)
            if (!fetchOne(session, entry) { done += it; onProgress(entry.name, done, total) }) failed++
        }
        return Fetched(done, failed)
    }

    /** A file that fails its SHA-256 is asked again once from the start; a second failure is counted, not hidden. */
    private fun fetchOne(session: AirKetClient, entry: AirKetEntry, onBytes: (Long) -> Unit): Boolean {
        repeat(2) {
            when (session.file(entry, staging.part(entry), onBytes)) {
                Fetch.Ok -> { staging.markVerified(entry); return true }
                Fetch.Missing -> return false
                Fetch.Bad -> Unit
            }
        }
        return false
    }

    /** Stops now; the staging folder keeps what was verified and the part in progress. */
    fun cancel() { client?.cancel() }

    fun close() { client?.close(); client = null }
}

internal fun Throwable.isStop(): Boolean = this is CancellationException || this is java.io.IOException || this is java.net.SocketException
