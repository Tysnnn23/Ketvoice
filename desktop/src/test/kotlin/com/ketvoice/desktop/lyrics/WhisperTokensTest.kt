package com.ketvoice.desktop.lyrics

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class WhisperTokensTest {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** A small vocabulary: the bytes, then merges by rank. */
    private val tiny = WhisperTokens.parse(
        (32..126).map { "${b64(it.toChar().toString())} ${it - 32}" } + listOf(
            "${b64("lo")} 200", "${b64("low")} 201", "${b64(" l")} 202", "${b64(" low")} 203", "${b64("er")} 204", "${b64(" lower")} 205"))

    @Test fun mergesFollowTheirRanks() {
        assertEquals(listOf(203), tiny.encode(" low"))
        // " lower": " l" + "o" + "w" + "e" + "r" → " low" (203) + "er" (204); " lower" itself is a token.
        assertEquals(listOf(205), tiny.encode(" lower"))
        assertEquals(listOf(201, 204), tiny.encode("lower"))
    }

    @Test fun textIsSplitAsTiktokenSplitsIt() {
        // "'t" is its own piece; spaces stay at the front of words.
        assertEquals(listOf('d'.code - 32, 'o'.code - 32, 'n'.code - 32), tiny.encode("don"))
        assertEquals(tiny.encode("don") + tiny.encode("'t"), tiny.encode("don't"))
    }

    @Test fun theLanguagesAreWhispersOrder() {
        assertEquals(99, WhisperTokens.LANGUAGES.size)
        assertEquals(50259, WhisperTokens.languageToken("en"))
        assertEquals(50265, WhisperTokens.languageToken("fr"))
        assertEquals(50357, WhisperTokens.languageToken("su"))
    }

    /** The real vocabulary, when it is on this machine: the tokens of tiktoken (export_whisper.py --fixtures). */
    @Test fun sameTokensAsTiktoken() {
        val vocab = System.getenv("KETVOICE_ALIGN_DIR")?.let { File(it, "ketvoice-align-vocab.tiktoken") }?.takeIf { it.isFile } ?: return
        val t = WhisperTokens.load(vocab)
        val expected = mapOf(
            "Hello world" to listOf(15947, 1002),
            "Don't stop me now, I'm having such a good time!" to listOf(7450, 380, 1590, 385, 586, 11, 286, 478, 1419, 1270, 257, 665, 565, 0),
            "Je t'aime à la folie, ça ira" to listOf(16582, 256, 6, 24461, 1531, 635, 3339, 414, 11, 2788, 3418, 64),
            "Смотри на меня" to listOf(8068, 44443, 1470, 6885),
            "사랑해요 너를" to listOf(14506, 9143, 15788, 3214, 31567),
            "夜に駆ける" to listOf(30124, 4108, 165, 100, 228, 39278),
            "愛してる" to listOf(15157, 8822, 4895),
            "Γεια σου κόσμε" to listOf(138, 241, 41198, 43455, 4903, 41292, 9104),
            "حبيبي يا نور العين" to listOf(5016, 21292, 21292, 35186, 8717, 13063, 18863, 9957),
            "1999 rock'n'roll 🎸" to listOf(3405, 8494, 3727, 6, 77, 6, 3970, 19034, 116),
            "  spaced   out  " to listOf(220, 43766, 220, 220, 484, 220, 220))
        for ((text, tokens) in expected) assertEquals(tokens, t.encode(text), text)
    }
}
