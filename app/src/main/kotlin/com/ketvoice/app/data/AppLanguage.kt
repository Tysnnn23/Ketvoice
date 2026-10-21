package com.ketvoice.app.data

/**
 * Langues proposées dans Réglages → Langue de l'application.
 *
 * Le français reste la langue de référence : les textes sources sont écrits
 * en français puis traduits à l'exécution par [AppText]. Les noms natifs sont
 * volontairement affichés dans leur propre écriture.
 */
enum class AppLanguage(
    val code: String,
    val label: String,
    val nativeName: String,
    val englishName: String
) {
    FRENCH("fr", "Français", "Français", "French"),
    ENGLISH("en", "English", "English", "English"),
    HINDI("hi", "हिन्दी", "हिन्दी", "Hindi"),
    JAPANESE("ja", "日本語", "日本語", "Japanese"),
    CHINESE("zh", "中文", "中文", "Chinese"),
    RUSSIAN("ru", "Русский", "Русский", "Russian"),
    ITALIAN("it", "Italiano", "Italiano", "Italian"),
    SPANISH("es", "Español", "Español", "Spanish"),
    GERMAN("de", "Deutsch", "Deutsch", "German"),
    PORTUGUESE("pt", "Português", "Português", "Portuguese"),
    KOREAN("ko", "한국어", "한국어", "Korean");

    companion object {
        val all: List<AppLanguage> = entries

        fun fromLabel(label: String?): AppLanguage {
            val value = label?.trim().orEmpty()
            return all.firstOrNull {
                it.label.equals(value, true) ||
                    it.nativeName.equals(value, true) ||
                    it.englishName.equals(value, true) ||
                    it.code.equals(value, true)
            } ?: FRENCH
        }
    }
}
