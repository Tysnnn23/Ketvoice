package com.ketvoice.app.player

/** Small stereo Schroeder room: damped parallel combs and all-pass diffusion.
 * No allocations in the sample loop. Each decoder owns its delay history. */
internal class ReverbEngine(sampleRate: Int, channels: Int) {
    private class Comb(size: Int) {
        private val delay = FloatArray(size)
        private var index = 0
        private var damped = 0f
        fun process(input: Float): Float {
            val output = delay[index]
            damped = output * .72f + damped * .28f
            delay[index] = input + damped * .74f
            index = (index + 1) % delay.size
            return output
        }
        fun clear() { delay.fill(0f); index = 0; damped = 0f }
    }
    private class Diffuser(size: Int) {
        private val delay = FloatArray(size)
        private var index = 0
        fun process(input: Float): Float {
            val past = delay[index]
            val output = past - input * .5f
            delay[index] = input + output * .5f
            index = (index + 1) % delay.size
            return output
        }
        fun clear() { delay.fill(0f); index = 0 }
    }
    private val combs = Array(channels) { channel ->
        val lengths = intArrayOf(1116, 1188, 1277, 1356)
        Array(lengths.size) { Comb(((lengths[it] + channel * 23) * sampleRate / 44100).coerceAtLeast(1)) }
    }
    private val diffusers = Array(channels) { channel ->
        val lengths = intArrayOf(225, 556)
        Array(lengths.size) { Diffuser(((lengths[it] + channel * 13) * sampleRate / 44100).coerceAtLeast(1)) }
    }
    private val wet = FloatArray(channels)
    private val smoothing = 1f / (sampleRate * .025f).coerceAtLeast(1f)
    fun sample(input: Float, channel: Int, amount: Float): Float {
        val target = if (amount.isFinite()) amount.coerceIn(0f, 1f) * .32f else 0f
        if (target == 0f && wet[channel] < .000001f) {
            if (wet[channel] != 0f) {
                combs[channel].forEach { it.clear() }
                diffusers[channel].forEach { it.clear() }
                wet[channel] = 0f
            }
            return input
        }
        wet[channel] += (target - wet[channel]) * smoothing
        var room = 0f
        for (comb in combs[channel]) room += comb.process(input * .16f)
        for (diffuser in diffusers[channel]) room = diffuser.process(room)
        // Unity dry path when off. Smoothed wet/dry avoids clicks when adjusting.
        return (input * (1f - wet[channel] * .35f) + room * wet[channel]).coerceIn(-1f, 1f)
    }
    fun clear() {
        combs.forEach { row -> row.forEach { it.clear() } }
        diffusers.forEach { row -> row.forEach { it.clear() } }
        wet.fill(0f)
    }
}

internal object ReverbControl {
    @Volatile var amount = 0f
}
