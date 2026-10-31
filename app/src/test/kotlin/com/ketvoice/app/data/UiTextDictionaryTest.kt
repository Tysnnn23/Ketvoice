package com.ketvoice.app.data

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UiTextDictionaryTest {
    @Test fun templatesPreserveUserContentAndCanReorderArguments() {
        val dictionary = UiTextDictionary(mapOf("{0} titres de {1}" to "{1}: {0} songs"))
        assertEquals("Cœur $ & \\: 12 songs", dictionary.translate("12 titres de Cœur $ & \\"))
    }

    @Test fun composedStatusLabelsTranslateIndependently() {
        val dictionary = UiTextDictionary(mapOf("Téléchargé" to "Downloaded", "Sans perte" to "Lossless"))
        assertEquals("Downloaded · FLAC · Lossless", dictionary.translate("Téléchargé · FLAC · Sans perte"))
        assertEquals("Unknown song", dictionary.translate("Unknown song"))
    }

    @Test fun malformedTranslationsDoNotLoseArguments() {
        val dictionary = UiTextDictionary(mapOf("{0} titres" to "Songs"))
        assertEquals("12 titres", dictionary.translate("12 titres"))
    }

    @Test fun aUnitTemplateDoesNotSwallowAnUntranslatedCount() {
        val dictionary = UiTextDictionary(mapOf("{0} titre" to "{0} song", "{0} Mo" to "{0} MB"))
        assertEquals("1 song · 3,4 MB", dictionary.translate("1 titre · 3,4 Mo"))
    }

    @Test fun canvasLocalMusicAndAlbumDurationMessagesExistInEveryLanguage() {
        val root = JSONObject(File("src/main/assets/translations.json").readText(Charsets.UTF_8))
        val required = listOf("Voir le Canvas en plein écran", "Quitter le plein écran", "Correspondances musicales",
            "Dossier de musique locale", "Impossible de lire les playlists", "Revenir à tout l'appareil",
            "MUSIQUE LOCALE", "Choisissez un dossier au-dessus pour élargir la recherche.", "{0} h {1} min",
            "Exclure ce morceau du Canvas", "Réactiver le Canvas pour ce morceau", "Choisir un dossier de musique",
            "Le scan MediaStore remplace le dossier choisi", "{0} lignes · mots synchronisés", "{0} écoutes")
        AppLanguage.all.filter { it != AppLanguage.FRENCH }.forEach { language ->
            val table = root.getJSONObject(language.code)
            required.forEach { assertTrue("${language.code}: $it", table.has(it) && table.getString(it).isNotBlank()) }
            val dictionary = UiTextDictionary(table.keys().asSequence().associateWith { table.getString(it) })
            val duration = dictionary.translate("2 h 17 min")
            assertTrue(duration.contains("2")); assertTrue(duration.contains("17"))
        }
    }

    @Test fun everyLanguageHasTheSameNonemptyMessagesAndValidTemplates() {
        val file = File("src/main/assets/translations.json")
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val keys = root.getJSONObject("en").keys().asSequence().toSet()
        val placeholder = Regex("\\{\\d+}")
        assertTrue(keys.size >= 380)
        AppLanguage.all.filter { it != AppLanguage.FRENCH }.forEach { language ->
            val table = root.getJSONObject(language.code)
            val dictionary = UiTextDictionary(table.keys().asSequence().associateWith { table.getString(it) })
            assertNotEquals("Settings translation is missing in ${language.code}", "Réglages", dictionary.translate("Réglages"))
            assertEquals(language.code, keys, table.keys().asSequence().toSet())
            keys.forEach { key ->
                val value = table.getString(key)
                assertTrue("${language.code}: $key", value.isNotBlank())
                assertEquals("${language.code}: $key", placeholder.findAll(key).map { it.value }.toSet(),
                    placeholder.findAll(value).map { it.value }.toSet())
            }
        }
    }
}
