package com.ketvoice.app.player

/**
 * Stereo Freeverb-style room: eight damped parallel combs per channel, four
 * all-pass diffusers, a short pre-delay and a DC blocker. Room size and damping
 * are adjustable; every decoder owns its own delay history.
 */
internal class ReverbEngine(private val sampleRate: Int, channels: Int) {

    private class Comb(size: Int) {
        private val delay = FloatArray(size)
        private var index = 0
        private var filter = 0f
        fun process(input: Float, feedback: Float, damping: Float): Float {
            val output = delay[index]
            filter = output * (1f - damping) + filter * damping
            delay[index] = input + filter * feedback
            index = (index + 1) % delay.size
            return output
        }
        fun clear() { delay.fill(0f); index = 0; filter = 0f }
    }

    private class Allpass(size: Int) {
        private val delay = FloatArray(size)
        private var index = 0
        fun process(input: Float): Float {
            val buffered = delay[index]
            val output = buffered - input
            delay[index] = input + buffered * .5f
            index = (index + 1) % delay.size
            return output
        }
        fun clear() { delay.fill(0f); index = 0 }
    }

    private fun scaled(samples: Int, channel: Int): Int {
        val spread = channel * 23
        return ((samples + spread).toLong() * sampleRate / 44100L).toInt().coerceAtLeast(1)
    }

    private val combs = Array(channels) { channel ->
        intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
            .map { Comb(scaled(it, channel)) }.toTypedArray()
    }
    private val diffusers = Array(channels) { channel ->
        intArrayOf(556, 441, 341, 225)
            .map { Allpass(scaled(it, channel)) }.toTypedArray()
    }
    private val preDelay = Array(channels) { FloatArray((sampleRate * .018f).toInt().coerceAtLeast(1)) }
    private val preIndex = IntArray(channels)
    private val wet = FloatArray(channels)
    private val dcIn = FloatArray(channels)
    private val dcOut = FloatArray(channels)
    private val smoothing = 1f / (sampleRate * .03f).coerceAtLeast(1f)

    fun sample(input: Float, channel: Int, amount: Float, size: Float, damping: Float): Float {
        val target = if (amount.isFinite()) amount.coerceIn(0f, 1f) * .85f else 0f
        if (target == 0f && wet[channel] < .000001f) {
            if (wet[channel] != 0f) clearChannel(channel)
            return input
        }
        wet[channel] += (target - wet[channel]) * smoothing

        val feedback = .72f + size.coerceIn(0f, 1f) * .17f
        val damp = (.05f + damping.coerceIn(0f, 1f) * .55f)
        val delayed = preDelay[channel]
        val pre = delayed[preIndex[channel]]
        delayed[preIndex[channel]] = input
        preIndex[channel] = (preIndex[channel] + 1) % delayed.size

        var room = 0f
        for (comb in combs[channel]) room += comb.process(pre * .025f, feedback, damp)
        for (diffuser in diffusers[channel]) room = diffuser.process(room)
        // DC blocker: a long feedback room otherwise builds sub-bass rumble.
        val hp = room - dcIn[channel] + .995f * dcOut[channel]
        dcIn[channel] = room
        dcOut[channel] = hp
        return (input * (1f - wet[channel] * .45f) + hp * wet[channel]).coerceIn(-1f, 1f)
    }

    private fun clearChannel(channel: Int) {
        combs[channel].forEach { it.clear() }
        diffusers[channel].forEach { it.clear() }
        preDelay[channel].fill(0f)
        preIndex[channel] = 0
        dcIn[channel] = 0f
        dcOut[channel] = 0f
        wet[channel] = 0f
    }

    fun clear() {
        for (channel in combs.indices) clearChannel(channel)
    }
}

internal object ReverbControl {
    @Volatile var amount = 0f
    @Volatile var size = .5f
    @Volatile var damping = .35f
    @Volatile private var lastPcmNanos = 0L
    fun notePcm() { lastPcmNanos = System.nanoTime() }
    fun processingAudio(): Boolean = lastPcmNanos != 0L && System.nanoTime() - lastPcmNanos < 1_000_000_000L
}
