package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppearanceSettingsHostTest {
    @Test fun glassStatsAndWifiChoicesSurviveReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsStore.load(context)
        try {
            AppSettings.glassStyle.value = 2
            AppSettings.techStats.value = true
            AppSettings.downloadsWifiOnly.value = true
            SettingsStore.save()
            AppSettings.glassStyle.value = 0
            AppSettings.techStats.value = false
            AppSettings.downloadsWifiOnly.value = false
            SettingsStore.load(context)
            assertEquals(2, AppSettings.glassStyle.value)
            assertTrue(AppSettings.techStats.value)
            assertTrue(AppSettings.downloadsWifiOnly.value)
        } finally {
            AppSettings.glassStyle.value = 0
            AppSettings.techStats.value = false
            AppSettings.downloadsWifiOnly.value = false
            SettingsStore.save()
        }
    }
    @After fun reset() { AppSettings.artistSignatures.value = false; AppSettings.highRefreshRate.value = false }
    @Test fun unusedLegacySignatureFlagCannotEnableNetworkWithoutConsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("artistSignatures", true).commit()
        SettingsStore.load(context)
        assertFalse(AppSettings.artistSignatures.value)
        assertFalse(prefs.contains("artistSignatures"))
    }
    @Test fun optInAndHighRefreshChoiceSurviveReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsStore.load(context)
        AppSettings.artistSignatures.value = true
        AppSettings.highRefreshRate.value = true
        SettingsStore.save()
        AppSettings.artistSignatures.value = false
        AppSettings.highRefreshRate.value = false
        SettingsStore.load(context)
        assertTrue(AppSettings.artistSignatures.value)
        assertTrue(AppSettings.highRefreshRate.value)
    }
    @Test fun disabledSignaturesReturnBeforeAnyNetworkLookup() = runBlocking {
        AppSettings.artistSignatures.value = false
        assertNull(ArtistSignatures.find("QA offline artist"))
    }
}
