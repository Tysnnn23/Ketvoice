package com.ketvoice.app.stems

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.ketvoice.app.automix.AnalysisDepth
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The work process (`:stems`, 6.1): separations and the preparation of downloaded titles, asked by the
 * playing process through [StemWork]. Three lanes as before: a title the listener waits for (the voice
 * slider), the parts Ketmix prepares, the library while charging; all on threads of background priority.
 * Nothing here plays, and nothing here writes the settings or the playback journal: they belong to the
 * playing process.
 */
class StemWorkService : Service() {
    private val requests = HandlerThread("ketvoice-stem-requests").apply { start() }
    private val messenger = Messenger(Handler(requests.looper) { handle(it); true })
    private val scope = CoroutineScope(SupervisorJob())
    private val voiceLane = lane("ketvoice-voice")
    private val partLane = lane("ketvoice-stem")
    private val libraryLane = lane("ketvoice-library")
    private val jobs = ConcurrentHashMap<Int, Job>()
    @Volatile private var held = false
    private val voices = AtomicInteger(0)

    private fun lane(name: String): CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, name).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    override fun onCreate() {
        super.onCreate()
        StemModel.init(applicationContext)
        AnalysisStore.init(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        jobs.clear()
        // Nobody needs the work process any more: its models go (a separation still running closes them when it ends).
        Thread({ runCatching { AnalysisStore.save(); StemEngine.close() } }, "ketvoice-stem-close").apply { isDaemon = true }.start()
        requests.quitSafely()
        super.onDestroy()
    }

    private fun handle(message: Message) {
        val token = message.arg1
        val reply = message.replyTo
        // The message is recycled once handled: its data is copied for the work that follows.
        val data = Bundle(message.data ?: Bundle.EMPTY)
        when (message.what) {
            StemWork.MSG_CANCEL -> jobs.remove(token)?.cancel()
            StemWork.MSG_HOLD -> held = message.arg2 == 1
            StemWork.MSG_CLOSE -> scope.launch(partLane) { StemEngine.close() }
            StemWork.MSG_PART -> start(token, reply, partLane, data) {
                val file = File(data.getString("pcm") ?: return@start false)
                val pcm = try { StemWork.readPcm(file) } finally { file.delete() }
                val ctx = currentCoroutineContext()
                StemJobs.part(data.getString("id") ?: return@start false, data.getLong("durationMs"), data.getBoolean("intro"), pcm,
                    data.getInt("rate"), data.getLong("startUs"), data.getInt("quality", -1)) { !ctx.isActive }
            }
            StemWork.MSG_WHOLE -> start(token, reply, voiceLane, data) { progress ->
                val id = data.getString("id") ?: return@start false
                val uri = data.getString("uri")?.let(Uri::parse) ?: return@start false
                val ctx = currentCoroutineContext()
                voices.incrementAndGet()
                try {
                    StemJobs.whole(applicationContext, id, uri, data.getInt("quality"), { ctx.isActive }) { f ->
                        progress(Bundle().apply { putFloat("fraction", f) })
                    }
                } finally { voices.decrementAndGet() }
            }
            StemWork.MSG_LIBRARY -> start(token, reply, libraryLane, data) { progress ->
                val ids = data.getStringArray("ids") ?: return@start false
                val paths = data.getStringArray("paths") ?: return@start false
                val lengths = data.getLongArray("lengths") ?: return@start false
                val items = ids.indices.filter { it < paths.size && it < lengths.size }.map { StemLibrary.LibraryItem(ids[it], paths[it], lengths[it]) }
                val depth = AnalysisDepth.entries.getOrElse(data.getInt("depth")) { AnalysisDepth.Balanced }
                StemLibrary.runItems(items, depth, data.getBoolean("separate"),
                    goOn = { StemModel.charging() },
                    // A voice the listener waits for comes first, then the mix being prepared in the playing process.
                    held = { held || voices.get() > 0 },
                    progress = { done, total -> progress(Bundle().apply { putInt("done", done); putInt("total", total) }) },
                    separating = { StemModel.charging() && voices.get() == 0 })
                true
            }
        }
    }

    private fun start(token: Int, reply: Messenger?, lane: CoroutineDispatcher, data: Bundle,
                      body: suspend (progress: (Bundle) -> Unit) -> Boolean) {
        val job = scope.launch(lane, start = CoroutineStart.LAZY) {
            // The models may have been downloaded or deleted since the last request; the setting comes with it.
            StemModel.init(applicationContext)
            AppSettings.ketmixStemsQuality.value = data.getInt("qualitySetting", 0).coerceIn(0, 2)
            val ok = try {
                body { bundle -> send(reply, Message.obtain(null, StemWork.MSG_PROGRESS, token, 0).apply { this.data = bundle }) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                android.util.Log.w("KetvoiceStems", "work failed: ${failure.javaClass.simpleName}")
                false
            }
            send(reply, Message.obtain(null, StemWork.MSG_DONE, token, if (ok) 1 else 0))
        }
        jobs[token] = job
        job.invokeOnCompletion { jobs.remove(token) }
        job.start()
    }

    private fun send(reply: Messenger?, message: Message) {
        runCatching { reply?.send(message) }
    }
}
