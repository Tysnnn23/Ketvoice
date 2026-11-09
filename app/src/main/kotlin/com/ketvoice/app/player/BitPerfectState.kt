@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** PCM actually handed to the sink (decoder output), never a container guess. */
data class PcmSpec(val sampleRate: Int, val encoding: Int, val channels: Int) {
    val bits: Int get() = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    val isValid: Boolean get() = sampleRate > 0 && channels in 1..8 && bits > 0

    fun describe(): String = buildString {
        append(sampleRate)
        append(" Hz · ")
        append(bits)
        append("-bit")
        if (encoding == C.ENCODING_PCM_FLOAT) append(" float")
        append(" · ")
        append(channels)
        append(" ch")
    }
}

enum class ExactAudioPhase { Standard, Pending, Unavailable, Active }

data class ExactAudioStatus(
    val phase: ExactAudioPhase = ExactAudioPhase.Standard,
    val reason: String = "Sortie standard",
    val source: PcmSpec? = null,
    val output: PcmSpec? = null,
    val device: String = ""
)

/**
 * A capability, a preference or a successful AudioTrack build is not proof.
 * "Active" requires the mixer contract to still be owned, the routed device to
 * match, the negotiated AudioTrack format to equal the decoded PCM, real bytes
 * written while playing, unity app gain and no app DSP in the path.
 */
internal fun exactAudioProof(
    requested: Boolean,
    mixerAccepted: Boolean,
    mixerStillOwned: Boolean,
    routedDeviceMatches: Boolean,
    playing: Boolean,
    writtenBytes: Long,
    source: PcmSpec?,
    actual: PcmSpec?,
    unityGain: Boolean,
    dspBypassed: Boolean
): Boolean =
    requested && mixerAccepted && mixerStillOwned && routedDeviceMatches && playing && writtenBytes > 0 &&
        source != null && source.isValid && actual == source && unityGain && dspBypassed

object BitPerfectState {
    private val _status = MutableStateFlow(ExactAudioStatus())
    val status: StateFlow<ExactAudioStatus> = _status

    internal fun report(value: ExactAudioStatus) {
        if (_status.value != value) _status.value = value
    }

    internal fun reset() = report(ExactAudioStatus())
}
