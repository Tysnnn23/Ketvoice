package com.ketvoice.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.metrics.performance.JankStats
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch

/**
 * 8.2 « Diagnostic intégré » (owner): off by default (AppSettings.diagnostics). When on, real jank (JankStats), cold
 * start, time to the first sound, Home/search/page load times and memory are written as JSON lines into a local file
 * (at most [MAX_BYTES]); « Partager le diagnostic » hands a copy to the share sheet. Nothing is ever sent by the app.
 * No personal data: a title is a short hash of its id; no account id, name, query, URL or title text is written.
 */
object Diagnostics {
    internal const val MAX_BYTES = 256 * 1024
    private const val NAME = "ketvoice-diagnostic.txt"
    /** About 30 s of frames at 60 Hz per jank line. */
    internal const val FRAMES_PER_LINE = 1800

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ketvoice-diagnostic").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var dir: File? = null
    private var jank: WeakReference<JankStats>? = null
    private val frames = JankTally()
    private var started = false

    val on: Boolean get() = AppSettings.diagnostics.value
    fun now(): Long = SystemClock.elapsedRealtime()

    /** A page, Home or a search answered [startedAt] (from [now]) ago. */
    fun load(kind: String, startedAt: Long, ok: Boolean = true) {
        if (!on || startedAt <= 0L) return
        record("load", "kind" to kind, "ms" to now() - startedAt, "ok" to ok)
    }

    /** From the tap (or the skip) to the first sound; the title is only a hash. */
    fun firstSound(trackId: String?, ms: Long) {
        if (on && ms >= 0L) record("first_sound", "ms" to ms, "title" to hash(trackId))
    }

    fun attach(activity: Activity, cold: Boolean) {
        dir = File(activity.applicationContext.filesDir, "diagnostic")
        if (cold && !started) firstFrame(activity)
        track(activity)
        if (!started) { started = true; watchHome() }
    }

    /** The switch changed: frames are only measured while it is on. */
    fun switched() { jank?.get()?.isTrackingEnabled = on; if (!on) frames.reset() }

    fun flush() { if (on) frames.take()?.let { record("jank", *it, *memory()) } }

    private fun firstFrame(activity: Activity) {
        val since = android.os.Process.getStartUptimeMillis()
        androidx.core.view.OneShotPreDrawListener.add(activity.window.decorView) {
            val ms = SystemClock.uptimeMillis() - since
            // Only a launch that started the process: not one long after the playback service did.
            if (ms in 0..20_000) { if (on) record("cold_start", "ms" to ms, *memory()) }
        }
    }

    private fun track(activity: Activity) {
        val stats = runCatching {
            JankStats.createAndTrack(activity.window) { frame ->
                frames.add(frame.frameDurationUiNanos, frame.isJank)
                if (frames.count >= FRAMES_PER_LINE) flush()
            }
        }.getOrNull() ?: return
        stats.isTrackingEnabled = on
        jank = WeakReference(stats)
    }

    private fun watchHome() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        scope.launch {
            var since = 0L
            com.ketvoice.app.data.HomeFeed.loading.collect { loading ->
                if (loading) since = now() else if (since > 0L) { load("home", since); since = 0L }
            }
        }
    }

    internal fun memory(): Array<Pair<String, Any?>> {
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        return arrayOf("heap_mb" to (rt.totalMemory() - rt.freeMemory()) / mb, "heap_max_mb" to rt.maxMemory() / mb,
            "native_mb" to android.os.Debug.getNativeHeapAllocatedSize() / mb)
    }

    internal fun line(event: String, fields: Array<out Pair<String, Any?>>, wallMs: Long): String {
        val o = JSONObject().put("at", wallMs).put("event", event)
        fields.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        return o.toString()
    }

    private fun record(event: String, vararg fields: Pair<String, Any?>) {
        val d = dir ?: return
        val text = line(event, fields, System.currentTimeMillis())
        writer.execute { runCatching { append(File(d, NAME), text) } }
    }

    /** Appends one line; past [max] the oldest half goes, at a line boundary. */
    internal fun append(file: File, text: String, max: Int = MAX_BYTES) {
        file.parentFile?.mkdirs()
        if (file.length() + text.length + 1 > max) keepTail(file, max / 2)
        file.appendText(text + "\n")
    }

    private fun keepTail(file: File, keep: Int) {
        val bytes = file.readBytes()
        if (bytes.size <= keep) return
        var start = bytes.size - keep
        while (start < bytes.size && bytes[start - 1] != '\n'.code.toByte()) start++
        file.writeBytes(bytes.copyOfRange(start, bytes.size))
    }

    internal fun hash(id: String?): String? = id?.takeIf { it.isNotBlank() }?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(5).joinToString("") { b -> "%02x".format(b) }
    }

    /** Whether there is anything to share yet. */
    fun hasLog(context: Context): Boolean = File(File(context.filesDir, "diagnostic"), NAME).let { it.isFile && it.length() > 0L }

    /** A copy of the file, with a header that says what it is, to the share sheet. Runs after pending lines. */
    fun share(context: Context) {
        val app = context.applicationContext
        writer.execute {
            val file = runCatching { exportCopy(app) }.getOrNull() ?: return@execute
            android.os.Handler(android.os.Looper.getMainLooper()).post { send(app, file) }
        }
    }

    private fun exportCopy(app: Context): File? {
        val src = File(File(app.filesDir, "diagnostic"), NAME).takeIf { it.isFile } ?: return null
        val out = File(app.cacheDir, "exports/$NAME")
        out.parentFile?.mkdirs()
        out.writeText(header(app) + src.readText())
        return out
    }

    internal fun header(app: Context): String {
        val b = com.ketvoice.app.BuildConfig.VERSION_NAME + " (" + com.ketvoice.app.BuildConfig.VERSION_CODE + ")"
        val edition = com.ketvoice.app.Edition.current.label
        return "# Ketvoice diagnostic $b$edition · Android ${android.os.Build.VERSION.SDK_INT} · " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
            "# performance=${AppSettings.highPerformanceMode.value} economy=${EconomyMode.holding.value} " +
            line("now", memory(), System.currentTimeMillis()) + "\n"
    }

    private fun send(app: Context, file: File) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { app.startActivity(Intent.createChooser(send, AppText.t("Partager")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Frames counted on the main thread between two jank lines. */
internal class JankTally {
    var count = 0; private set
    private var janky = 0
    private var worstNs = 0L

    fun add(durationNs: Long, isJank: Boolean) {
        count++
        if (isJank) janky++
        if (durationNs > worstNs) worstNs = durationNs
    }

    fun reset() { count = 0; janky = 0; worstNs = 0L }

    /** The tally as fields, then reset; null when no frame was counted. */
    fun take(): Array<Pair<String, Any?>>? {
        if (count == 0) return null
        val fields = arrayOf<Pair<String, Any?>>("frames" to count, "janky" to janky, "worst_ms" to worstNs / 1_000_000)
        reset()
        return fields
    }
}
