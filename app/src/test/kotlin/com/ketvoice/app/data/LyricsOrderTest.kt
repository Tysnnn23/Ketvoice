package com.ketvoice.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** « Ordre des sources de paroles » (8.4): the stored order, its checks, and which source wins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsOrderTest {
    private val default = MusicApi.lyricSources

    @Test fun theDefaultOrderIsTheAutomaticOneAndIsStoredEmpty() {
        assertEquals(default, LyricsOrder.parse(""))
        assertEquals(default, LyricsOrder.parse(null))
        assertEquals("", LyricsOrder.encode(default))
        assertFalse(LyricsOrder.custom(""))
    }

    @Test fun aPartialOrUnknownOrderIsMadeWhole() {
        val parsed = LyricsOrder.parse("lrclib, netease,lrclib,nope")
        assertEquals(listOf("lrclib", "netease") + default.filter { it != "lrclib" && it != "netease" }, parsed)
        assertTrue(LyricsOrder.custom("lrclib"))
        assertEquals(parsed.joinToString(","), LyricsOrder.encode(parsed))
    }

    @Test fun movingASourceKeepsTheOthersInOrder() {
        val moved = LyricsOrder.move(default, default.lastIndex, 0)
        assertEquals(default.last(), moved.first())
        assertEquals(default.dropLast(1), moved.drop(1))
        assertEquals(default, LyricsOrder.move(default, 0, 99))
    }

    @Test fun onlyKnownSourcesEachOnceAreAcceptedFromABackupOrTheSync() {
        assertTrue(LyricsOrder.valid(""))
        assertTrue(LyricsOrder.valid("lrclib,netease"))
        assertFalse(LyricsOrder.valid("lrclib,lrclib"))
        assertFalse(LyricsOrder.valid("lrclib,https://evil"))
        assertEquals("lrclib,netease", PersonalBackup.setting("lyricsOrder", "lrclib,netease"))
        assertNull(PersonalBackup.setting("lyricsOrder", "lrclib,x"))
        assertTrue("lyricsOrder" in com.ketvoice.app.sync.SettingsKind.keys)
    }

    @Test fun theFirstSourceInTheOrderThatHasLyricsWinsEvenWhenSlower() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val answers = mapOf("a" to (300L to "A"), "b" to (10L to ""), "c" to (5L to "C"))
        val won = LyricsOrder.firstInOrder(listOf("a", "b", "c"), { it.isNotEmpty() }) { src ->
            asked += src
            val (wait, text) = answers.getValue(src)
            delay(wait); text
        }
        assertEquals("a" to "A", won)
        // Every source was asked at once.
        assertEquals(setOf("a", "b", "c"), asked.toSet())
    }

    @Test fun aSourceWithoutLyricsOrThatFailsPassesToTheNext() = runBlocking {
        val won = LyricsOrder.firstInOrder(listOf("a", "b", "c"), { it.isNotEmpty() }) { src ->
            when (src) { "a" -> error("offline"); "b" -> ""; else -> "C" }
        }
        assertEquals("c" to "C", won)
        assertNull(LyricsOrder.firstInOrder(listOf("a", "b"), { it.isNotEmpty() }) { "" })
    }

    @Test fun theSourcesAfterTheWinnerAreStopped() = runBlocking {
        var lateFinished = false
        val won = LyricsOrder.firstInOrder(listOf("a", "b"), { it.isNotEmpty() }) { src ->
            if (src == "a") "A" else { delay(5_000); lateFinished = true; "B" }
        }
        assertEquals("a" to "A", won)
        assertFalse(lateFinished)
    }
}
