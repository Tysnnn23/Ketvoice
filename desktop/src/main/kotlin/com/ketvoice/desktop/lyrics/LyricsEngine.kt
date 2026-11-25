package com.ketvoice.desktop.lyrics

import com.ketvoice.app.data.LyricsRank
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The PC's lyrics engine (Windows 2.0), plugged into the shared lookup ([LyricsRepo.hooks]): every provider and every
 * custom source that serves lyrics is asked at once, the richest timing is shown ([LyricsRank]: word, syllable, word by
 * AI, line, plain), a source the listener picks for a title in the lyrics' menu is remembered for that title, and
 * line-timed lyrics are shown word by word once the AI has timed them ([AiLyrics]).
 */
object LyricsEngine : LyricsRepo.Hooks {
    /** The answers heard for the title shown, for the source menu (« Pour ce titre »). */
    data class Answers(val trackId: String, val candidates: List<LyricsRank.Candidate>)

    private val _answers = MutableStateFlow<Answers?>(null)
    val answers: StateFlow<Answers?> = _answers

    /** The lyrics as the sources gave them for the title shown (before any AI timing): what the AI aligns. */
    data class Raw(val track: Track, val state: LyricsRepo.State)
    private val _raw = MutableStateFlow<Raw?>(null)
    val raw: StateFlow<Raw?> = _raw

    private const val CHOICES = "lyricsChoices"
    private const val MAX_CHOICES = 2_000
    private val choices = LinkedHashMap<String, String>()
    @Volatile private var loaded = false

    fun install() {
        LyricsRepo.hooks = this
    }

    override fun rank(lines: List<MusicApi.LyricLine>): Int = LyricsRank.level(lines)

    override fun pinned(trackId: String): String? = synchronized(choices) { ensure(); choices[trackId] }

    override fun answered(track: Track, source: String, label: String, lines: List<MusicApi.LyricLine>, order: Int) {
        if (lines.isEmpty()) return
        synchronized(_answers) {
            val now = _answers.value?.takeIf { it.trackId == track.id }?.candidates.orEmpty().filter { it.source != source }
            _answers.value = Answers(track.id, (now + LyricsRank.Candidate(source, label, lines, order)).sortedBy { it.order })
        }
    }

    override fun shown(track: Track, state: LyricsRepo.State): LyricsRepo.State {
        _raw.value = Raw(track, state)
        if (_answers.value?.trackId != track.id) _answers.value = Answers(track.id, emptyList())
        return withAi(track.id, state)
    }

    /** [state] with the AI's word timing when it has some for exactly these lines (and the listener kept it). */
    fun withAi(trackId: String, state: LyricsRepo.State): LyricsRepo.State {
        if (state.lines.isEmpty() || LyricsRank.level(state.lines) > LyricsRank.LINE) return state
        val ai = AiLyrics.lines(trackId, AiLyrics.basis(state.lines)) ?: return state
        return state.copy(lines = ai, isTimed = true, wordTimed = true, syllableTimed = false,
            source = listOf(state.source, AiLyrics.LABEL).filter { it.isNotBlank() }.joinToString(" · "))
    }

    /* ── The listener's choice per title ── */

    /** « Pour ce titre »: shows [source]'s lyrics for this title from now on (null: back to the best one). */
    fun choose(trackId: String, source: String?) {
        synchronized(choices) {
            ensure()
            choices.remove(trackId)
            if (source != null) choices[trackId] = source
            while (choices.size > MAX_CHOICES) choices.remove(choices.keys.first())
            LyricsPcSettings.putString(CHOICES, encode(choices))
        }
    }

    fun chosen(trackId: String): String? = pinned(trackId)

    private fun ensure() {
        if (loaded) return
        loaded = true
        choices.putAll(decode(LyricsPcSettings.string(CHOICES)))
    }

    internal fun encode(map: Map<String, String>): String = JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }.toString()

    internal fun decode(text: String?): Map<String, String> = runCatching {
        val o = JSONObject(text ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getString(it) }.filterValues { it.matches(Regex("[A-Za-z0-9:_.-]{1,80}")) }
    }.getOrDefault(emptyMap())

    /** Tests. */
    internal fun resetForTest() { synchronized(choices) { choices.clear(); loaded = true }; _answers.value = null; _raw.value = null }
}
