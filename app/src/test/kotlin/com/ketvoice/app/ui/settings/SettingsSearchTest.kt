package com.ketvoice.app.ui.settings

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** « Recherche dans les réglages » (8.4): what a query finds, in which language, and that the index follows the pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSearchTest {
    private val rows = listOf(
        SettingEntry("Égaliseur", "Traitement réel de la session audio", SettingsCategory.Playback),
        SettingEntry("Thème", "S'applique immédiatement à toute l'app", SettingsCategory.Appearance),
        SettingEntry("Mode économie auto", "Batterie à 20 % ou moins", SettingsCategory.Appearance),
        SettingEntry("Flou des paroles", "Les lignes éloignées s'estompent", SettingsCategory.Lyrics),
        SettingEntry("Garder la fenêtre éveillée", "Quest", SettingsCategory.Appearance, shown = { false }))

    @Test fun accentsAndCaseDoNotMatter() {
        assertEquals(listOf("Égaliseur"), SettingsSearch.search("egaliseur", rows, { it }).map { it.title })
        assertEquals(listOf("Égaliseur"), SettingsSearch.search("ÉGALI", rows, { it }).map { it.title })
        assertEquals(listOf("Mode économie auto"), SettingsSearch.search("economie", rows, { it }).map { it.title })
        assertEquals("theme", SettingsSearch.fold("Thème"))
        assertEquals("l'app", SettingsSearch.fold("l’App"))
    }

    @Test fun descriptionsAreSearchedAndEveryWordMustBeThere() {
        assertEquals(listOf("Égaliseur"), SettingsSearch.search("session audio", rows, { it }).map { it.title })
        assertEquals(listOf("Flou des paroles"), SettingsSearch.search("lignes estompent", rows, { it }).map { it.title })
        assertTrue(SettingsSearch.search("session paroles", rows, { it }).isEmpty())
        assertTrue(SettingsSearch.search("   ", rows, { it }).isEmpty())
    }

    @Test fun aTitleThatStartsWithTheQueryComesFirst() {
        val both = listOf(SettingEntry("Couleur d'accent", "Barre de lecture", SettingsCategory.Appearance),
            SettingEntry("Fond noir", "Même sous la couleur d'une pochette", SettingsCategory.Appearance),
            SettingEntry("Couleurs de la pochette", "Accents", SettingsCategory.Appearance))
        assertEquals(listOf("Couleur d'accent", "Couleurs de la pochette", "Fond noir"),
            SettingsSearch.search("couleur", both, { it }).map { it.title })
    }

    @Test fun rowsThisDeviceDoesNotShowAreNotFound() {
        assertTrue(SettingsSearch.search("fenetre", rows, { it }).isEmpty())
    }

    @Test fun theInterfaceLanguageIsWhatIsMatched() {
        val english = mapOf("Égaliseur" to "Equalizer", "Traitement réel de la session audio" to "Real processing of the audio session",
            "Thème" to "Theme")
        val translate: (String) -> String = { english[it] ?: it }
        assertEquals(listOf("Égaliseur"), SettingsSearch.search("equalizer", rows, translate).map { it.title })
        assertEquals(listOf("Thème"), SettingsSearch.search("THEME", rows, translate).map { it.title })
        // The French word no longer finds the English row.
        assertTrue(SettingsSearch.search("egaliseur", rows, translate).isEmpty())
    }

    @Test fun theRealIndexFindsRowsOfEveryPage() {
        val all = SettingsSearch.entries
        assertTrue(all.size > 90)
        assertEquals(SettingsCategory.Playback, SettingsSearch.search("egaliseur", all, { it }).first().category)
        assertEquals(SettingsCategory.Storage, SettingsSearch.search("air ket", all, { it }).first().category)
        assertTrue(SettingsSearch.search("Fluidite 120", all, { it }).any { it.category == SettingsCategory.Appearance })
        // Every page that has rows is reachable from the search.
        val pages = all.map { it.category }.toSet()
        listOf(SettingsCategory.Account, SettingsCategory.Appearance, SettingsCategory.Design, SettingsCategory.Playback, SettingsCategory.Lyrics,
            SettingsCategory.Canvas, SettingsCategory.Sources, SettingsCategory.Downloads, SettingsCategory.LocalMusic,
            SettingsCategory.Storage, SettingsCategory.About).forEach { assertTrue("$it", it in pages) }
    }

    /** Rows drawn only in a passing state (a refused permission, a progress) or under a group without a page. */
    private val notIndexed = setOf("Ouvrir le Studio", "Importer des lyrics", "Déconnecter la publication", "Gestionnaire de batterie du fabricant",
        "Reprise en moins d'une minute", "Notifications désactivées", "Notification des paroles", "Ajouter un widget",
        // Shown only once « Autre » is picked for the sung words.
        "Couleur personnalisée")

    @Test fun everyRowThePagesDrawIsIndexed() {
        val folder = listOf(File("src/main/kotlin/com/ketvoice/app/ui/settings"), File("app/src/main/kotlin/com/ketvoice/app/ui/settings")).first { it.isDirectory }
        val row = Regex("""\b(?:SRow|SToggle|SRowLike)\(\s*[A-Za-z0-9_.]+(?:\([^)]*\))?\s*,\s*"((?:[^"\\$]|\\.)+)"""")
        // « Personnalisation » (2026-11-22): its sliders, choices and font strips start with their title.
        val titled = Regex("""\b(?:PercentSlider|LabeledSegments|FontStrip)\(\s*"((?:[^"\\$]|\\.)+)"""")
        val drawn = folder.listFiles()!!.filter { it.name.endsWith(".kt") }.flatMap { file ->
            (row.findAll(file.readText()) + titled.findAll(file.readText())).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue(drawn.size > 90)
        val indexed = SettingsSearch.entries.map { it.title }.toSet()
        val missing = drawn - indexed - notIndexed
        assertTrue("rows missing from the search: $missing", missing.isEmpty())
        val gone = indexed - drawn - com.ketvoice.app.data.DesignChoices.all.map { it.title }.toSet() -
            com.ketvoice.app.widget.WidgetPinning.widgets.map { it.name }.toSet() - setOf("Compte YouTube Music")
        assertTrue("indexed rows no page draws: $gone", gone.isEmpty())
    }
}
