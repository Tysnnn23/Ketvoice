package com.ketvoice.app.player

import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object AutoMixSettings {
    const val LOW = 0
    const val MEDIUM = 1
    const val STANDARD = 2
    const val HIGH = 3
    const val MIN_FADE_MS = 700L
    const val MAX_FADE_MS = 30_000L
    const val AUTO_MAX_MS = 12_000L
    const val MANUAL_FADE_MS = 1_500L

    fun performance(): Int = AppSettings.automixPerformance.value.coerceIn(LOW, HIGH)

    fun maxFadeMs(): Long {
        val requested = if (AppSettings.automixAutoFade.value) AUTO_MAX_MS
        else AppSettings.automixFadeSeconds.value * 1000L
        return requested.coerceIn(MIN_FADE_MS, MAX_FADE_MS)
    }

    fun automatic(): Boolean = AppSettings.automixAutoFade.value
}

data class AutoMixAnalysis(
    val trackId: String,
    val window: Int,
    val level: Int,
    val durationMs: Long,
    val windowStartMs: Long,
    val bpm: Float = 0f,
    val confidence: Float = 0f,
    val beatOffsetMs: Long = 0L,
    val rms: Float = 0f,
    val peak: Float = 0f,
    val vocalRisk: Float = 0f,
    val quietTailMs: Long = 0L,
    val sampledMs: Long = 0L,
    val structureStartMs: Long = 0L
) {
    val beatMs: Long get() = if (bpm > 0f) (60_000f / bpm).toLong() else 0L
}

enum class AutoMixCurve { EQUAL_POWER, LINEAR }

data class AutoMixPlan(
    val fadeMs: Long,
    val leadMs: Long,
    val incomingStartMs: Long,
    val incomingGain: Float,
    val curve: AutoMixCurve,
    val beatSynced: Boolean,
    val reason: String
)

object AutoMixPlanner {

    fun plan(
        level: Int,
        maxFadeMs: Long,
        automatic: Boolean,
        current: AutoMixAnalysis?,
        next: AutoMixAnalysis?,
        remainingMs: Long
    ): AutoMixPlan {
        val limit = maxFadeMs.coerceIn(AutoMixSettings.MIN_FADE_MS, AutoMixSettings.MAX_FADE_MS)
        val base = when (level) {
            AutoMixSettings.LOW -> 2_400L
            AutoMixSettings.MEDIUM -> 3_600L
            AutoMixSettings.STANDARD -> 4_600L
            else -> 5_200L
        }
        var reasons = mutableListOf<String>()
        var fade = if (automatic) base else min(limit, base * 2)
        var synced = false
        var incomingStart = 0L

        val bpmA = current?.bpm ?: 0f
        val bpmB = next?.bpm ?: 0f
        val confidence = min(current?.confidence ?: 0f, next?.confidence ?: 0f)
        val compatible = bpmA > 0f && bpmB > 0f && confidence >= 0.32f &&
            abs(bpmA - bpmB) / max(bpmA, bpmB) <= 0.045f

        if (automatic) {
            if (compatible && level >= AutoMixSettings.STANDARD) {
                fade += 900L
                synced = true
                reasons += "tempo"
            }
            val quiet = current?.quietTailMs ?: 0L
            if (quiet >= 1_500L) {
                fade += min(1_200L, quiet / 3)
                reasons += "outro"
            }
            if (level == AutoMixSettings.LOW) reasons += "performance"
        } else {
            synced = compatible && level >= AutoMixSettings.STANDARD
            if (synced) reasons += "tempo"
        }
        if ((next?.vocalRisk ?: 0f) >= 0.55f) {
            fade = (fade * 0.68f).toLong()
            reasons += "voix"
        }

        val room = (remainingMs - 250L).coerceAtLeast(AutoMixSettings.MIN_FADE_MS)
        fade = fade.coerceIn(AutoMixSettings.MIN_FADE_MS, limit).coerceAtMost(room)
        val structuralLead = if (automatic && current != null && current.structureStartMs > 0) {
            current.durationMs - current.structureStartMs
        } else 0L
        val lead = if (structuralLead in 1_000L..(fade + 2_500L)) {
            reasons += "structure"
            structuralLead
        } else fade
        fade = min(fade, lead)

        if (synced) {
            val beatA = current?.beatMs ?: 0L
            val beatB = next?.beatMs ?: 0L
            if (beatA > 0L && beatB > 0L && current != null && next != null) {
                val transitionAt = (current.durationMs - lead).coerceAtLeast(0L)
                val phase = Math.floorMod(transitionAt - current.windowStartMs - current.beatOffsetMs, beatA)
                val nextBeat = (beatA - phase) % beatA
                incomingStart = Math.floorMod(next.beatOffsetMs - nextBeat, beatB)
            }
        }

        val outgoingRms = current?.rms ?: 0f
        val incomingRms = next?.rms ?: 0f
        val gain = if (outgoingRms > 0.01f && incomingRms > 0.01f) {
            (outgoingRms / incomingRms).coerceIn(0.62f, 1f)
        } else 1f

        if (reasons.isEmpty()) reasons += "fondu"

        return AutoMixPlan(
            fadeMs = fade,
            leadMs = lead,
            incomingStartMs = incomingStart,
            incomingGain = gain,
            curve = AutoMixCurve.EQUAL_POWER,
            beatSynced = synced,
            reason = reasons.joinToString(" · ")
        )
    }

    fun fadeGain(progress: Float, curve: AutoMixCurve): Pair<Float, Float> {
        val t = progress.coerceIn(0f, 1f)
        return when (curve) {
            AutoMixCurve.LINEAR -> (1f - t) to t
            AutoMixCurve.EQUAL_POWER -> {
                val angle = t * (Math.PI.toFloat() / 2f)
                kotlin.math.cos(angle) to kotlin.math.sin(angle)
            }
        }
    }

    fun loudnessGain(progress: Float, match: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        val factor = match.coerceIn(0.5f, 1f)
        return factor + (1f - factor) * t
    }
}

object AutoMixCommands {
    const val MIX = "ketvoice.automix_mix"
    const val PREPARE = "ketvoice.automix_prepare"
    const val CANCEL = "ketvoice.automix_cancel"
    const val DISABLE = "ketvoice.automix_disable"
    const val EVENT = "ketvoice.automix_event"
    const val TOKEN = "token"
    const val SUCCESS = "success"
    const val REASON = "reason"
}

object AutoMix {
    data class Transition(
        val id: Long,
        val fromId: String,
        val toId: String,
        val fromTitle: String,
        val toTitle: String,
        val fromCover: String?,
        val toCover: String?,
        val fadeMs: Long,
        val startedAt: Long,
        val manual: Boolean,
        val strategy: String
    )

    val transition = MutableStateFlow<Transition?>(null)
    val preparing = MutableStateFlow(false)

    fun enabled(): Boolean = AppSettings.automix.value

    fun clear() {
        transition.value = null
        preparing.value = false
    }
}
