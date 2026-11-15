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
        "Ketmix actif", "Ketmix actif · le mix du titre suivant se prépare avant la fin de celui-ci",
        "Pas de mix · dernier titre de la file", "Pas de mix · sortie bit-perfect", "Pas de mix · titre répété", "Pas de mix · minuterie active",
        "Pas de mix · album joué dans l'ordre", "Pas de mix · titre suivant trop court", "Pas de mix · titre suivant indisponible",
        "Pas de mix · le titre suivant n'a pas pu être préparé", "Pas de mix · album sans blanc entre les titres",
        "Pas de mix · trop tard pour préparer le mix", "Pas de mix · le titre suivant n'a pas pu être lu", "Pas de mix · les deux titres ne s'enchaînent pas",
        "Préparation du titre suivant…", "Titre suivant indisponible · nouvel essai dans quelques secondes",
        "Mix prévu dans 42 s · calé sur le tempo", "Mix prévu dans 7 s · fondu enchaîné", "Mix prévu dans 0 s · enchaînement sans silence",
        "Mix prévu dans 12 s · sortie en écho", "Mix prévu dans 9 s · croisement de filtres", "Mix prévu dans 3 s · dissolution",
        "Mix prévu dans 1 s · enchaîné sur le temps", "Mix prévu dans 20 s · en boucle · calé sur le tempo", "Mix en cours · calé sur le tempo", "Mix en cours · sortie en écho", "Mix en cours")

    /** Every kind of transition has its own words, and what was measured follows them untouched. */
    @Test fun everyKindOfTransitionIsNamedAndItsMeasuresAreShown() {
        val labels = MixKind.values().filter { it != MixKind.None }.map { MixWords.label(MixPlan(it)) } + MixWords.label(MixPlan(MixKind.Trim, onBeat = true))
        assertEquals(labels.size, labels.toSet().size)
        for (label in labels) assertTrue(label, lines.any { it.endsWith(" · $label") })
        val plan = MixPlan(MixKind.Beatmatch, fromBpm = 123.6f, toBpm = 128f, fromKey = 12 + 9, toKey = 7)
        assertEquals("124 → 128 BPM · 8A → 9B", MixWords.measures(plan))
        assertEquals("One tempo unknown: no tempo shown", "8A → 9B", MixWords.measures(plan.copy(toBpm = 0f)))
        assertEquals("", MixWords.measures(MixPlan(MixKind.Filter)))
        assertEquals("124 → 128 BPM", MixWords.measures(plan.copy(toKey = MusicalKey.NONE)))
        // The measures are figures and codes: a translation leaves them as they are.
        val shown = dictionary("en").translate("Mix prévu dans 42 s · calé sur le tempo · " + MixWords.measures(plan))
        assertEquals("Mix in 42 s · matched to the tempo · 124 → 128 BPM · 8A → 9B", shown)
    }

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
