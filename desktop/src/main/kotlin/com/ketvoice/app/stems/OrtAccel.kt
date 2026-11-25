package com.ketvoice.app.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import ai.onnxruntime.OrtHardwareDevice
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.FloatBuffer
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * Where Ketmix's networks run on Windows (2.0). What works from Java with ONNX Runtime 1.32 (see desktop/README.md,
 * « Accélération GPU »):
 *  - CUDA needs the `onnxruntime_gpu` JNI build plus CUDA 12 and cuDNN 9 installed by the listener: not used.
 *  - DirectML: the Maven JNI is built without it (`addDirectML` throws) and the last DirectML build of ONNX Runtime is
 *    1.24.4, whose C API is older than the 1.32 JNI asks for: not used.
 *  - The WebGPU plugin execution provider (`Microsoft.ML.OnnxRuntime.EP.WebGpu`, MIT, Direct3D 12 through Dawn on any
 *    DX12 card): registered at run time on the stock 1.32 runtime with `registerExecutionProviderLibrary`, its GPU
 *    devices added with `addExecutionProvider`. This is the path used. Its 32 MB of DLLs are downloaded on request
 *    from nuget.org (pinned package digest) only when « Maximum » meets a real graphics card; the MSI does not grow.
 * Before it is trusted, a tiny network ([PROBE]) runs on the card and on the processor and must agree; a check that
 * crashed the process is remembered and never retried. Everything falls back to the processor with all its cores.
 */
internal object OrtAccel {
    private const val VERSION = "0.6.0"
    private const val NUPKG = "microsoft.ml.onnxruntime.ep.webgpu.$VERSION.nupkg"
    private const val PROVIDER = "onnxruntime_providers_webgpu.dll"
    private val NATIVE = listOf(PROVIDER, "dxcompiler.dll")
    const val PROBE = "/ketmix/gpu-probe.onnx"
    private const val CHECK_KEY = "gpuCheck"

    /** The NuGet package, as nuget.org serves it (no copy on our releases). */
    val runtime = ModelPack("https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntime.ep.webgpu/$VERSION/",
        listOf(ModelPack.Part(NUPKG, 55_622_619L, "ec1123d85edff3dd4aa6ed40adcf55df6d69c60319f4430732d930e3db5f83e7", null)),
        "ketmix/pc/gpu")

    enum class Status { Cpu, Checking, Gpu, Failed }
    private val _status = MutableStateFlow(Status.Cpu)
    val status: StateFlow<Status> = _status
    /** The card the models run on, for Settings. */
    @Volatile var deviceName: String? = null
        private set
    @Volatile private var devices: List<OrtEpDevice>? = null
    @Volatile private var registered = false

    // Seams: the stored check (KetmixPcSettings in the app) and whether the listener allows the card.
    @Volatile internal var readCheck: () -> String? = { null }
    @Volatile internal var writeCheck: (String?) -> Unit = {}
    @Volatile internal var allowed: () -> Boolean = { false }

    /** The threads a network gets on the processor: every core while the listener waits, half of them in the background. */
    fun threads(background: Boolean): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return if (background) (cores / 2).coerceAtLeast(1) else cores.coerceAtLeast(1)
    }

    /** Session options for a network: the card when it passed its check and is allowed, the processor otherwise. */
    fun options(threads: Int, gpu: Boolean = true): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        val cards = if (gpu && allowed() && _status.value == Status.Gpu) devices else null
        if (!cards.isNullOrEmpty()) runCatching { addExecutionProvider(cards, emptyMap()) }
            .onFailure { android.util.Log.w("KetvoiceGpu", "GPU not added: $it") }
    }

    /** A session on the card when possible; a session that fails to build there is built again on the processor. */
    fun session(file: File, threads: Int, gpu: Boolean = true): OrtSession {
        val env = OrtEnvironment.getEnvironment()
        if (gpu && _status.value == Status.Gpu && allowed()) {
            runCatching { return env.createSession(file.path, options(threads, true)) }
                .onFailure { android.util.Log.w("KetvoiceGpu", "GPU session failed, processor instead: $it") }
        }
        return env.createSession(file.path, options(threads, false))
    }

    /**
     * Installs the runtime when needed (blocking download), then checks the card once. Safe to call at every start:
     * a passed or failed check is remembered; a check the process never finished (a crash) counts as failed.
     */
    suspend fun prepare(filesDir: File, download: Boolean) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        runtime.init(filesDir)
        val stored = readCheck()
        if (stored == "running" || stored == "failed") { if (stored == "running") writeCheck("failed"); _status.value = Status.Failed; return }
        if (!runtime.ready) { if (!download) return; runtime.download(); runtime.join(); if (!runtime.ready) return }
        val dir = runtime.file(runtime.parts[0])?.parentFile ?: return
        val provider = extract(dir) ?: return
        _status.value = Status.Checking
        writeCheck("running")
        val ok = runCatching { register(provider) && verify() }.onFailure { android.util.Log.w("KetvoiceGpu", "GPU check: $it") }.getOrDefault(false)
        writeCheck(if (ok) "ok" else "failed")
        _status.value = if (ok) Status.Gpu else Status.Failed
    }

    /** The two DLLs of the package, next to it (checked package, so their bytes are the pinned ones). */
    internal fun extract(dir: File): File? = runCatching {
        val package_ = File(dir, NUPKG)
        ZipFile(package_).use { zip ->
            for (name in NATIVE) {
                val target = File(dir, name)
                val entry = zip.getEntry("runtimes/win-x64/native/$name") ?: return null
                if (target.length() == entry.size) continue
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        File(dir, PROVIDER)
    }.getOrNull()

    private fun register(provider: File): Boolean {
        val env = OrtEnvironment.getEnvironment()
        if (!registered) { env.registerExecutionProviderLibrary("ketvoice_webgpu", provider.path); registered = true }
        val found = gpuDevices(env.epDevices)
        devices = found
        deviceName = found.firstOrNull()?.let { it.device.metadata["Description"] ?: it.device.vendor }
        return found.isNotEmpty()
    }

    /** The WebGPU provider's graphics cards (not its software adapters). */
    internal fun gpuDevices(all: List<OrtEpDevice>): List<OrtEpDevice> =
        all.filter { it.epName.contains("WebGpu", ignoreCase = true) && it.device.type == OrtHardwareDevice.OrtHardwareDeviceType.GPU }.take(1)

    /** The probe network on the card and on the processor: they must agree. */
    private fun verify(): Boolean {
        val bytes = OrtAccel::class.java.getResourceAsStream(PROBE)?.use { it.readBytes() } ?: return false
        val input = FloatArray(4 * 32) { i -> kotlin.math.sin(i * .37f) }
        val cpu = runProbe(bytes, input, gpu = false)
        val card = runProbe(bytes, input, gpu = true)
        return agree(cpu, card)
    }

    internal fun agree(a: FloatArray, b: FloatArray, tolerance: Float = 2e-3f): Boolean =
        a.size == b.size && a.isNotEmpty() && a.indices.all { abs(a[it] - b[it]) <= tolerance * (1f + abs(a[it])) }

    private fun runProbe(model: ByteArray, input: FloatArray, gpu: Boolean): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { if (gpu) addExecutionProvider(devices.orEmpty(), emptyMap()) }
        env.createSession(model, options).use { session ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, 32)).use { tensor ->
                session.run(mapOf("x" to tensor)).use { result ->
                    val out = (result.get(0) as OnnxTensor).floatBuffer
                    return FloatArray(out.remaining()).also { out.get(it) }
                }
            }
        }
    }

    /** Settings: forget the check and the runtime (the next « Maximum » start fetches and checks it again). */
    fun reset() { writeCheck(null); _status.value = Status.Cpu; runtime.delete() }

    /** Test seam. */
    internal fun setStatusForTest(s: Status) { _status.value = s }
}
