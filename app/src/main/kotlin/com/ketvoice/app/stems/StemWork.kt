package com.ketvoice.app.stems

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.ketvoice.app.automix.AnalysisDepth
import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The heavy work beside the music runs in a process of its own, `:stems` (6.1, owner: « La séparation des
 * voix et Ketmix tournent dans un processus à part : si le système les tue, la musique continue »): the
 * separation models (tens to hundreds of megabytes once loaded) and the preparation of downloaded titles
 * never live in the process that plays. A battery manager or Android's memory killer that picks the biggest
 * process picks that one; the music goes on, and what was lost is asked again later.
 *
 * Requests travel by [Messenger]; their results by files, as before: separations are stored by [StemStore]
 * and measurements by [com.ketvoice.app.automix.AnalysisStore], which the playing process reads. The sound of
 * a part to separate goes as a temporary file in the cache. Host tests (Robolectric) run every request here,
 * in the same process ([inProcess]).
 */
internal object StemWork {
    enum class Outcome {
        Done,
        /** The work could not be done (no model, a sound that cannot be read): asking again changes nothing. */
        Failed,
        /** The work process was stopped, or could not be reached: worth asking again later. */
        Lost
    }

    const val MSG_PART = 1
    const val MSG_WHOLE = 2
    const val MSG_LIBRARY = 3
    const val MSG_CANCEL = 4
    const val MSG_CLOSE = 5
    const val MSG_HOLD = 6
    const val MSG_PROGRESS = 100
    const val MSG_DONE = 101

    /** Requests run in this process: the host tests (Robolectric, or plain JVM stubs), where there is no second process. */
    @Volatile internal var inProcess: Boolean = runCatching { android.os.Build.FINGERPRINT.let { it == null || it == "robolectric" } }.getOrDefault(true)

    /** The work process is let go after this long without a request: it then frees its models. */
    private const val IDLE_UNBIND_MS = 60_000L
    private const val CONNECT_MS = 10_000L
    /**
     * Its memory priority never above the playing process's, its threads in the background group, and Android
     * free to stop it first when memory runs short (it is started again when needed).
     */
    private const val BIND_FLAGS = Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND or Context.BIND_ALLOW_OOM_MANAGEMENT

    private class Pending(val done: CompletableDeferred<Outcome>, val progress: ((Bundle) -> Unit)?)

    private val pending = ConcurrentHashMap<Int, Pending>()
    private val tokens = AtomicInteger(1)
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var remote: Messenger? = null
    private var connection: ServiceConnection? = null
    private var connected = CompletableDeferred<Messenger?>()
    @Volatile private var holding = false

    private val handler by lazy { Handler(HandlerThread("ketvoice-stem-replies").apply { start() }.looper) { onReply(it); true } }
    private val replies by lazy { Messenger(handler) }
    private val unbindLater = Runnable { synchronized(lock) { if (pending.isEmpty()) unbind() } }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // Sound left by a request that never finished (the process stopped mid-way).
        Thread({ runCatching { pcmDir(context.applicationContext).listFiles()?.forEach { it.delete() } } }, "ketvoice-stem-clean")
            .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    internal fun pcmDir(context: Context) = File(context.cacheDir, "stem-work").apply { mkdirs() }

    /**
     * Separates part of a title from its sound [pcm] (interleaved 16-bit stereo at [rate], its first frame at
     * [startUs]) and stores it. [quality] < 0 does what [StemEngine.prepare] does (only when nothing as good is
     * stored); otherwise that model is used ([StemEngine.store]).
     */
    suspend fun part(trackId: String, durationMs: Long, intro: Boolean, pcm: ShortArray, rate: Int, startUs: Long,
                     quality: Int = -1): Outcome {
        if (inProcess) return withContext(Dispatchers.Default) {
            val ctx = currentCoroutineContext()
            val ok = StemJobs.part(trackId, durationMs, intro, pcm, rate, startUs, quality) { !ctx.isActive }
            if (ok) Outcome.Done else Outcome.Failed
        }
        val context = appContext ?: return Outcome.Lost
        val file = File(pcmDir(context), "${java.util.UUID.randomUUID()}.pcm")
        try {
            withContext(Dispatchers.IO) { writePcm(file, pcm) }
            return request(MSG_PART, Bundle().apply {
                putString("id", trackId); putLong("durationMs", durationMs); putBoolean("intro", intro)
                putString("pcm", file.absolutePath); putInt("rate", rate); putLong("startUs", startUs); putInt("quality", quality)
            }, null)
        } finally {
            file.delete()
        }
    }

    /** Separates a whole title read from [uri] with the model of [quality] and stores it; [progress] hears the share done. */
    suspend fun whole(trackId: String, uri: Uri, quality: Int, progress: (Float) -> Unit): Outcome {
        if (inProcess) {
            val context = appContext ?: return Outcome.Failed
            val ctx = currentCoroutineContext()
            return if (StemJobs.whole(context, trackId, uri, quality, { ctx.isActive }, progress)) Outcome.Done else Outcome.Failed
        }
        return request(MSG_WHOLE, Bundle().apply { putString("id", trackId); putString("uri", uri.toString()); putInt("quality", quality) }) {
            progress(it.getFloat("fraction"))
        }
    }

    /** Prepares downloaded titles while the phone charges ([StemLibrary.runItems]); [progress] hears titles done and found. */
    suspend fun library(context: Context, items: List<StemLibrary.LibraryItem>, depth: AnalysisDepth, separate: Boolean,
                        progress: (Int, Int) -> Unit): Outcome {
        if (inProcess) {
            com.ketvoice.app.automix.AnalysisStore.init(context)
            StemLibrary.runItems(items, depth, separate, goOn = { StemModel.charging() }, held = { holding }, progress = progress)
            return Outcome.Done
        }
        return request(MSG_LIBRARY, Bundle().apply {
            putStringArray("ids", items.map { it.id }.toTypedArray())
            putStringArray("paths", items.map { it.path }.toTypedArray())
            putLongArray("lengths", items.map { it.trackDurationMs }.toLongArray())
            putInt("depth", depth.ordinal); putBoolean("separate", separate)
        }) { progress(it.getInt("done"), it.getInt("total")) }
    }

    /** Holds the library's work between two titles while a mix is prepared or played here. */
    fun hold(on: Boolean) {
        if (holding == on) return
        holding = on
        if (!inProcess) runCatching { remote?.send(Message.obtain(null, MSG_HOLD, 0, if (on) 1 else 0)) }
    }

    /** The models are let go (deleted from Settings): the work process closes them now. */
    fun closeModels() {
        if (inProcess) { StemEngine.close(); return }
        runCatching { remote?.send(Message.obtain(null, MSG_CLOSE)) }
    }

    private suspend fun request(what: Int, data: Bundle, progress: ((Bundle) -> Unit)?): Outcome {
        val messenger = messenger() ?: return Outcome.Lost
        handler.removeCallbacks(unbindLater)
        val token = tokens.getAndIncrement()
        val waiting = Pending(CompletableDeferred(), progress)
        pending[token] = waiting
        data.putInt("qualitySetting", AppSettings.ketmixStemsQuality.value)
        val message = Message.obtain(null, what, token, 0).apply { this.data = data; replyTo = replies }
        try {
            if (runCatching { messenger.send(message) }.isFailure) return Outcome.Lost
            return waiting.done.await()
        } catch (cancelled: CancellationException) {
            runCatching { remote?.send(Message.obtain(null, MSG_CANCEL, token, 0)) }
            throw cancelled
        } finally {
            pending.remove(token)
            if (pending.isEmpty()) handler.postDelayed(unbindLater, IDLE_UNBIND_MS)
        }
    }

    private fun onReply(message: Message) {
        val waiting = pending[message.arg1] ?: return
        when (message.what) {
            MSG_PROGRESS -> runCatching { waiting.progress?.invoke(message.data) }
            MSG_DONE -> waiting.done.complete(if (message.arg2 == 1) Outcome.Done else Outcome.Failed)
        }
    }

    private suspend fun messenger(): Messenger? {
        remote?.let { return it }
        val context = appContext ?: return null
        val waiter = synchronized(lock) {
            if (connection == null) {
                connected = CompletableDeferred()
                val c = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val m = binder?.let(::Messenger)
                        remote = m
                        if (m != null && holding) runCatching { m.send(Message.obtain(null, MSG_HOLD, 0, 1)) }
                        synchronized(lock) { connected.complete(m) }
                    }

                    // The work process died (memory, a battery manager): Android starts it again; what it was doing is lost.
                    override fun onServiceDisconnected(name: ComponentName?) {
                        remote = null
                        synchronized(lock) { if (connected.isCompleted) connected = CompletableDeferred() }
                        lost()
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        remote = null
                        lost()
                        synchronized(lock) { unbind() }
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        synchronized(lock) { connected.complete(null); unbind() }
                    }
                }
                val bound = runCatching { context.bindService(Intent(context, StemWorkService::class.java), c, BIND_FLAGS) }.getOrDefault(false)
                if (!bound) { runCatching { context.unbindService(c) }; return null }
                connection = c
            }
            connected
        }
        return withTimeoutOrNull(CONNECT_MS) { waiter.await() }
    }

    private fun lost() {
        val waiting = pending.values.toList()
        if (waiting.isEmpty()) return
        com.ketvoice.app.data.PlaybackJournal.add("separation and Ketmix work stopped by Android · the music goes on, the work will be asked again")
        waiting.forEach { it.done.complete(Outcome.Lost) }
    }

    /** Lets the work process go now (the host tests, between two services). */
    internal fun disconnect() { synchronized(lock) { unbind() } }

    /** Lets the work process go; called under [lock]. */
    private fun unbind() {
        val c = connection ?: return
        connection = null
        remote = null
        connected.complete(null)
        runCatching { appContext?.unbindService(c) }
    }

    internal fun writePcm(file: File, pcm: ShortArray) {
        file.outputStream().channel.use { channel ->
            val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < pcm.size) {
                buffer.clear()
                val n = minOf(pcm.size - i, buffer.capacity() / 2)
                buffer.asShortBuffer().put(pcm, i, n)
                buffer.limit(n * 2)
                while (buffer.hasRemaining()) channel.write(buffer)
                i += n
            }
        }
    }

    internal fun readPcm(file: File): ShortArray {
        val length = file.length()
        require(length in 0..(256L * 1024 * 1024) && length % 2 == 0L) { "bad sound" }
        val out = ShortArray((length / 2).toInt())
        file.inputStream().channel.use { channel ->
            val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < out.size) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity(), (out.size - i) * 2))
                while (buffer.hasRemaining()) if (channel.read(buffer) < 0) break
                buffer.flip()
                val n = buffer.remaining() / 2
                if (n == 0) break
                buffer.asShortBuffer().get(out, i, n)
                i += n
            }
        }
        return out
    }
}

/** What the work process does for each request; the same functions run here in the host tests. */
internal object StemJobs {
    fun part(trackId: String, durationMs: Long, intro: Boolean, pcm: ShortArray, rate: Int, startUs: Long, quality: Int,
             cancelled: () -> Boolean): Boolean = runCatching {
        if (quality < 0) StemEngine.prepare(trackId, durationMs, intro, pcm, rate, startUs, cancelled)
        else StemEngine.store(trackId, durationMs, intro, pcm, rate, startUs, quality, cancelled)
    }.onFailure { android.util.Log.w("KetvoiceStems", "separation failed: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** Reads a whole title from [uri] (a downloaded file, or the address it is streamed from) and separates it. */
    fun whole(context: Context, trackId: String, uri: Uri, quality: Int, goOn: () -> Boolean, progress: (Float) -> Unit): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(index)
            if (StemLibrary.channels(format) != 2) return false
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else return false
            if (durationMs <= 0) return false
            extractor.selectTrack(index)
            return StemLibrary.separateWhole(extractor, format, trackId, durationMs, quality, goOn) { progress(it.coerceIn(0f, 1f)) }
        } catch (_: java.io.IOException) {
            return false
        } finally {
            runCatching { extractor.release() }
        }
    }
}
