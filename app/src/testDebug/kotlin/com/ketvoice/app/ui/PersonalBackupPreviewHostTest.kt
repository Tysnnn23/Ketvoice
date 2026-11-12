package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PersonalBackupData
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.settings.PersonalBackupPreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class PersonalBackupPreviewHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun previewDoesNotImportUntilTheUserConfirms() {
        val previousLanguage = AppSettings.language.value
        val previousChosen = AppSettings.languageChosen.value
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        var imported = false
        var cancelled = false
        val data = PersonalBackupData(listOf(LocalPlaylist(id = "id", name = "List")), setOf("favorite"),
            mapOf("themeMode" to 1), emptyList(), emptyList(), emptyMap(), 0)
        try {
            compose.setContent { KetvoiceTheme(useDark = false) { Column {
                PersonalBackupPreview(data, false, onImport = { imported = true }, onCancel = { cancelled = true })
            } } }
            compose.onNodeWithText("Aperçu de la sauvegarde").assertIsDisplayed()
            assertFalse(imported)
            compose.onNodeWithText("Annuler").performClick()
            assertTrue(cancelled)
            assertFalse(imported)
            compose.onNodeWithText("Importer").performClick()
            assertTrue(imported)
        } finally {
            AppSettings.language.value = previousLanguage
            AppSettings.languageChosen.value = previousChosen
        }
    }
}
