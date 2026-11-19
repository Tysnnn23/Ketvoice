package com.ketvoice.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ketvoice.app.util.CanvasTranscoder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Big Canvas take little room (7.2): while the phone charges, a Canvas video whose bitrate is far above what its
 * picture needs is encoded again on the device (HEVC when the phone has an encoder for it, H.264 otherwise), at
 * the screen's resolution and a bitrate that keeps it sharp. The copy replaces the file under the same name only
 * once it is checked (it reads, it lasts as long, it is clearly smaller); until then, and whenever anything fails,
 * the original stays. The same name keeps every reference: the album's Canvas shared by its titles, downloads
 * that need it (DiskBudget protects them as before), and the offline player, which shows the file at once.
 */
object CanvasCompact {
    /** Files below this are already small: they are left as they are. */
    const val MIN_BYTES = 1_500_000L
    /** The copy must be at most this share of the original, or the original stays. */
    const val KEEP_RATIO = .8
    /** Bits per pixel and frame that keep a Canvas sharp: HEVC needs about a third less than H.264. */
    const val BPP_HEVC = .07
    const val BPP_AVC = .10
    const val MIN_BITRATE = 1_500_000
    const val MAX_BITRATE = 8_000_000
    /** A file written less than this long ago may still be a download: it waits for the next pass. */
    const val SETTLED_MS = 120_000L
    /** One pass does at most this many files, so a short charge is not spent on one folder. */
    const val PER_PASS = 8

    data class Source(val name: String, val bytes: Long, val modified: Long, val width: Int, val height: Int,
                      val durationUs: Long, val fps: Int, val kept: Boolean)
    data class Plan(val name: String, val width: Int, val height: Int, val bitrate: Int, val fps: Int, val hevc: Boolean,
                    val expectedBytes: Long)

    /** The picture size that fits [longSide] (the screen's), even, never larger than the source. */
    fun size(width: Int, height: Int, longSide: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= longSide || long <= 0) return even(width) to even(height)
        val scale = longSide.toDouble() / long
        return even((width * scale).toInt()) to even((height * scale).toInt())
    }
    private fun even(v: Int) = (v / 2 * 2).coerceAtLeast(2)

    fun bitrate(width: Int, height: Int, fps: Int, hevc: Boolean): Int =
        (width.toDouble() * height * fps.coerceIn(15, 60) * (if (hevc) BPP_HEVC else BPP_AVC)).toInt().coerceIn(MIN_BITRATE, MAX_BITRATE)

    /** What to encode, or null when the file is small, unread, too fresh, already done or would not shrink enough. */
    fun plan(source: Source, now: Long, longSide: Int, hevc: Boolean, done: Boolean): Plan? {
        if (done || source.bytes < MIN_BYTES || now - source.modified < SETTLED_MS) return null
        if (source.width <= 0 || source.height <= 0 || source.durationUs < 500_000L) return null
        val (w, h) = size(source.width, source.height, longSide)
        val fps = source.fps.coerceIn(15, 60)
        val rate = bitrate(w, h, fps, hevc)
        val expected = (rate.toLong() * source.durationUs / 8_000_000L)
        if (expected > source.bytes * KEEP_RATIO * .85) return null
        return Plan(source.name, w, h, rate, fps, hevc, expected)
    }

    /** Files downloads need first (they stay for good), then the biggest; [PER_PASS] at most. */
    fun order(plans: List<Pair<Source, Plan>>): List<Plan> =
        plans.sortedWith(compareByDescending<Pair<Source, Plan>> { it.first.kept }.thenByDescending { it.first.bytes - it.second.expectedBytes })
            .take(PER_PASS).map { it.second }

    /** The copy replaces the original only when it reads, lasts as long and is clearly smaller. */
    fun accept(originalBytes: Long, originalDurationUs: Long, copyBytes: Long, copyDurationUs: Long, copyWidth: Int, copyHeight: Int,
               plan: Plan): Boolean =
        copyBytes in 10_001..(originalBytes * KEEP_RATIO).toLong() &&
            kotlin.math.abs(copyDurationUs - originalDurationUs) <= maxOf(200_000L, originalDurationUs / 33) &&
            copyWidth == plan.width && copyHeight == plan.height

    /** Room the pass needs on the disk: the copy, twice for safety, and a margin. */
    fun hasRoom(freeBytes: Long, plan: Plan): Boolean = freeBytes > plan.expectedBytes * 2 + 64L * 1024 * 1024

    /** A file is done once for its size: a new download under the same name is looked at again. */
    fun doneKey(name: String, bytes: Long) = "$name|$bytes"

    // ---- On the device.

    private const val PREFS = "ketvoice_canvas_compact"

    /** Encodes what [plan] picks in the Canvas folder; returns the bytes freed. Never throws. */
    fun pass(context: Context, stop: () -> Boolean = { false }): Long = runCatching {
        val dir = File(context.filesDir, "canvas")
        val files = dir.listFiles { f -> f.isFile && f.extension == "mp4" }.orEmpty()
        if (files.isEmpty()) return@runCatching 0L
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hevc = CanvasTranscoder.hasHevcEncoder()
        val metrics = context.resources.displayMetrics
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(720, 1920)
        val refs = DiskBudget.downloadRefs(File(context.filesDir, "downloads"))
        val kept = DiskBudget.canvasKept(refs) { album, artist -> CanvasRepo.albumFile(album, artist) }
        val now = System.currentTimeMillis()
        val plans = files.mapNotNull { file ->
            if (prefs.getBoolean(doneKey(file.name, file.length()), false)) return@mapNotNull null
            val probe = CanvasTranscoder.probe(file) ?: return@mapNotNull null
            val source = Source(file.name, file.length(), file.lastModified(), probe.width, probe.height, probe.durationUs, probe.fps, kept(file))
            plan(source, now, longSide, hevc, done = false)?.let { source to it }
        }
        var freed = 0L
        for (plan in order(plans)) {
            if (stop()) break
            val original = File(dir, plan.name)
            if (!hasRoom(dir.usableSpace, plan)) break
            val bytes = original.length()
            val copy = File(dir, plan.name.removeSuffix(".mp4") + ".compact.part")
            copy.delete()
            val ok = CanvasTranscoder.transcode(original, copy, plan.width, plan.height, plan.bitrate, plan.fps, plan.hevc) &&
                CanvasTranscoder.probe(original)?.let { before -> CanvasTranscoder.probe(copy)?.let { after ->
                    accept(bytes, before.durationUs, copy.length(), after.durationUs, after.width, after.height, plan) } } == true &&
                original.length() == bytes
            if (ok && copy.renameTo(original)) {
                freed += bytes - original.length()
                prefs.edit().putBoolean(doneKey(original.name, original.length()), true).apply()
            } else {
                copy.delete()
                // Tried and not better: not tried again for this file.
                prefs.edit().putBoolean(doneKey(original.name, bytes), true).apply()
            }
        }
        freed
    }.getOrDefault(0L)
}

/** Runs [CanvasCompact.pass] while the phone charges, with enough battery and storage. */
class CanvasCompactWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // The listener watching a Canvas keeps the video decoders for it.
        if (com.ketvoice.app.automix.Automix.appInFront) return Result.success()
        CanvasCompact.pass(applicationContext) { isStopped || com.ketvoice.app.automix.Automix.appInFront }
        return Result.success()
    }

    companion object {
        private const val NAME = "canvas-compact"
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CanvasCompactWorker>(12, TimeUnit.HOURS).setConstraints(constraints).build())
        }
    }
}
