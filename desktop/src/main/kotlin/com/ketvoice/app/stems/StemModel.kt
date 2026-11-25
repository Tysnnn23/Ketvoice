package com.ketvoice.app.stems

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
import java.security.MessageDigest

/**
 * The separation models on the desktop (Phase 3): the very networks the phone runs, as the ONNX files
 * sherpa-onnx publishes (the sources the phone's LiteRT conversions start from), run by ONNX Runtime.
 * Each file is fetched from the `ketmix-stems-onnx-v1` prerelease (`publish-stem-models-onnx.yml`), then
 * from its source on Hugging Face (the same bytes), and kept only with its pinned size and SHA-256
 * (`tools/stems/expected-onnx.sha256`).
 */
internal class ModelPack(private val release: String, val parts: List<Part>, private val folder: String) {
    /** [source]: where the same bytes are published upstream, null when only the release has them (converted files). */
    class Part(val name: String, val bytes: Long, val sha256: String, val source: String?)

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

    /** Test seam: opens a URL (null when it answers with an error). */
    @Volatile internal var open: (String) -> java.io.InputStream? = ::get

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

    /** Waits for a download started by [download] (tests and the opt-in checks). */
    suspend fun join() { job?.join() }

    fun cancel() { job?.cancel(); job = null }

    fun delete() {
        cancel()
        _state.value = State.Missing
        scope.launch {
            StemEngine.close()
            dir?.listFiles()?.forEach { it.delete() }
        }
    }

    /** The release first, then the source; each attempt is checked whole before the file is kept. */
    private suspend fun fetch(part: Part, tmp: File, target: File, progress: (Long) -> Unit) {
        val free = tmp.parentFile?.usableSpace ?: 0L
        if (free in 1 until part.bytes + 50L * 1024 * 1024) throw IOException("storage")
        var last: Exception? = null
        for (url in listOfNotNull(release + part.name, part.source)) {
            try {
                val input = open(url) ?: throw IOException("HTTP error")
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                input.use { stream ->
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
                            val n = stream.read(buffer)
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
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (c: CancellationException) {
                tmp.delete(); throw c
            } catch (e: Exception) {
                last = e; tmp.delete()
            }
        }
        throw last ?: IOException("download")
    }

    private fun get(url: String): java.io.InputStream? {
        val response = okhttp3.OkHttpClient.Builder().followRedirects(true)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Ketvoice").build()).execute()
        if (!response.isSuccessful) { response.close(); return null }
        return response.body?.byteStream()
    }
}

private const val RELEASE = "https://github.com/Tysnnn23/Ketvoice/releases/download/ketmix-stems-onnx-v1/"
private const val SPLEETER = "https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems/resolve/main/"
private const val UVR = "https://huggingface.co/k2-fsa/sherpa-onnx-models/resolve/main/source-separation-models/UVR-MDX-NET-Voc_FT.onnx"

internal object StemModel {
    /** Deezer's Spleeter 2 stems (MIT): quick enough for every mix. */
    val fast = ModelPack(RELEASE, listOf(
        ModelPack.Part("spleeter-2stems-vocals.onnx", 39_318_336L, "bdc16ab6bf6117ddd4842c19e80e40e2be188fc555295064d424616b0224ac97", SPLEETER + "vocals.onnx"),
        ModelPack.Part("spleeter-2stems-accompaniment.onnx", 39_318_343L, "671ace17acd3720674a2bc14de32ac6292453dec20d9eb0ba4255d4ad8e3d8c0", SPLEETER + "accompaniment.onnx"),
    ), "stems/model-onnx")
    /** UVR-MDX-NET Voc FT (Ultimate Vocal Remover, MIT): cleaner, about ten times slower. */
    val hq = ModelPack(RELEASE, listOf(
        ModelPack.Part("uvr-mdx-net-voc-ft.onnx", 66_762_795L, "e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4", UVR),
    ), "stems/model-hq-onnx")

    val parts: List<ModelPack.Part> get() = fast.parts
    val ready: Boolean get() = fast.ready
    /** One of the two models can separate. */
    val anyReady: Boolean get() = fast.ready || hq.ready

    fun init(context: android.content.Context) {
        StemStore.init(context.filesDir)
        fast.init(context.filesDir)
        hq.init(context.filesDir)
    }

    /**
     * Whether the computer runs on mains power (the phone's « en charge »): a laptop on battery separates with
     * the quick model in the automatic setting. Desktops, and systems that do not say, count as plugged in.
     */
    /**
     * Whether the computer has a system battery (Windows 2.0, « Puissance de Ketmix »: a battery is what makes a laptop).
     * `BatteryFlag` 128 means « no system battery »; 255 (unknown) and other systems count as a desktop.
     */
    fun hasBattery(): Boolean = runCatching {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@runCatching false
        val status = PowerStatus()
        if (!Kernel.api.GetSystemPowerStatus(status)) false else batteryPresent(status.BatteryFlag.toInt() and 0xff)
    }.getOrDefault(false)

    internal fun batteryPresent(flag: Int): Boolean = flag != 128 && flag != 255

    fun charging(): Boolean = runCatching {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@runCatching true
        val status = PowerStatus()
        // ACLineStatus: 0 on battery, 1 on mains, 255 unknown.
        if (!Kernel.api.GetSystemPowerStatus(status)) true else status.ACLineStatus.toInt() != 0
    }.getOrDefault(true)

    @com.sun.jna.Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
    class PowerStatus : com.sun.jna.Structure() {
        @JvmField var ACLineStatus: Byte = 0
        @JvmField var BatteryFlag: Byte = 0
        @JvmField var BatteryLifePercent: Byte = 0
        @JvmField var SystemStatusFlag: Byte = 0
        @JvmField var BatteryLifeTime: Int = 0
        @JvmField var BatteryFullLifeTime: Int = 0
    }

    @Suppress("FunctionName")
    private interface Kernel : com.sun.jna.Library {
        fun GetSystemPowerStatus(status: PowerStatus): Boolean
        companion object { val api: Kernel by lazy { com.sun.jna.Native.load("kernel32", Kernel::class.java) } }
    }

    /** Which model separates now (setting `ketmixStemsQuality`: 0 automatic, 1 always the best, 2 always quick); null when none can. */
    fun quality(setting: Int, charging: Boolean): Int? = choose(setting, charging, fast.ready, hq.ready)

    internal fun choose(setting: Int, charging: Boolean, fastReady: Boolean, hqReady: Boolean): Int? = when {
        setting == 2 -> if (fastReady) StemMask.FAST else if (hqReady) StemMask.HIGH else null
        setting == 1 || charging || !fastReady -> if (hqReady) StemMask.HIGH else if (fastReady) StemMask.FAST else null
        else -> StemMask.FAST
    }
}
