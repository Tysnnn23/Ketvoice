package com.ketvoice.app.smartlib

import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test

class NaturalQueryTest {
    private fun keys(text: String, artists: List<String> = emptyList()) = NaturalQuery.parse(text, artists).filters.map { it.key }.toSet()

    @Test fun theOwnersExampleIsUnderstoodInFrench() {
        val query = NaturalQuery.parse("rap français calme des années 2010")
        assertEquals(setOf("g:Rap", "l:French", "m:Calm", "y:2010-2019"), query.filters.map { it.key }.toSet())
        assertEquals("", query.rest)
        assertTrue(query.natural)
        assertEquals(listOf("Rap", "Français", "Calme", "2010–2019"), query.filters.map { it.label })
    }

    @Test fun englishSpanishItalianGermanAndPortugueseAreUnderstood() {
        assertEquals(setOf("g:Rap", "l:French", "m:Calm", "y:2010-2019"), keys("chill french hip hop from the 2010s"))
        assertEquals(setOf("g:Latin", "m:Party", "y:1990-1999"), keys("reggaeton para fiesta de los años 90"))
        assertEquals(setOf("l:Italian", "m:Melancholy", "y:1980-1989"), keys("canzoni italiane malinconiche anni 80"))
        assertEquals(setOf("l:German", "g:Rock", "t:Fast", "y:1990-1999"), keys("schneller deutscher Rock der 90er"))
        assertEquals(setOf("l:Portuguese", "m:Happy"), keys("músicas brasileiras alegres"))
    }

    @Test fun russianJapaneseChineseKoreanAndHindiAreUnderstood() {
        assertEquals(setOf("l:Russian", "g:Rock", "y:2000-2009"), keys("русский рок 2000-х"))
        assertEquals(setOf("l:Japanese", "m:Calm", "y:1980-1989"), keys("80年代の落ち着いた日本の曲"))
        assertEquals(setOf("l:Chinese", "m:Melancholy", "y:2010-2019"), keys("2010年代伤感中文歌"))
        assertEquals(setOf("g:KPop", "l:Korean", "m:Calm", "y:2010-2019"), keys("2010년대 잔잔한 케이팝"))
        assertEquals(setOf("l:Hindi", "m:Melancholy"), keys("उदास हिंदी गाने"))
    }

    @Test fun yearsRangesAndDecadesInManyForms() {
        assertEquals(setOf("y:2015-2015"), keys("pop 2015") - "g:Pop")
        assertEquals(setOf("y:2012-2016"), keys("rap 2012-2016") - "g:Rap")
        assertEquals(setOf("y:1990-1999"), keys("90s") )
        assertEquals(setOf("y:2000-2009"), keys("années 2000"))
        assertNull(QueryYears.decadeStart("95"))
    }

    @Test fun artistsOfTheLibraryAreRecognisedBeforeWords() {
        val query = NaturalQuery.parse("pop smoke énergique", listOf("Pop Smoke", "Werenoi"))
        assertEquals(setOf("a:pop smoke", "m:Energy"), query.filters.map { it.key }.toSet())
        assertFalse("A bare artist name stays a normal search", NaturalQuery.parse("werenoi", listOf("Werenoi")).natural)
    }

    @Test fun unknownWordsRemainAndPlainSearchesFallBack() {
        val plain = NaturalQuery.parse("Bohemian Rhapsody")
        assertFalse(plain.natural)
        assertEquals("bohemian rhapsody", plain.rest)
        val mixed = NaturalQuery.parse("rap calme pétunias")
        assertEquals("petunias", mixed.rest)
        assertEquals("petunias", mixed.catalogueText())
        assertEquals("Rap Français", NaturalQuery.parse("rap français calme").catalogueText())
    }

    @Test fun removedChipsAreLeftOut() {
        val query = NaturalQuery.parse("rap français calme").without(setOf("m:Calm"))
        assertEquals(setOf("g:Rap", "l:French"), query.filters.map { it.key }.toSet())
    }

    @Test fun libraryFilteringKeepsOnlyWhatAnswers() {
        val quietRap = Track("1", "Bené", "Werenoi", "Pétunias", durationMs = 200_000)
        val loudRap = Track("2", "Chemin d'or", "Werenoi", "Pétunias", durationMs = 190_000)
        val pop = Track("3", "Sunny day", "Someone", "Summer", durationMs = 180_000)
        val unknown = Track("4", "Untitled", "Nobody", "", durationMs = 180_000)
        val facts = mapOf(
            "1" to TrackFacts(setOf(Genre.Rap), 2016, Lang.French, SoundSignals(84f, -15f, true), "bene petunias", "werenoi"),
            "2" to TrackFacts(setOf(Genre.Rap), 2016, Lang.French, SoundSignals(140f, -6f, false), "chemin d'or petunias", "werenoi"),
            "3" to TrackFacts(setOf(Genre.Pop), 2019, Lang.English, null, "sunny day summer", "someone"),
            "4" to TrackFacts(),
        )
        val query = NaturalQuery.parse("rap français calme des années 2010")
        val library = SmartFilter.apply(listOf(pop, loudRap, unknown, quietRap), query, strict = true) { facts.getValue(it.id) }
        assertEquals(listOf("1"), library.map { it.id })
        val catalogue = SmartFilter.apply(listOf(unknown, pop, quietRap), query, strict = false) { facts.getValue(it.id) }
        assertEquals("Known matches first, unknown titles kept, contradictions left out", listOf("1", "4"), catalogue.map { it.id })
    }

    @Test fun languageIsGuessedFromScriptLettersAndSmallWords() {
        assertEquals(Lang.French, LanguageGuess.of("Je t'aime encore"))
        assertEquals(Lang.English, LanguageGuess.of("Love me like you do"))
        assertEquals(Lang.Spanish, LanguageGuess.of("Bésame mucho, mi corazón"))
        assertEquals(Lang.Russian, LanguageGuess.of("Группа крови"))
        assertEquals(Lang.Japanese, LanguageGuess.of("夜に駆ける"))
        assertEquals(Lang.Korean, LanguageGuess.of("봄날"))
        assertNull(LanguageGuess.of("Bené"))
    }
}
