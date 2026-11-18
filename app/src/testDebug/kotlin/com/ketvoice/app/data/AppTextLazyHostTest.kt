package com.ketvoice.app.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 6.7: the translations are no longer read at every start. French reads nothing; another language reads its own
 * dictionary the first time a text is asked, with the same entries the whole-file reading of 6.6 gave.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppTextLazyHostTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var language = ""

    @Before fun fresh() {
        language = AppSettings.language.value
        AppText.clearForTests()
        AppText.init(app)
    }

    @After fun reset() {
        AppSettings.language.value = language
        AppText.clearForTests()
    }

    @Test fun frenchReadsNoDictionary() {
        AppSettings.language.value = AppLanguage.FRENCH.label
        assertEquals("Réverbération", AppText.t("Réverbération"))
        assertEquals("the source language needs no file", emptySet<String>(), AppText.loaded())
    }

    @Test fun anotherLanguageReadsOnlyItsOwnDictionary() {
        AppSettings.language.value = AppLanguage.ENGLISH.label
        assertEquals("Reverberation", AppText.t("Réverbération"))
        assertEquals(setOf("en"), AppText.loaded())
        // Changing language in Settings reads the new one then.
        AppSettings.language.value = AppLanguage.fromLabel("de").label
        assertTrue(AppText.t("Réverbération").isNotBlank())
        assertEquals(setOf("en", "de"), AppText.loaded())
    }

    /** The streamed reading against the whole file parsed at once, as every start did until 6.6. */
    @Test fun everyLanguageHasTheEntriesOfTheWholeFile() {
        val root = JSONObject(app.assets.open("translations.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val visual = JSONObject(app.assets.open("visual-refresh-translations.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val keys = visual.getJSONArray("keys")
        val languages = AppLanguage.all.filter { it != AppLanguage.FRENCH }
        assertEquals(10, languages.size)
        for (language in languages) {
            val obj = root.getJSONObject(language.code)
            val whole = HashMap<String, String>()
            obj.keys().forEach { key -> obj.optString(key).takeIf { it.isNotBlank() }?.let { whole[key] = it } }
            val extra = visual.getJSONObject("translations").getJSONArray(language.code)
            for (i in 0 until keys.length()) whole[keys.getString(i)] = extra.getString(i)
            val streamed = AppText.entries(app.assets, language.code)
            assertTrue("${language.code} has entries", streamed.size > 1_000)
            assertEquals(language.code, whole, streamed)
        }
    }
}
