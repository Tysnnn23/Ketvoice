package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.theme.T
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 6.0.0: Ket 27 is the only interface and Cascade the only way lyrics move. Their settings
 * (`ket27`, `lyricsStyle`) are retired: never written, never read, never carried by a backup; a
 * value stored by an earlier version stays where it is and changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetiredInterfaceSettingsHostTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
    private val retired = listOf("ket27", "lyricsStyle", "design212_D1", "design212_D5")

    @Before @After fun clean() {
        prefs.edit().apply { retired.forEach(::remove) }.commit()
    }

    @Test fun aSaveNeverWritesTheRetiredKeys() {
        SettingsStore.load(context)
        SettingsStore.save()
        for (key in retired) assertFalse("$key is not written", prefs.contains(key))
    }

    @Test fun valuesStoredByAnEarlierVersionAreIgnoredAndLeftAsTheyWere() {
        // The classic interface and the "Ketvoice" lyrics style, as 5.x stored them.
        prefs.edit().putBoolean("ket27", false).putInt("lyricsStyle", 0)
            .putBoolean("design212_D1", false).putBoolean("design212_D5", false).commit()
        SettingsStore.load(context)
        // The interface is Ket 27's whatever was stored: its large title is iOS's 34 dp.
        assertEquals(34f, T.hugeTitle.fontSize.value, 0f)
        assertTrue("the retired design choices are not offered any more",
            DesignChoices.all.none { it.code == "D1" || it.code == "D5" })
        SettingsStore.save()
        // Nothing reads them and nothing rewrites them.
        assertFalse(prefs.getBoolean("ket27", true))
        assertEquals(0, prefs.getInt("lyricsStyle", -1))
    }

    @Test fun aBackupNeverCarriesThem() {
        prefs.edit().putBoolean("ket27", true).putInt("lyricsStyle", 1).putBoolean("design212_D1", true).commit()
        val backup = PersonalBackup.collect(context)
        for (key in retired) assertFalse("$key is not in the backup", backup.settings.containsKey(key))
        // A backup made by 5.x that carries them restores without them.
        val json = org.json.JSONObject(String(PersonalBackup.encode(backup), Charsets.UTF_8))
        json.getJSONObject("settings").put("ket27", false).put("lyricsStyle", 0).put("design212_D1", false)
        val decoded = PersonalBackup.decode(json.toString().toByteArray(Charsets.UTF_8))
        for (key in retired) assertFalse("$key is dropped on restore", decoded.settings.containsKey(key))
    }
}
