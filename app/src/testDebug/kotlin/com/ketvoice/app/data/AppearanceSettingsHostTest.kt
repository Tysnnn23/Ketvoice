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
            AppSettings.glassIntensity.value = .73f
            AppSettings.glassTilt.value = false
            AppSettings.techStats.value = true
            AppSettings.downloadsWifiOnly.value = true
            SettingsStore.save()
            AppSettings.glassStyle.value = 0
            AppSettings.glassIntensity.value = 0f
            AppSettings.glassTilt.value = true
            AppSettings.techStats.value = false
            AppSettings.downloadsWifiOnly.value = false
            SettingsStore.load(context)
            assertEquals(2, AppSettings.glassStyle.value)
            assertEquals(.73f, AppSettings.glassIntensity.value, 0f)
            assertFalse(AppSettings.glassTilt.value)
            assertTrue(AppSettings.techStats.value)
            assertTrue(AppSettings.downloadsWifiOnly.value)
        } finally {
            AppSettings.glassStyle.value = 0
            AppSettings.glassIntensity.value = com.ketvoice.app.glass.GlassClarity.DEFAULT
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
            for ((input, expected) in listOf(-5f to glass.MIN, 9f to glass.MAX, .3f to .3f, Float.NaN to glass.DEFAULT)) {
                preferences.edit().putFloat("glassIntensity", input).commit()
                SettingsStore.load(context)
                assertEquals(expected, AppSettings.glassIntensity.value, 0f)
            }
            preferences.edit().putInt("glassIntensity", 7).commit()
            SettingsStore.load(context)
            assertEquals("Wrong preference types must not crash loading", glass.DEFAULT, AppSettings.glassIntensity.value, 0f)
        } finally { preferences.edit().remove("glassIntensity").commit(); AppSettings.glassIntensity.value = glass.DEFAULT }
    }

    @Test fun earlierGlassSettingsAreConvertedOnceAndRetired() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
        val glass = com.ketvoice.app.glass.GlassClarity
        fun fresh() = preferences.edit().remove("glassIntensity").remove("glassTintLevel").remove("glassTinted").remove("glassTint")
        try {
            // A listener of 5.x who never touched the tint keeps today's look.
            fresh().putFloat("glassTint", 0f).commit()
            SettingsStore.load(context)
            assertEquals(glass.DEFAULT, AppSettings.glassIntensity.value, 0f)
            fresh().putFloat("glassTint", .5f).commit()
            SettingsStore.load(context)
            assertEquals(.4f, AppSettings.glassIntensity.value, 1e-4f)
            // 6.0 to 7.4: Clair keeps its level on the clear part of the travel, Teinté lands near the opaque end.
            fresh().putInt("glassTintLevel", 0).commit()
            SettingsStore.load(context)
            assertEquals(glass.MIN, AppSettings.glassIntensity.value, 0f)
            fresh().putInt("glassTintLevel", 2).putBoolean("glassTinted", true).commit()
            SettingsStore.load(context)
            assertEquals(glass.TINTED, AppSettings.glassIntensity.value, 1e-4f)
            SettingsStore.save()
            for (old in listOf("glassTint", "glassTintLevel", "glassTinted")) assertFalse("$old is not written again", preferences.contains(old))
            assertEquals(glass.TINTED, preferences.getFloat("glassIntensity", -1f), 1e-4f)
            // Garbage of the old keys never crashes loading.
            fresh().putString("glassTint", "x").putString("glassTintLevel", "y").commit()
            SettingsStore.load(context)
            assertEquals(glass.DEFAULT, AppSettings.glassIntensity.value, 0f)
            // The tilt switch defaults to on.
            preferences.edit().remove("glassTilt").commit()
            SettingsStore.load(context)
            assertTrue(AppSettings.glassTilt.value)
        } finally { fresh().commit(); AppSettings.glassIntensity.value = glass.DEFAULT }
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
