package com.ketvoice.desktop.audio

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/** ketvoice_wasapi.dll (native/wasapi), loaded with JNA; Windows only. */
@Suppress("FunctionName")
internal interface WasapiLibrary : Library {
    fun kv_wasapi_version(): Int
    fun kv_wasapi_open(device: WString?, rate: Int, channels: Int, bits: Int, bufferMs: Int): Int
    fun kv_wasapi_format(handle: Int, out: IntArray): Int
    fun kv_wasapi_write(handle: Int, samples: ByteArray, frames: Int): Int
    fun kv_wasapi_padding(handle: Int): Int
    fun kv_wasapi_reset(handle: Int)
    fun kv_wasapi_drain(handle: Int)
    fun kv_wasapi_close(handle: Int)
    fun kv_wasapi_supports(device: WString?, rate: Int, channels: Int, bits: Int): Int
}

/**
 * Extracts `/native/ketvoice_wasapi.dll` (built by Windows CI from native/wasapi) into the data folder and loads it.
 * Off Windows, or when the app was built without it (Linux tests, `run` without CI's DLL), [library] is null and
 * « Mode exclusif » says so: the shared output plays as before.
 */
object WasapiNative {
    const val RESOURCE = "/native/ketvoice_wasapi.dll"
    /** The functions the app calls (CI checks the DLL exports them). */
    val EXPORTS = listOf("kv_wasapi_version", "kv_wasapi_open", "kv_wasapi_format", "kv_wasapi_write", "kv_wasapi_padding",
        "kv_wasapi_reset", "kv_wasapi_drain", "kv_wasapi_close", "kv_wasapi_supports")
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Volatile var error: String? = null
        private set
    private var loaded: WasapiLibrary? = null
    private var tried = false

    @Synchronized internal fun library(): WasapiLibrary? {
        if (tried) return loaded
        tried = true
        if (!isWindows) { error = "not Windows"; return null }
        loaded = runCatching {
            val file = extract() ?: error("$RESOURCE is not in the app")
            Native.load(file.absolutePath, WasapiLibrary::class.java).also { check(it.kv_wasapi_version() >= 1) { "version" } }
        }.onFailure { error = it.toString() }.getOrNull()
        return loaded
    }

    val available: Boolean get() = library() != null

    /** A copy per content hash, so an update never overwrites a DLL another instance has open. */
    private fun extract(dir: File = File(android.content.Context.app.dataDir, "native")): File? {
        val bytes = WasapiNative::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() } ?: return null
        val crc = CRC32().apply { update(bytes) }.value
        val file = File(dir, "wasapi-%08x/ketvoice_wasapi.dll".format(crc))
        if (file.isFile && file.length() == bytes.size.toLong()) return file
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "ketvoice_wasapi.dll.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { tmp.delete(); if (!file.isFile) error("cannot write $file") }
        return file
    }

    /** `--smoke` on Windows: the DLL loads and answers; a PC without a sound card only skips the device check. */
    fun smoke(say: (String) -> Unit): Boolean {
        if (!isWindows) { say("wasapi skipped: not Windows"); return true }
        val lib = library()
        if (lib == null) { say("wasapi failed: ketvoice_wasapi.dll did not load ($error)"); return false }
        val supports = runCatching { lib.kv_wasapi_supports(null, 48_000, 2, 16) }.getOrElse { say("wasapi failed: $it"); return false }
        say("wasapi ok (dll version ${lib.kv_wasapi_version()}, default device " +
            (if (supports >= 0) "exclusive 48 kHz/16-bit: ${if (supports == 1) "yes" else "no"}" else "none (0x%08X)".format(supports)) + ")")
        return true
    }

    /** Why a HRESULT from the DLL refused exclusive mode, in the listener's words (French, through AppText). */
    fun reason(hr: Int): String = when (hr) {
        0x88890004.toInt() -> "Le périphérique a été débranché" // AUDCLNT_E_DEVICE_INVALIDATED
        0x8889000A.toInt() -> "Une autre application utilise le périphérique en mode exclusif" // AUDCLNT_E_DEVICE_IN_USE
        0x88890008.toInt(), 0x88890019.toInt() -> "Le périphérique n'accepte pas ce format en mode exclusif" // UNSUPPORTED_FORMAT, BUFFER_SIZE_ERROR
        0x88890010.toInt(), 0x80070005.toInt() -> "Le mode exclusif n'est pas autorisé pour ce périphérique" // EXCLUSIVE_MODE_NOT_ALLOWED, E_ACCESSDENIED
        0x80070490.toInt() -> "Périphérique introuvable" // ERROR_NOT_FOUND
        else -> "Le périphérique refuse le mode exclusif"
    }
}

/**
 * « Mode exclusif »'s output: the device opened alone, at the title's own rate and in a PCM format that holds its bits
 * (ketvoice_wasapi.dll). It takes 32-bit samples ([bytesPerSample] 4) and only shifts them into the device's format.
 * [lastError] keeps why the device refused, for the status line.
 */
class WasapiSink(private val bufferMs: Int = 120) : PcmSink {
    private var handle = 0
    private var channels = 2
    @Volatile private var wanted: String? = null
    @Volatile override var device: String? = null; private set
    @Volatile override var format: PcmSink.Format? = null; private set
    @Volatile var lastError: Int = 0; private set
    override val realtime = true
    override val bytesPerSample = 4

    override fun select(name: String?): Boolean { wanted = name; return true }

    override fun open(sampleRate: Int, channels: Int) = openFormat(sampleRate, channels, 16)

    override fun openFormat(sampleRate: Int, channels: Int, bits: Int) {
        val lib = WasapiNative.library() ?: throw IOException("ketvoice_wasapi.dll: ${WasapiNative.error}")
        close()
        val h = lib.kv_wasapi_open(wanted?.let(::WString), sampleRate, channels, bits, bufferMs)
        if (h <= 0) { lastError = h; throw IOException("exclusive: 0x%08X".format(h)) }
        lastError = 0
        handle = h; this.channels = channels; device = wanted
        val f = IntArray(3)
        lib.kv_wasapi_format(h, f)
        format = PcmSink.Format(f[0], f[2], exclusive = true, note = if (f[1] != f[2]) "${f[2]}/${f[1]}" else null)
    }

    override fun write(pcm: ByteArray, length: Int) {
        val lib = WasapiNative.library() ?: return
        val frames = length / (4 * channels)
        val data = if (length == pcm.size) pcm else pcm.copyOf(length)
        val hr = lib.kv_wasapi_write(handle, data, frames)
        if (hr != 0) { lastError = hr; throw IOException("exclusive write: 0x%08X".format(hr)) }
    }

    override fun queuedFrames(): Int = if (handle > 0) WasapiNative.library()?.kv_wasapi_padding(handle)?.coerceAtLeast(0) ?: 0 else 0
    override fun flush() { if (handle > 0) WasapiNative.library()?.kv_wasapi_reset(handle) }
    override fun drain() { if (handle > 0) WasapiNative.library()?.kv_wasapi_drain(handle) }

    override fun close() {
        if (handle > 0) WasapiNative.library()?.kv_wasapi_close(handle)
        handle = 0; device = null; format = null
    }
}

/**
 * The engine's output on Windows: the shared sound card ([shared], Java Sound) or, while [exclusiveWanted], the
 * exclusive one ([exclusive]); a device that refuses exclusive mode at open falls back to the shared output at the same
 * rate, and [refusal] says why.
 */
class SwitchingSink(private val shared: PcmSink, private val exclusive: PcmSink?) : PcmSink {
    @Volatile var exclusiveWanted = false
    @Volatile private var active: PcmSink = shared
    /** Why the last exclusive open fell back to the shared output (French text), or null. */
    @Volatile var refusal: String? = null; private set

    override val realtime: Boolean get() = active.realtime
    override val bytesPerSample: Int get() = active.bytesPerSample
    override val device: String? get() = active.device
    override val format: PcmSink.Format? get() = active.format

    override fun select(name: String?): Boolean { exclusive?.select(name); return shared.select(name) }

    override fun open(sampleRate: Int, channels: Int) { active = shared; shared.open(sampleRate, channels) }

    override fun openFormat(sampleRate: Int, channels: Int, bits: Int) {
        if (exclusiveWanted && exclusive != null) {
            try { exclusive.openFormat(sampleRate, channels, bits); active = exclusive; refusal = null; return }
            catch (e: Exception) { refusal = (exclusive as? WasapiSink)?.lastError?.takeIf { it != 0 }?.let(WasapiNative::reason) ?: e.message }
        }
        active = shared
        shared.open(sampleRate, channels)
    }

    override fun write(pcm: ByteArray, length: Int) = active.write(pcm, length)
    override fun queuedFrames(): Int = active.queuedFrames()
    override fun flush() = active.flush()
    override fun drain() = active.drain()
    override fun close() { runCatching { exclusive?.close() }; shared.close(); active = shared }
}
