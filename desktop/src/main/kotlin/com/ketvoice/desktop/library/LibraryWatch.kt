package com.ketvoice.desktop.library

import com.ketvoice.desktop.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * « Bibliothèque surveillée » (Windows 2.0): the music folders the listener chose ([LocalFolders]) are watched with the
 * system's file notifications, every subfolder included; added, changed and removed files reach the library within a
 * couple of seconds, with their tags, embedded cover and lyrics (embedded, or a `.lrc` beside the file). Bursts (a
 * whole album copied, a tag editor saving) are gathered by [Debounce] and read once. Lost notifications rescan.
 */
object LibraryWatch {
    /** Gathers changed paths: they are handed over once nothing changed for [quietMs], or [maxMs] after the first. */
    class Debounce(private val quietMs: Long = 1_200, private val maxMs: Long = 8_000) {
        private val paths = LinkedHashSet<String>()
        private var first = -1L
        private var last = -1L

        @Synchronized fun add(path: String, now: Long) {
            if (paths.isEmpty()) first = now
            paths += path; last = now
        }

        /** The paths to read now (empty while changes are still coming in). */
        @Synchronized fun due(now: Long): Set<String> {
            if (paths.isEmpty() || (now - last < quietMs && now - first < maxMs)) return emptySet()
            return paths.toSet().also { paths.clear(); first = -1; last = -1 }
        }

        @Synchronized fun pending(): Int = paths.size
    }

    @Volatile private var service: WatchService? = null
    private var job: Job? = null
    private val keys = HashMap<WatchKey, Path>()
    internal val debounce = Debounce()
    /** Test seam: what is done with the paths gathered (the library reads them). */
    internal var apply: (Set<String>) -> Unit = { LocalFolders.update(it) }
    internal var rescan: () -> Unit = { LocalFolders.rescan() }

    /** Watches the chosen folders now and whenever they change. */
    fun start(scope: CoroutineScope) {
        scope.launch { LocalFolders.folders.drop(1).collect { watch(scope, it) } }
        watch(scope, LocalFolders.folders.value)
    }

    @Synchronized internal fun watch(scope: CoroutineScope, folders: List<String>) {
        stop()
        if (folders.isEmpty()) return
        val ws = runCatching { FileSystems.getDefault().newWatchService() }.getOrNull() ?: return
        service = ws
        folders.forEach { register(ws, File(it)) }
        job = scope.launch(Dispatchers.IO) { loop(ws) }
        scope.launch(Dispatchers.IO) { flushLoop(ws) }
        AppLog.i("library", "watching ${folders.size} music folders (${keys.size} folders in all)")
    }

    @Synchronized fun stop() {
        runCatching { service?.close() }
        service = null; job?.cancel(); job = null
        synchronized(keys) { keys.clear() }
    }

    /** [dir] and every folder under it (hidden ones left out, as the scan does). */
    private fun register(ws: WatchService, dir: File) {
        dir.walkTopDown().onEnter { it == dir || (!it.isHidden && !it.name.startsWith(".")) }.filter { it.isDirectory }.forEach { d ->
            runCatching { d.toPath().register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }.getOrNull()?.let { synchronized(keys) { keys[it] = d.toPath() } }
        }
    }

    private fun loop(ws: WatchService) {
        try {
            while (true) {
                val key = ws.take()
                val dir = synchronized(keys) { keys[key] }
                for (event in key.pollEvents()) {
                    if (event.kind() == OVERFLOW) { rescan(); continue }
                    val path = dir?.resolve(event.context() as Path) ?: continue
                    if (event.kind() == ENTRY_CREATE && path.toFile().isDirectory) register(ws, path.toFile())
                    if (relevant(path.toFile())) debounce.add(path.toString(), System.currentTimeMillis())
                }
                if (!key.reset()) synchronized(keys) { keys.remove(key) }
            }
        } catch (_: ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
        }
    }

    private suspend fun flushLoop(ws: WatchService) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive && service === ws) {
            delay(400)
            val due = debounce.due(System.currentTimeMillis())
            if (due.isNotEmpty()) runCatching { apply(due) }.onFailure { AppLog.w("library", "watched change: $it") }
        }
    }

    /** Audio files, lyrics beside them, and folders (a folder moved in or out). */
    internal fun relevant(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in LocalFolders.EXTENSIONS || ext == "lrc" || ext.isEmpty() || file.isDirectory
    }

}
