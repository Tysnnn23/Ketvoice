package com.ketvoice.app.automix

import com.ketvoice.app.data.UiTextDictionary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The status lines are assembled at run time: every piece must be found in every language. */
class AutomixStatusTextTest {
    private val root = JSONObject(File("src/main/assets/translations.json").readText())
    private fun dictionary(code: String): UiTextDictionary {
        val obj = root.getJSONObject(code)
        return UiTextDictionary(obj.keys().asSequence().associateWith { obj.getString(it) })
    }
    private val lines = listOf(
        "Automix actif", "Automix actif · le mix du titre suivant se prépare avant la fin de celui-ci",
        "Pas de mix · dernier titre de la file", "Pas de mix · sortie bit-perfect", "Pas de mix · titre répété", "Pas de mix · minuterie active",
        "Pas de mix · album joué dans l'ordre", "Pas de mix · titre suivant trop court", "Pas de mix · titre suivant indisponible",
        "Pas de mix · le titre suivant n'a pas pu être préparé", "Pas de mix · album sans blanc entre les titres",
        "Pas de mix · trop tard pour préparer le mix", "Pas de mix · le titre suivant n'a pas pu être lu", "Pas de mix · les deux titres ne s'enchaînent pas",
        "Préparation du titre suivant…", "Titre suivant indisponible · nouvel essai dans quelques secondes",
        "Mix prévu dans 42 s · calé sur le tempo", "Mix prévu dans 7 s · fondu enchaîné", "Mix prévu dans 0 s · enchaînement sans silence", "Mix en cours")

    @Test fun everyStatusLineIsTranslatedInEveryLanguage() {
        for (code in listOf("en", "hi", "ja", "zh", "ru", "it", "es", "de", "pt", "ko")) {
            val dictionary = dictionary(code)
            for (line in lines) {
                val translated = dictionary.translate(line)
                assertNotEquals("$code: $line", line, translated)
                assertFalse("$code: $line -> $translated", translated.contains("{0}"))
            }
        }
        assertEquals("Mix in 42 s · matched to the tempo", dictionary("en").translate("Mix prévu dans 42 s · calé sur le tempo"))
        assertEquals("No mix · title on repeat", dictionary("en").translate("Pas de mix · titre répété"))
    }
}
