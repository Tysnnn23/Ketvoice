package com.ketvoice.app.automix

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 7.6 (ketmix76): the two decks of a transition are two players that the system mixer sums, and it clips past full
 * scale. Each deck publishes the peak level it is playing at (held, then let go slowly); while another deck of a
 * transition plays, both take the same small reduction whenever their two peaks together would pass [CEILING].
 * Linked this way it is one limiter on the sum: the balance between the two titles is kept, and a deck playing
 * alone is never touched.
 */
internal object MixBus {
    /** -1 dBFS: what the two together may reach, with room for the peaks between samples. */
    const val CEILING = .891f
    /** A deck not heard from for this long is no longer playing. */
    private const val FRESH_NS = 600_000_000L
    /** The held peak falls by this share per block of 64 frames (about 400 ms to half at 44.1 kHz). */
    private const val HOLD = .9975f
    /** How fast a reduction is given back, per block, once the peaks allow it. */
    private const val GIVE_BACK = .02f

    /** Below this share of full scale the other deck is not counted: a deck nearly alone is left untouched. */
    private const val AUDIBLE = .02f

    private class Slot { @Volatile var peak = 0f; @Volatile var atNs = 0L; @Volatile var pair: Any? = null }
    private val slots = ConcurrentHashMap<Deck, Slot>()

    /** The loudest other deck of a transition, as a share of full scale; 0 when this one plays alone. */
    private fun partner(self: Deck, pair: Any, now: Long): Float {
        var most = 0f
        for ((deck, slot) in slots) if (deck !== self && now - slot.atNs < FRESH_NS && slot.pair == pair) most = maxOf(most, slot.peak)
        return most
    }

    /** For tests: forgets every deck. */
    fun clear() = slots.clear()

    /** One deck's side of the bus, owned by its [DeckDsp] (one audio thread). */
    class Deck {
        private val slot = Slot()
        private var held = 0f
        /** The reduction applied at the end of the last block (1 = none). */
        var reduction = 1f
            private set

        /**
         * Writes [count] samples of [pending] (16-bit scale) to [output], reduced as the sum requires. [pair] names the
         * transition (both decks' automations carry the same, see [DeckAutomation.pair]); without one nothing is reduced.
         */
        fun write(pending: FloatArray, count: Int, output: ByteBuffer, pair: Any?) {
            var peak = 0f
            for (i in 0 until count) peak = maxOf(peak, abs(pending[i]))
            held = maxOf(peak / 32_768f, held * HOLD)
            val now = System.nanoTime()
            slot.peak = held; slot.atNs = now; slot.pair = pair
            if (pair != null && slots[this] == null) slots[this] = slot
            val other = if (pair == null) 0f else partner(this, pair, now)
            val target = if (other < AUDIBLE) 1f else minOf(1f, CEILING / (held + other))
            val from = reduction
            val to = if (target < from) target else from + (target - from) * GIVE_BACK
            val step = (to - from) / count.coerceAtLeast(1)
            var r = from
            for (i in 0 until count) { r += step; output.putShort((pending[i] * r).toInt().coerceIn(-32768, 32767).toShort()) }
            reduction = to
        }

        /** The deck's transition is over: it leaves the bus and plays untouched. */
        fun leave() { slots.remove(this); held = 0f; reduction = 1f }
    }
}
