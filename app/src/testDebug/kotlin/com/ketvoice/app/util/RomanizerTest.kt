package com.ketvoice.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Known examples for each script; android.icu runs from Robolectric's Android runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RomanizerTest {
    private fun r(text: String, japanese: Boolean = false) = Romanizer.line(text, japanese).also { println("ROMAN $text -> $it") }

    @Test fun latinLinesGetNothing() {
        assertNull(r("Je t'aime, mon amour"))
        assertNull(r("Ça va très bien 123"))
        assertNull(r("♪ ... !"))
    }

    @Test fun koreanFollowsRevisedRomanization() {
        assertEquals("annyeonghaseyo", r("안녕하세요"))
        assertEquals("saranghae", r("사랑해"))
        assertEquals("gamsahamnida", r("감사합니다"))
        assertEquals("hangugeo", r("한국어"))
        assertEquals("seoul", r("서울"))
        assertEquals("silla", r("신라"))
        assertEquals("joa", r("좋아"))
        assertEquals("neol saranghae baby", r("널 사랑해 baby"))
    }

    @Test fun japaneseKanaIsReadAndKanjiLinesAreLeftAlone() {
        assertEquals("arigatou", r("ありがとう", true))
        assertEquals("sakura", r("さくら", true))
        assertEquals("karē", r("カレー", true))
        assertEquals("kimi to sakura", r("きみ と サクラ", true))
        // Kanji: ICU would give the Chinese reading, so the line gets nothing.
        assertNull(r("君の名は", true))
        // A whole song: kana anywhere makes Han-only lines Japanese too.
        assertEquals(listOf("sayounara", null), Romanizer.lines(listOf("さようなら", "東京")))
    }

    @Test fun chineseGetsPinyinWithToneMarks() {
        assertEquals("nǐ hǎo", r("你好"))
        assertEquals(listOf("wǒ ài nǐ"), Romanizer.lines(listOf("我爱你")))
    }

    @Test fun cyrillicGreekAndOthers() {
        assertEquals("privet", r("привет"))
        assertEquals("Moskva", r("Москва"))
        assertEquals("Ya tebya lyublyu", r("Я тебя люблю"))
        assertEquals("YA TEBYA LYUBLYU", r("Я ТЕБЯ ЛЮБЛЮ"))
        assertEquals("Kyyiv", r("Київ"))
        assertEquals("kalimera", r("καλημερα"))
        assertEquals("namaste", r("नमस्ते")?.let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "") })
        // Hebrew and Arabic are written without vowels: only the letters can be given (basics, no dictionary).
        assertEquals("şlwm", r("שלום"))
        for (line in listOf("שלום", "مرحبا", "สวัสดี")) {
            val roman = r(line)!!
            assert(roman.all { Romanizer.scriptOf(it).let { s -> s == Romanizer.Script.Latin || s == Romanizer.Script.Other } }) { roman }
        }
    }
}
