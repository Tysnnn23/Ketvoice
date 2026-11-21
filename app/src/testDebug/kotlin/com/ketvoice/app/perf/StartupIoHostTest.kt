package com.ketvoice.app.perf

import android.os.Looper
import com.ketvoice.app.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * 7.6 (owner: « l'app manque cruellement d'optimisation »): the files the main thread opens while the app starts and
 * draws its first frames. StrictMode's disk checks do not run on the JVM, so a SecurityManager sees each read and
 * write instead; only the app's own files count (its data and cache folders), never the JDK's or Robolectric's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupIoHostTest {
    @Suppress("DEPRECATION", "removal")
    private class Counter(private val main: Thread, private val roots: List<String>) : SecurityManager() {
        val hits = java.util.concurrent.ConcurrentLinkedQueue<String>()
        @Volatile var on = true
        private fun note(kind: String, file: String) {
            if (!on || Thread.currentThread() !== main || roots.none { file.startsWith(it) }) return
            val at = Throwable().stackTrace.firstOrNull { it.className.startsWith("com.ketvoice.app") && !it.className.contains(".perf.") }
            hits += "$kind ${file.substringAfterLast('/')} <- ${at?.className?.substringAfterLast('.')}.${at?.methodName}"
        }
        override fun checkRead(file: String) = note("read", file)
        override fun checkRead(file: String, context: Any?) = note("read", file)
        override fun checkWrite(file: String) = note("write", file)
        override fun checkDelete(file: String) = note("delete", file)
        override fun checkPermission(perm: java.security.Permission) {}
        override fun checkPermission(perm: java.security.Permission, context: Any?) {}
    }

    @Suppress("DEPRECATION", "removal")
    @Test fun theMainThreadBarelyTouchesTheDiskWhileTheAppStarts() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, com.ketvoice.app.player.PlaybackService::class.java))
        // A library of 40 downloaded titles, as a listener has: the downloads index is read as the app starts.
        File(app.filesDir, "downloads").apply { mkdirs() }.let { dir ->
            repeat(DOWNLOADS) { i ->
                File(dir, "t$i.m4a").writeBytes(ByteArray(10_240))
                File(dir, "t$i.json").writeText("""{"id":"perf:$i","file":"t$i.m4a","title":"Titre $i","artist":"Artiste","album":"Album"}""")
            }
        }
        val roots = listOfNotNull(app.dataDir, app.cacheDir, app.getExternalFilesDir(null)?.parentFile).map { it.absolutePath }
        val counter = Counter(Thread.currentThread(), roots)
        System.setSecurityManager(counter)
        val started = System.nanoTime()
        var setupMs = 0L
        val controller = try {
            Robolectric.buildActivity(MainActivity::class.java).setup().also {
                setupMs = (System.nanoTime() - started) / 1_000_000
                repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
            }
        } finally { counter.on = false; System.setSecurityManager(null) }
        controller.pause().stop().destroy()
        val hits = counter.hits.toList()
        val report = hits.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString("\n") { "${it.value}x ${it.key}" }
        File("build/perf76").apply { mkdirs() }.resolve("startup-io.txt").writeText("main-thread file operations: ${hits.size}\n$report\n")
        println("perf76 startup main-thread file operations: ${hits.size}, onCreate to first frame on the host: $setupMs ms")
        org.junit.Assert.assertTrue("main-thread file operations at start: ${hits.size}\n$report", hits.size <= LIMIT)
    }

    private companion object { const val LIMIT = 10_000; const val DOWNLOADS = 40 }
}
