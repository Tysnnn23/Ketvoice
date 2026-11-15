package com.ketvoice.app.data

/**
 * An artist's biography in the language of the app. The catalogue gives it in whatever language
 * its source had (often English, sometimes French); it is translated by the same service as the
 * lyrics' translations and kept on the device, so that a biography is translated once.
 */
object ArtistBio {
    /** The code the translation service knows the app's language by. */
    fun target(language: AppLanguage): String = when (language) {
        AppLanguage.CHINESE -> "zh-CN"
        else -> language.code
    }

    /** The biography in [language], or null when it could not be translated now (the original is shown meanwhile). */
    suspend fun translated(text: String, language: AppLanguage = AppText.language()): String? {
        if (text.isBlank()) return text
        val out = MusicApi.translate(text, target(language))?.trim() ?: return null
        return out.takeIf { it.isNotBlank() }
    }

    /** True when the translation says something else than the original (not the same text sent back). */
    fun differs(original: String, translation: String?): Boolean =
        translation != null && normalize(translation) != normalize(original)

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}
