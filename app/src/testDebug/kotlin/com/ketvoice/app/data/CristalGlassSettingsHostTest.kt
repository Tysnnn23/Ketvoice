package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.glass.GlassLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Cristal look's setting (8.4): a new key, off by default, kept on reload, in backups and in the account's sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CristalGlassSettingsHostTest {
    @Test fun liquid27IsTheDefaultAndCristalSurvivesAReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).edit().remove("glassLook").commit()
        SettingsStore.load(context)
        try {
            assertEquals(GlassLook.LIQUID_27, AppSettings.glassLook.value)
            AppSettings.glassLook.value = GlassLook.CRISTAL
            SettingsStore.save()
            AppSettings.glassLook.value = 0
            SettingsStore.load(context)
            assertEquals(GlassLook.CRISTAL, AppSettings.glassLook.value)
            assertTrue(GlassLook.cristal)
            // A value no version writes falls back to the default range.
            context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).edit().putInt("glassLook", 9).commit()
            SettingsStore.load(context)
            assertEquals(GlassLook.MAX, AppSettings.glassLook.value)
        } finally {
            AppSettings.glassLook.value = 0
            SettingsStore.save()
        }
    }

    @Test fun theOldGlassStyleKeyDoesNotTurnCristalOn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Versions before Ket 27 stored 1 (« dépoli ») in glassStyle: that is not Cristal.
        context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).edit().putInt("glassStyle", 1).remove("glassLook").commit()
        SettingsStore.load(context)
        try {
            assertEquals(GlassLook.LIQUID_27, AppSettings.glassLook.value)
        } finally {
            AppSettings.glassStyle.value = 0
            SettingsStore.save()
        }
    }

    @Test fun backupsAndTheAccountsSyncCarryTheLook() {
        assertEquals(1, PersonalBackup.setting("glassLook", 1))
        assertEquals(0, PersonalBackup.setting("glassLook", 0))
        assertNull("out of range", PersonalBackup.setting("glassLook", 2))
        assertNull("not a number", PersonalBackup.setting("glassLook", "Cristal"))
        assertTrue("glassLook" in com.ketvoice.app.sync.SettingsKind.keys)
        assertEquals(1, com.ketvoice.app.sync.SettingsKind.parse("glassLook", "1"))
    }
}
