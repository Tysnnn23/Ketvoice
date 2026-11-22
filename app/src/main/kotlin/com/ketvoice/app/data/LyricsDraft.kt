package com.ketvoice.app.data

/**
 * « Éditeur de paroles » (9.0): the lines being timed, as the editor holds them. Pure and immutable, so every step
 * can be undone and the model is tested on the host. Times are milliseconds into the title, -1 while not set.
 */
data class DraftWord(val text: String, val startMs: Long = -1L)

data class DraftLine(val text: String, val timeMs: Long = -1L, val words: List<DraftWord> = split(text)) {
    val wordsTimed: Boolean get() = words.isNotEmpty() && words.all { it.startMs >= 0 }

    companion object {
        /** The words of a line, each keeping the spaces that follow it (as enhanced LRC writes them). */
        fun split(text: String): List<DraftWord> = Regex("\\S+\\s*").findAll(text.trim()).map { DraftWord(it.value) }.toList()
    }
}

enum class TapMode { Line, Word }

data class LyricsDraft(
    val lines: List<DraftLine> = emptyList(),
    /** The line the next tap times. */
    val line: Int = 0,
    /** The word the next tap times, in [TapMode.Word]. */
    val word: Int = 0,
    val mode: TapMode = TapMode.Line,
    /** The steps before this one, most recent last (bounded to [HISTORY]). */
    private val history: List<LyricsDraft> = emptyList()
) {
    val canUndo: Boolean get() = history.isNotEmpty()
    val done: Boolean get() = line >= lines.size
    val timedLines: Int get() = lines.count { it.timeMs >= 0 }
    val hasWordTimes: Boolean get() = lines.any { l -> l.words.any { it.startMs >= 0 } }

    private fun step(next: LyricsDraft): LyricsDraft = next.copy(history = (history + copy(history = emptyList())).takeLast(HISTORY))

    /** Times what comes next at [positionMs]: the line (and its first word), or in word mode the word, then moves on. */
    fun tap(positionMs: Long): LyricsDraft {
        if (done) return this
        val at = positionMs.coerceAtLeast(0L)
        val current = lines[line]
        if (mode == TapMode.Line || current.words.isEmpty()) {
            // A line timed again moves its words with it; a new one has only its first word set.
            val shift = if (current.timeMs >= 0) at - current.timeMs else 0L
            val words = current.words.mapIndexed { i, w ->
                when { i == 0 -> w.copy(startMs = at); w.startMs >= 0 -> w.copy(startMs = (w.startMs + shift).coerceAtLeast(at)); else -> w }
            }
            return step(copy(lines = lines.replace(line, current.copy(timeMs = at, words = words)), line = line + 1, word = 0))
        }
        val w = word.coerceIn(0, current.words.lastIndex)
        val words = current.words.replace(w, current.words[w].copy(startMs = at))
        val timed = current.copy(timeMs = if (w == 0) at else current.timeMs.takeIf { it >= 0 } ?: at, words = words)
        val last = w >= current.words.lastIndex
        return step(copy(lines = lines.replace(line, timed), line = if (last) line + 1 else line, word = if (last) 0 else w + 1))
    }

    /** The line (and word) last timed, which a nudge moves: the one just before the cursor. */
    fun lastTimed(): Pair<Int, Int>? {
        if (mode == TapMode.Word) {
            val l = lines.getOrNull(line)
            if (l != null && word > 0) return line to word - 1
            val before = (line - 1).takeIf { it >= 0 } ?: return null
            return before to (lines[before].words.lastIndex).coerceAtLeast(0)
        }
        return (line - 1).takeIf { it >= 0 && lines[it].timeMs >= 0 }?.let { it to -1 }
    }

    /**
     * Moves the time last set by [deltaMs] (« ±50 ms »): a whole line, its words with it, in line mode; one word in word
     * mode (the line's time follows its first word).
     */
    fun nudge(deltaMs: Long): LyricsDraft {
        val (l, w) = lastTimed() ?: return this
        val target = lines[l]
        fun moved(t: Long) = if (t < 0) t else (t + deltaMs).coerceAtLeast(0L)
        val updated = if (w < 0 || mode == TapMode.Line) target.copy(timeMs = moved(target.timeMs), words = target.words.map { it.copy(startMs = moved(it.startMs)) })
        else {
            val words = target.words.replace(w, target.words[w].let { it.copy(startMs = moved(it.startMs)) })
            target.copy(words = words, timeMs = if (w == 0) words[0].startMs else target.timeMs)
        }
        if (updated == target) return this
        return step(copy(lines = lines.replace(l, updated)))
    }

    /** Puts the cursor on line [index] (its first word): the next tap times it again. */
    fun select(index: Int): LyricsDraft = if (index !in 0..lines.size) this else copy(line = index, word = 0)

    fun withMode(next: TapMode): LyricsDraft = if (next == mode) this else copy(mode = next, word = 0)

    /** Forgets the times of line [index]. */
    fun clear(index: Int): LyricsDraft {
        val l = lines.getOrNull(index) ?: return this
        return step(copy(lines = lines.replace(index, l.copy(timeMs = -1, words = l.words.map { it.copy(startMs = -1) }))))
    }

    fun undo(): LyricsDraft = history.lastOrNull()?.copy(history = history.dropLast(1)) ?: this

    /** New text for the draft: lines whose text is unchanged at the same place keep their times. */
    fun retext(text: String): LyricsDraft {
        val fresh = fromText(text).lines
        val kept = fresh.mapIndexed { i, l -> lines.getOrNull(i)?.takeIf { it.text == l.text } ?: l }
        return step(copy(lines = kept, line = kept.indexOfFirst { it.timeMs < 0 }.takeIf { it >= 0 } ?: kept.size, word = 0))
    }

    val text: String get() = lines.joinToString("\n") { it.text }

    /** The lines as the lyrics view shows them; a word ends where the next starts (the last one where the next line starts). */
    fun toLyricLines(): List<MusicApi.LyricLine> = lines.mapIndexedNotNull { i, l ->
        if (l.timeMs < 0) return@mapIndexedNotNull null
        val nextLine = lines.drop(i + 1).firstOrNull { it.timeMs >= 0 }?.timeMs ?: -1L
        val words = if (l.wordsTimed && l.words.size >= 2) l.words.mapIndexed { j, w ->
            val end = l.words.getOrNull(j + 1)?.startMs ?: nextLine.takeIf { it > w.startMs } ?: -1L
            MusicApi.LyricWord(w.startMs, w.text, end)
        } else emptyList()
        MusicApi.LyricLine(l.timeMs, l.text, words)
    }.sortedBy { it.timeMs }

    companion object {
        const val HISTORY = 200

        /** Pasted or typed text: one line per line, blank lines and section labels like « [Chorus] » left out. */
        fun fromText(text: String): LyricsDraft = LyricsDraft(text.replace("\r", "").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.matches(Regex("^\\[[^\\]]*]$")) }.map { DraftLine(it) })

        /** Lyrics already there (online, downloaded or the title's own file): their times are kept. */
        fun fromLines(source: List<MusicApi.LyricLine>): LyricsDraft {
            val lines = source.filter { it.text.isNotBlank() }.map { l ->
                val words = if (l.words.size >= 2) l.words.map { DraftWord(it.text, it.startMs) } else DraftLine.split(l.text).mapIndexed { i, w ->
                    if (i == 0 && l.timeMs >= 0) w.copy(startMs = l.timeMs) else w
                }
                DraftLine(l.text.trim(), l.timeMs, words)
            }
            return LyricsDraft(lines, line = lines.indexOfFirst { it.timeMs < 0 }.takeIf { it >= 0 } ?: lines.size)
        }

        private fun <T> List<T>.replace(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
    }
}

/** LRC and enhanced LRC (word times) written from a draft, and read back through [CustomLyrics.parse]. */
object LyricsExport {
    /** « [mm:ss.cc] » (centiseconds, as LRC writes them; 100 minutes and more keep counting minutes). */
    fun stamp(ms: Long): String {
        val cs = (ms.coerceAtLeast(0L) + 5) / 10
        return "%02d:%02d.%02d".format(java.util.Locale.ROOT, cs / 6000, (cs / 100) % 60, cs % 100)
    }

    private fun header(title: String, artist: String, album: String): String = buildString {
        fun tag(name: String, value: String) { if (value.isNotBlank()) append("[$name:${value.replace(Regex("[\\[\\]\\n\\r]"), " ").trim()}]\n") }
        tag("ti", title); tag("ar", artist); tag("al", album); tag("re", "Ketvoice")
    }

    /** Standard LRC: one time per timed line (lines without a time are left out). */
    fun lrc(draft: LyricsDraft, title: String = "", artist: String = "", album: String = ""): String = buildString {
        append(header(title, artist, album))
        draft.toLyricLines().forEach { append("[${stamp(it.timeMs)}]${it.text}\n") }
    }

    /**
     * Enhanced LRC: each fully timed line also carries « <mm:ss.cc> » before each word and one after its last word, where
     * it ends (the next line, or a second after its last word). Lines without word times stay plain LRC lines.
     */
    fun enhanced(draft: LyricsDraft, title: String = "", artist: String = "", album: String = ""): String = buildString {
        append(header(title, artist, album))
        val timed = draft.lines.filter { it.timeMs >= 0 }.sortedBy { it.timeMs }
        timed.forEachIndexed { i, l ->
            append("[${stamp(l.timeMs)}]")
            if (l.wordsTimed) {
                l.words.forEach { append("<${stamp(it.startMs)}>${it.text}") }
                val lastWord = l.words.last().startMs
                val end = timed.getOrNull(i + 1)?.timeMs?.takeIf { it > lastWord } ?: (lastWord + 1_000)
                append("<${stamp(end)}>")
            } else append(l.text)
            append('\n')
        }
    }

    /** The draft read back from an LRC or enhanced LRC text (or any format [CustomLyrics] reads). */
    fun import(text: String): LyricsDraft = LyricsDraft.fromLines(CustomLyrics.parse(text).second)
}
