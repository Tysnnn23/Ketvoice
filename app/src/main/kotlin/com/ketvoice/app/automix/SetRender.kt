package com.ketvoice.app.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Stereo 16-bit sound of one title at [SetRender.RATE], from the position it was opened at. */
internal interface SetSource : AutoCloseable {
    /** Writes up to [frames] interleaved stereo frames to [out] from frame [at]; fewer only at the end of the title. */
    fun read(out: ShortArray, at: Int, frames: Int): Int
    override fun close() {}
}

/** Where a render goes: an encoder, a WAV file, or a test's array. */
internal interface SetSink {
    fun write(pcm: ShortArray, frames: Int)
    fun finish()
    fun abort() {}
}

/** One title of a set to render: its real length and how to hear it from a position (ms). */
internal class SetTitle(val durationMs: Long, val open: (fromMs: Long) -> SetSource)

/**
 * « Exporter le set » (7.0.0): the offline twin of Ketmix's two decks. Each title runs through the same
 * [LoopFx] and [DeckDsp] as on playback, with the same [DeckAutomation] built from the same [MixPlan]s, and the
 * two decks are summed as Android's mixer sums them. Titles play at their own speed: the export never stretches
 * tempo (its plans are made with tempo matching off, see [SetExport]).
 */
internal object SetRender {
    const val RATE = 44_100
    const val BLOCK = 2_048

    private fun mixes(plan: MixPlan?) = plan != null && plan.mixes

    /** Where title [i] starts (ms in it): where the plan before it brings it in. */
    fun entryMs(plans: List<MixPlan?>, i: Int): Long = if (i > 0 && mixes(plans[i - 1])) plans[i - 1]!!.inMs else 0L

    /** Where title [i] stops: the end of its blend into the next (its echo may ring past its end), else its end. */
    fun exitMs(titles: List<SetTitle>, plans: List<MixPlan?>, i: Int): Long {
        val plan = plans.getOrNull(i)
        val length = titles[i].durationMs
        if (!mixes(plan)) return length
        val end = plan!!.outMs + plan.overlapMs
        return if (plan.tail) min(end, length + TransitionPlanner.MAX_TAIL_MS) else min(end, length)
    }

    /** Where, in title [i], the next one comes in. */
    private fun handOverMs(titles: List<SetTitle>, plans: List<MixPlan?>, i: Int): Long =
        plans.getOrNull(i)?.takeIf { it.mixes }?.outMs ?: exitMs(titles, plans, i)

    /** How long the whole set lasts, in frames. */
    fun lengthFrames(titles: List<SetTitle>, plans: List<MixPlan?>): Long {
        var total = 0L
        for (i in titles.indices) {
            val until = if (i == titles.lastIndex) exitMs(titles, plans, i) else handOverMs(titles, plans, i)
            total += frames(max(0L, until - entryMs(plans, i)))
        }
        return total
    }

    fun frames(ms: Long): Long = (ms * RATE / 1000.0).roundToLong()

    /** One title on its deck. */
    private class Deck(title: SetTitle, val fromMs: Long, val untilMs: Long, var automation: DeckAutomation?,
                       val outgoing: DeckAutomation?, val switchMs: Long, val loop: BarLoop?) {
        val source = title.open(fromMs)
        private val dsp = DeckDsp(RATE, 2)
        private val loopFx = LoopFx(RATE, 2)
        private val pcm = ShortArray(BLOCK * 2)
        private val input = ByteBuffer.allocate(BLOCK * 4).order(ByteOrder.LITTLE_ENDIAN)
        private val output = ByteBuffer.allocate(BLOCK * 4).order(ByteOrder.LITTLE_ENDIAN)
        var played = 0L
            private set

        fun positionUs(): Long = fromMs * 1000L + played * 1_000_000L / RATE
        fun framesUntil(ms: Long): Long = max(0L, frames(ms - fromMs) - played)
        val done: Boolean get() = framesUntil(untilMs) == 0L

        /** Adds [count] frames of this deck to [mix]; past the title's end it plays silence (an echo rings on it). */
        fun render(count: Int, mix: IntArray) {
            if (outgoing != null && automation !== outgoing && positionUs() >= switchMs * 1000L) automation = outgoing
            val got = runCatching { source.read(pcm, 0, count) }.getOrDefault(0)
            java.util.Arrays.fill(pcm, got * 2, count * 2, 0)
            input.clear(); input.asShortBuffer().put(pcm, 0, count * 2); input.limit(count * 4)
            output.clear()
            val at = positionUs()
            val live = automation
            if (live == null) output.put(input)
            else {
                val sound = runCatching { loopFx.process(input, at, loop) }.getOrNull() ?: input
                runCatching { dsp.process(sound, output, at, live) }.onFailure { output.clear(); input.rewind(); output.put(input) }
            }
            output.flip()
            val shorts = output.asShortBuffer()
            for (k in 0 until min(count * 2, shorts.remaining())) mix[k] += shorts.get(k).toInt()
            played += count
        }

        fun close() = runCatching { source.close() }
    }

    private fun deck(titles: List<SetTitle>, plans: List<MixPlan?>, i: Int): Deck {
        val before = plans.getOrNull(i - 1)?.takeIf { i > 0 && it.mixes }
        val after = plans.getOrNull(i)?.takeIf { it.mixes }
        return Deck(titles[i], entryMs(plans, i), exitMs(titles, plans, i), before?.let { DeckAutomation.incoming(it) },
            after?.let { DeckAutomation.outgoing(it) }, after?.let { it.outMs - it.buildMs - SWITCH_LEAD_MS } ?: Long.MAX_VALUE,
            after?.barLoop())
    }

    /** The outgoing automation takes over this long before a blend (its curves hold their first values until then). */
    private const val SWITCH_LEAD_MS = 1_000L

    /**
     * Renders the set to [sink]: [plans]`[i]` joins title i to title i+1 (null or [MixKind.None]: one after the other).
     * [progress] hears the share done. False when [cancelled] stopped it (the sink is then aborted).
     */
    fun render(titles: List<SetTitle>, plans: List<MixPlan?>, sink: SetSink, cancelled: () -> Boolean = { false },
               progress: (Float) -> Unit = {}): Boolean {
        if (titles.isEmpty()) return false
        val total = max(1L, lengthFrames(titles, plans))
        val mix = IntArray(BLOCK * 2)
        val out = ShortArray(BLOCK * 2)
        var i = 0
        var a = deck(titles, plans, 0)
        var b: Deck? = null
        var written = 0L
        try {
            while (true) {
                if (cancelled()) { sink.abort(); return false }
                val plan = plans.getOrNull(i)?.takeIf { it.mixes && i < titles.lastIndex }
                if (b == null && plan != null && a.framesUntil(plan.outMs) == 0L) b = deck(titles, plans, i + 1)
                var count = min(BLOCK.toLong(), a.framesUntil(a.untilMs))
                if (b == null && plan != null) count = min(count, a.framesUntil(plan.outMs))
                if (count > 0) {
                    java.util.Arrays.fill(mix, 0)
                    a.render(count.toInt(), mix)
                    b?.render(count.toInt(), mix)
                    for (k in 0 until count.toInt() * 2) out[k] = mix[k].coerceIn(-32_768, 32_767).toShort()
                    sink.write(out, count.toInt())
                    written += count
                    progress(min(1f, written.toFloat() / total))
                }
                if (!a.done) continue
                a.close()
                if (i == titles.lastIndex) break
                a = b ?: deck(titles, plans, i + 1)
                b = null
                i++
            }
        } catch (failure: Exception) {
            sink.abort(); throw failure
        } finally {
            a.close(); b?.close()
        }
        sink.finish()
        return true
    }
}

/**
 * A decoder's sound (any rate, mono or stereo or more) as stereo at [to], read as it comes: cubic (Hermite)
 * interpolation between the decoded frames, which keeps the highs a linear one dulls.
 */
internal class StreamResampler(private val from: Int, private val to: Int = SetRender.RATE) {
    private var data = FloatArray(8_192)
    private var size = 0
    private var pos = 0.0
    private val step = from.toDouble() / to
    val rate: Int get() = from

    /** Adds [frames] frames of [channels]-channel 16-bit sound (the first two channels; mono is doubled). */
    fun push(pcm: ShortArray, frames: Int, channels: Int) {
        if (frames <= 0 || channels <= 0) return
        if ((size + frames) * 2 > data.size) data = data.copyOf(max(data.size * 2, (size + frames) * 2))
        for (f in 0 until frames) {
            val l = pcm[f * channels].toFloat()
            val r = if (channels > 1) pcm[f * channels + 1].toFloat() else l
            data[(size + f) * 2] = l; data[(size + f) * 2 + 1] = r
        }
        size += frames
    }

    private fun at(frame: Int, c: Int): Float = data[frame.coerceIn(0, max(0, size - 1)) * 2 + c]

    /** Writes up to [frames] stereo frames to [out] from frame [offset]; with [ended], the last frames are played out too. */
    fun pull(out: ShortArray, offset: Int, frames: Int, ended: Boolean): Int {
        var made = 0
        while (made < frames) {
            val i = floor(pos).toInt()
            if (if (ended) i >= size else i + 2 >= size) break
            val t = (pos - i).toFloat()
            for (c in 0..1) {
                val y0 = at(i - 1, c); val y1 = at(i, c); val y2 = at(i + 1, c); val y3 = at(i + 2, c)
                val v = y1 + .5f * t * (y2 - y0 + t * (2f * y0 - 5f * y1 + 4f * y2 - y3 + t * (3f * (y1 - y2) + y3 - y0)))
                out[(offset + made) * 2 + c] = v.coerceIn(-32_768f, 32_767f).toInt().toShort()
            }
            made++
            pos += step
        }
        compact()
        return made
    }

    private fun compact() {
        val drop = floor(pos).toInt() - 1
        if (drop < 4_096) return
        System.arraycopy(data, drop * 2, data, 0, (size - drop) * 2)
        size -= drop; pos -= drop
    }
}
