package com.ketvoice.desktop.audio

import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * When « Mode exclusif » may really be bit-perfect (Windows 2.0). Exclusive output is only taken when nothing would
 * change the sound on its way: otherwise the shared output plays, and [Decision.reason] says why, in the order a
 * listener would fix it. Pure, for tests.
 */
object ExclusiveRules {
    data class Inputs(
        val wanted: Boolean,
        val windows: Boolean,
        val dllLoaded: Boolean,
        val volume: Float,
        val equalizer: Boolean,
        val headphoneEq: Boolean,
        val ketmix: Boolean,
        /** The voice level away from 100 % (« Niveau de la voix »). */
        val voiceLevel: Boolean,
        /** A transition or a change of speed playing right now. */
        val mixing: Boolean,
    )

    data class Decision(val exclusive: Boolean, val reason: String? = null)

    fun decide(i: Inputs): Decision = when {
        !i.wanted -> Decision(false)
        !i.windows -> Decision(false, "Le mode exclusif n'existe que sur Windows")
        !i.dllLoaded -> Decision(false, "Le composant audio exclusif est absent de cette installation")
        i.ketmix || i.mixing -> Decision(false, "Ketmix est activé")
        i.equalizer -> Decision(false, "L'égaliseur est activé")
        i.headphoneEq -> Decision(false, "L'égaliseur par casque est activé")
        i.voiceLevel -> Decision(false, "Le niveau de la voix n'est pas à 100 %")
        i.volume < 0.999f -> Decision(false, "Le volume n'est pas à 100 %")
        else -> Decision(true)
    }
}

/**
 * « Mode exclusif » at work: follows the setting and the effects ([ExclusiveRules]) twice a second and switches the
 * engine between its shared output (48 kHz) and the exclusive one (each title at its own rate and bit depth). [status]
 * is the line Settings shows: what plays now, or why the shared output plays.
 */
object ExclusiveOutput {
    data class Status(val wanted: Boolean = false, val exclusive: Boolean = false, val reason: String? = null, val format: PcmSink.Format? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private var job: Job? = null

    /** The engine's output for this PC: on Windows a [SwitchingSink] that can go exclusive, elsewhere the shared one. */
    fun sink(): PcmSink = if (WasapiNative.isWindows) SwitchingSink(LineSink(), WasapiSink()) else LineSink()

    fun attach(engine: FfmpegAudioEngine, scope: CoroutineScope, sink: PcmSink?) {
        job?.cancel()
        val switching = sink as? SwitchingSink
        job = scope.launch(Dispatchers.Default) {
            var applied = false
            while (isActive) {
                engine.trueGapless = PlaybackPrefs.trueGapless.value // « Lecture sans blanc », from the next title
                val decision = ExclusiveRules.decide(inputs(engine))
                if (decision.exclusive != applied) {
                    applied = decision.exclusive
                    switching?.exclusiveWanted = applied
                    engine.setNativeRate(applied)
                }
                val format = engine.outputFormat
                val refused = switching?.refusal?.takeIf { applied && format?.exclusive != true }
                _status.value = Status(PlaybackPrefs.exclusive.value, applied && format?.exclusive == true, refused ?: decision.reason, format)
                delay(500)
            }
        }
    }

    private fun inputs(engine: FfmpegAudioEngine) = ExclusiveRules.Inputs(
        wanted = PlaybackPrefs.exclusive.value,
        windows = WasapiNative.isWindows,
        dllLoaded = WasapiNative.isWindows && WasapiNative.available,
        volume = engine.volume,
        equalizer = !engine.mixer.equalizer.neutral,
        headphoneEq = !engine.mixer.headphone.neutral,
        ketmix = AppSettings.automix.value,
        voiceLevel = com.ketvoice.app.stems.VoiceLevel.level < 0.999f,
        mixing = engine.mixer.mix != null || engine.speed() != 1f,
    )
}
