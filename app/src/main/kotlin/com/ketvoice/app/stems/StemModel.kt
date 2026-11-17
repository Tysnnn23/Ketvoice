package com.ketvoice.app.stems

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The separation models, optional downloads. Each file is checked against its SHA-256 before it is
 * used, so what runs is exactly what was published by the workflows of this repository.
 * - [fast] (5.10.0): Deezer's Spleeter 2 stems (MIT), `ketmix-stems-v1`, quick enough for every mix.
 * - [hq] (6.0.0): UVR-MDX-NET Voc FT (Ultimate Vocal Remover, MIT), `ketmix-stems-v2`: about 3 dB
 *   cleaner instrumentals and voices on MUSDB18-HQ, and about ten times slower.
 */
internal class ModelPack(private val release: String, val parts: List<Part>, private val folder: String) {
    class Part(val name: String, val bytes: Long, val sha256: String)

    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    val totalBytes: Long get() = parts.sumOf { it.bytes }
    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var dir: File? = null

    fun init(filesDir: File) {
        val d = File(filesDir, folder).apply { mkdirs() }
        dir = d
        if (_state.value !is State.Downloading) _state.value = if (installed(d)) State.Ready else State.Missing
    }

    val ready: Boolean get() = _state.value == State.Ready

    fun file(part: Part): File? = dir?.let { File(it, part.name) }

    /** Present with the expected size; the digest was checked when the file was written. */
    private fun installed(d: File) = parts.all { File(d, it.name).length() == it.bytes }

    fun download() {
        val d = dir ?: return
        if (job?.isActive == true || ready) return
        job = scope.launch {
            _state.value = State.Downloading(0f)
            try {
                var done = 0L
                for (part in parts) {
                    val target = File(d, part.name)
                    if (target.length() != part.bytes) fetch(part, File(d, part.name + ".part"), target) { n ->
                        _state.value = State.Downloading(((done + n).toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                    done += part.bytes
                }
                _state.value = if (installed(d)) State.Ready else State.Failed("incomplete")
            } catch (cancelled: CancellationException) {
                _state.value = State.Missing
                throw cancelled
            } catch (failure: Exception) {
                _state.value = State.Failed(failure.message ?: failure.javaClass.simpleName)
            }
        }
    }

    fun cancel() { job?.cancel(); job = null }

    fun delete() {
        cancel()
        _state.value = State.Missing
        // A separation may be running: the model is closed once it is done, away from the screen.
        scope.launch {
            StemEngine.close()
            // The models live in the work process since 6.1: it lets them go too.
            StemWork.closeModels()
            dir?.listFiles()?.forEach { it.delete() }
        }
    }

    private suspend fun fetch(part: Part, tmp: File, target: File, progress: (Long) -> Unit) {
        val free = tmp.parentFile?.usableSpace ?: 0L
        if (free in 1 until part.bytes + 50L * 1024 * 1024) throw IOException("storage")
        val connection = URL(release + part.name).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Ketvoice")
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            connection.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        if (read > part.bytes) throw IOException("size")
                        digest.update(buffer, 0, n); out.write(buffer, 0, n)
                        progress(read)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (read != part.bytes || !sha.equals(part.sha256, ignoreCase = true)) throw IOException("checksum")
            if (!tmp.renameTo(target)) throw IOException("rename")
        } finally {
            connection.disconnect()
            tmp.delete()
        }
    }
}

/** The high-quality model as published by publish-stem-model-hq.yml (tools/stems/expected-hq.sha256). */
private const val HQ_BYTES = 33_429_300L
private const val HQ_SHA = "94f35d9d1bb1a15829c0872bca71147c92cb03d0e4db8dcfc2e73b24d9f01257"

internal object StemModel {
    val fast = ModelPack("https://github.com/Tysnnn23/Ketvoice/releases/download/ketmix-stems-v1/", listOf(
        ModelPack.Part("spleeter-2stems-vocals.tflite", 19_682_028L, "883206076122f26c6c131cddc49a646be954c8ccb4bfa37b602a5b4e92b36989"),
        ModelPack.Part("spleeter-2stems-accompaniment.tflite", 19_682_036L, "93c45e02fe7befac4db502e11898d86322784e2ec5469b0a1111c4e0e3cf8576"),
    ), "stems/model")
    val hq = ModelPack("https://github.com/Tysnnn23/Ketvoice/releases/download/ketmix-stems-v2/", listOf(
        ModelPack.Part("uvr-mdx-net-voc-ft.tflite", HQ_BYTES, HQ_SHA),
    ), "stems/model-hq")

    /** The quick model (kept under the 5.10.0 names). */
    val parts: List<ModelPack.Part> get() = fast.parts
    val ready: Boolean get() = fast.ready
    /** One of the two models can separate. */
    val anyReady: Boolean get() = fast.ready || hq.ready

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        StemStore.init(context.filesDir)
        fast.init(context.filesDir)
        hq.init(context.filesDir)
    }

    /** Whether the phone is plugged in. */
    fun charging(): Boolean = runCatching {
        appContext?.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true
    }.getOrDefault(false)

    /** Which model separates now (setting `ketmixStemsQuality`: 0 automatic, 1 always the best, 2 always quick); null when none can. */
    fun quality(setting: Int, charging: Boolean): Int? = choose(setting, charging, fast.ready, hq.ready)

    internal fun choose(setting: Int, charging: Boolean, fastReady: Boolean, hqReady: Boolean): Int? = when {
        setting == 2 -> if (fastReady) StemMask.FAST else if (hqReady) StemMask.HIGH else null
        setting == 1 || charging || !fastReady -> if (hqReady) StemMask.HIGH else if (fastReady) StemMask.FAST else null
        else -> StemMask.FAST
    }
}
