package com.ketvoice.desktop.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** The output devices of this PC as Java Sound lists them (the devices [LineSink] can open), by name. */
object OutputDevices {
    private val FORMAT = AudioFormat(FfmpegAudioEngine.RATE.toFloat(), 16, 2, true, false)

    /** The devices that can play Ketvoice's 48 kHz stereo, in the system's order; never throws. */
    fun list(): List<String> = runCatching {
        val info = DataLine.Info(SourceDataLine::class.java, FORMAT)
        names(AudioSystem.getMixerInfo().map { it.name to runCatching { AudioSystem.getMixer(it).isLineSupported(info) }.getOrDefault(false) })
    }.getOrDefault(emptyList())

    /**
     * The names to offer among [mixers] (name, can play): the ones that can, once each, without the aliases of the
     * system's default (Windows' « Primary Sound Driver », ALSA's « default »), which the default entry already is.
     */
    internal fun names(mixers: List<Pair<String, Boolean>>): List<String> = mixers
        .filter { (name, plays) -> plays && name.isNotBlank() && !alias(name) }
        .map { it.first.trim() }.distinct()

    private fun alias(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.startsWith("primary sound driver") || n == "default" || n.startsWith("default [") || n.startsWith("port ")
    }

    /** A line on the device called [name], or null when it is not there. */
    fun line(name: String, format: AudioFormat): SourceDataLine? =
        AudioSystem.getMixerInfo().firstOrNull { it.name.trim() == name }?.let { AudioSystem.getSourceDataLine(format, it) }
}

/**
 * « Sortie audio » on Windows (the phone's 8.4 output choice): the devices Ketvoice can play to, the listener's choice,
 * remembered by device name in the `desktop` preferences, and the device it plays to now. A chosen device that goes
 * away gives the music back to the system's default; the choice is kept, and the music goes back to it when it returns.
 */
object DesktopOutput {
    private const val KEY = "audioOutput"
    private const val POLL_MS = 2_500L

    private val _devices = MutableStateFlow<List<String>>(emptyList())
    val devices: StateFlow<List<String>> = _devices
    private val _chosen = MutableStateFlow<String?>(null)
    /** The device the listener picked, or null for the system's default. */
    val chosen: StateFlow<String?> = _chosen

    @Volatile private var engine: FfmpegAudioEngine? = null
    private var applied: Any? = Unset
    private var job: Job? = null
    private object Unset

    private val prefs by lazy { Context.app.getSharedPreferences("desktop", Context.MODE_PRIVATE) }

    /** Where the music goes: the chosen device while it is connected, else the system's default (null). */
    fun target(devices: List<String>, chosen: String?): String? = chosen?.takeIf { it in devices }

    /** The listener picks [name] (null: the system's default); the music moves there at once. */
    fun choose(name: String?) {
        _chosen.value = name
        runCatching { prefs.edit().apply { if (name == null) remove(KEY) else putString(KEY, name) }.apply() }
        apply()
    }

    /** Takes up the devices connected now ([list] in tests). */
    fun refresh(list: List<String> = OutputDevices.list()) {
        if (_devices.value != list) _devices.value = list
        apply()
    }

    @Synchronized private fun apply() {
        val t = target(_devices.value, _chosen.value)
        if (applied == t) return
        applied = t
        engine?.setOutput(t)
    }

    /** Plays [engine] on the remembered device and follows connections from then on. */
    fun attach(engine: FfmpegAudioEngine, scope: CoroutineScope) {
        job?.cancel()
        this.engine = engine
        applied = Unset
        _chosen.value = runCatching { prefs.getString(KEY, null) }.getOrNull()?.takeIf { it.isNotBlank() }
        job = scope.launch(Dispatchers.IO) { while (isActive) { refresh(); delay(POLL_MS) } }
    }

    /** Tests. */
    internal fun resetForTest() { job?.cancel(); job = null; engine = null; applied = Unset; _devices.value = emptyList(); _chosen.value = null }

    /** Tests: as [attach] without the polling, for an [engine] that may be null. */
    internal fun attachForTest(engine: FfmpegAudioEngine?) { this.engine = engine; applied = Unset }
}
