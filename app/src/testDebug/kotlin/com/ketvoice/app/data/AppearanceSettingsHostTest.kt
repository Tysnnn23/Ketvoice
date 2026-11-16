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
            AppSettings.glassTintLevel.value = 5
            AppSettings.glassTilt.value = false
            AppSettings.techStats.value = true
            AppSettings.downloadsWifiOnly.value = true
            SettingsStore.save()
            AppSettings.glassStyle.value = 0
            AppSettings.glassTintLevel.value = 0
            AppSettings.glassTilt.value = true
            AppSettings.techStats.value = false
            AppSettings.downloadsWifiOnly.value = false
            SettingsStore.load(context)
            assertEquals(2, AppSettings.glassStyle.value)
            assertEquals(5, AppSettings.glassTintLevel.value)
            assertFalse(AppSettings.glassTilt.value)
            assertTrue(AppSettings.techStats.value)
            assertTrue(AppSettings.downloadsWifiOnly.value)
        } finally {
            AppSettings.glassStyle.value = 0
            AppSettings.glassTintLevel.value = com.ketvoice.app.glass.GlassClarity.DEFAULT
            AppSettings.glassTilt.value = true
            AppSettings.techStats.value = false
            AppSettings.downloadsWifiOnly.value = false
            SettingsStore.save()
        }
    }
    @Test fun invalidStoredTransparencyCannotReachTheShader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
        val glass = com.ketvoice.app.glass.GlassClarity
        try {
            for ((input, expected) in listOf(-5 to glass.MIN, 9 to glass.MAX, 3 to 3)) {
                preferences.edit().putInt("glassTintLevel", input).commit()
                SettingsStore.load(context)
                assertEquals(expected, AppSettings.glassTintLevel.value)
            }
            preferences.edit().putFloat("glassTintLevel", 7f).commit()
            SettingsStore.load(context)
            assertEquals("Wrong preference types must not crash loading", glass.DEFAULT, AppSettings.glassTintLevel.value)
        } finally { preferences.edit().remove("glassTintLevel").remove("glassTint").commit(); AppSettings.glassTintLevel.value = glass.DEFAULT }
    }

    @Test fun theContinuousTintOfEarlierVersionsIsConvertedOnceAndRetired() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
        val glass = com.ketvoice.app.glass.GlassClarity
        try {
            // A listener of 5.x who never touched the tint keeps today's look.
            preferences.edit().remove("glassTintLevel").putFloat("glassTint", 0f).commit()
            SettingsStore.load(context)
            assertEquals(glass.DEFAULT, AppSettings.glassTintLevel.value)
            // One who tinted it halfway lands on the nearest step, and keeps it after a save.
            preferences.edit().remove("glassTintLevel").putFloat("glassTint", .5f).commit()
            SettingsStore.load(context)
            assertEquals(4, AppSettings.glassTintLevel.value)
            SettingsStore.save()
            assertFalse("The old key is not written again", preferences.contains("glassTint"))
            assertEquals(4, preferences.getInt("glassTintLevel", -1))
            // Garbage of the old key never crashes loading.
            preferences.edit().remove("glassTintLevel").putString("glassTint", "x").commit()
            SettingsStore.load(context)
            assertEquals(glass.DEFAULT, AppSettings.glassTintLevel.value)
            // The tilt switch defaults to on.
            preferences.edit().remove("glassTilt").commit()
            SettingsStore.load(context)
            assertTrue(AppSettings.glassTilt.value)
        } finally { preferences.edit().remove("glassTintLevel").remove("glassTint").commit(); AppSettings.glassTintLevel.value = glass.DEFAULT }
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
