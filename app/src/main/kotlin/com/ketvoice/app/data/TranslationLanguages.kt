package com.ketvoice.app.data

object TranslationLanguages {
    private val languages = linkedMapOf(
        "fr" to "Français", "en" to "Anglais", "es" to "Espagnol", "de" to "Allemand",
        "it" to "Italien", "pt" to "Portugais", "nl" to "Néerlandais", "pl" to "Polonais",
        "ru" to "Russe", "uk" to "Ukrainien", "ar" to "Arabe", "tr" to "Turc",
        "ja" to "Japonais", "ko" to "Coréen", "zh-CN" to "Chinois simplifié",
        "hi" to "Hindi", "id" to "Indonésien", "vi" to "Vietnamien", "sv" to "Suédois", "el" to "Grec"
    )
    val codes = languages.keys.toList()
    fun code(value: String) = if (value in languages) value else languages.entries.firstOrNull { it.value == value }?.key ?: "fr"
    fun name(value: String) = languages[code(value)].orEmpty()
}
