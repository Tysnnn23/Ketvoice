package com.ketvoice.app.ui.player

import com.ketvoice.app.data.LyricsFormats
import com.ketvoice.app.data.MusicApi

/** Display-only separation. Original lyrics, provider timings and downloads stay untouched. */
internal object LyricsPresentation {
    data class Voices(val lead: MusicApi.LyricLine, val backs: List<MusicApi.LyricLine>)

    fun split(line: MusicApi.LyricLine): Voices {
        val ranges = mutableListOf<IntRange>()
        val stack = ArrayDeque<Int>()
        line.text.forEachIndexed { index, char ->
            when (char) {
                '(', '（' -> stack.addLast(index)
                ')', '）' -> if (stack.isNotEmpty()) {
                    val start = stack.removeLast()
                    if (stack.isEmpty()) ranges += start..index
                }
            }
        }
        if (ranges.isEmpty()) return Voices(line, emptyList())
        val native = if (line.words.joinToString("") { it.text } == line.text) line.words
            else LyricsFormats.align(line.text, line.words)
        val owners = IntArray(line.text.length) { -1 }
        var offset = 0
        native.forEachIndexed { index, word ->
            repeat(word.text.length.coerceAtMost(owners.size - offset)) { owners[offset++] = index }
        }
        val voice = IntArray(line.text.length) { -1 }
        ranges.forEachIndexed { index, range -> range.forEach { voice[it] = index } }

        fun project(wanted: Int): MusicApi.LyricLine {
            val text = StringBuilder()
            val projectedOwners = mutableListOf<Int>()
            var space = -1
            line.text.forEachIndexed { index, char ->
                if (voice[index] != wanted || (wanted >= 0 && char in "()（）")) return@forEachIndexed
                if (char.isWhitespace()) {
                    if (text.isNotEmpty()) space = index
                } else {
                    if (space >= 0) {
                        if (char !in ",.;:!?…，。！？；：") { text.append(' '); projectedOwners += owners[space] }
                        space = -1
                    }
                    text.append(char); projectedOwners += owners[index]
                }
            }
            val words = mutableListOf<MusicApi.LyricWord>()
            var start = 0
            while (start < text.length && native.isNotEmpty()) {
                val owner = projectedOwners[start]
                var end = start + 1
                while (end < text.length && projectedOwners[end] == owner) end++
                val piece = text.substring(start, end)
                if (piece.none { it.isLetterOrDigit() } && words.isNotEmpty()) words[words.lastIndex] = words.last().copy(text = words.last().text + piece)
                else if (owner >= 0) words += native[owner].copy(text = piece)
                else if (words.isNotEmpty()) words[words.lastIndex] = words.last().copy(text = words.last().text + text.substring(start, end))
                start = end
            }
            // Never invent word alignment if the provider did not supply an exact mapping.
            val aligned = words.takeIf { it.joinToString("") { word -> word.text } == text.toString() }.orEmpty()
            return line.copy(
                timeMs = if (wanted >= 0) aligned.firstOrNull()?.startMs ?: line.timeMs else line.timeMs,
                text = text.toString(), words = aligned,
                translations = if (wanted < 0) line.translations else emptyMap()
            )
        }
        return Voices(project(-1), ranges.indices.map(::project).filter { it.text.isNotBlank() })
    }

    fun backIsActive(line: MusicApi.LyricLine, now: Long): Boolean {
        if (line.words.isEmpty()) return true // line-only provider: no invented word timings
        val start = line.words.minOf { it.startMs }
        val end = line.words.maxOf { it.endMs }
        return now >= start && (end <= start || now < end)
    }

    /** Soft emphasis DURING a native long segment, never in an invented post-word gap. */
    fun sustain(now: Long, start: Long, end: Long, reduced: Boolean): Float {
        if (reduced || end - start < 850L || now !in start until end) return 0f
        val duration = (end - start).toFloat()
        val enter = ((now - start) / (duration * .32f).coerceIn(260f, 600f)).coerceIn(0f, 1f)
        val exit = ((end - now) / 220f).coerceIn(0f, 1f)
        val t = minOf(enter, exit)
        return t * t * (3f - 2f * t)
    }
}
