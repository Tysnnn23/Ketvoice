package com.ketvoice.app.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The default language must be the device language whenever Ketvoice supports it. */
class AppLanguageTest {

    @Test fun supportedDeviceLanguagesBecomeTheDefault() {
        assertEquals(AppLanguage.SPANISH, AppLanguage.systemDefault(Locale("es", "ES")))
        assertEquals(AppLanguage.GERMAN, AppLanguage.systemDefault(Locale.GERMANY))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.systemDefault(Locale("pt", "BR")))
        assertEquals(AppLanguage.KOREAN, AppLanguage.systemDefault(Locale.KOREA))
    }

    @Test fun regionAndScriptSuffixesAreIgnored() {
        assertEquals(AppLanguage.CHINESE, AppLanguage.systemDefault(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.systemDefault(Locale.UK))
    }

    @Test fun unsupportedDeviceLanguageFallsBackToEnglish() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.systemDefault(Locale("ar", "EG")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.systemDefault(Locale("nl", "NL")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.systemDefault(Locale.ROOT))
    }
}
