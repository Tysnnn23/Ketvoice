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
 * value stored by an earlier version stays where it is and changes nothing. 7.6 retires `viceCityTheme` (the
 * Vice City edition's theme) the same way, and makes the GTA VI icon the default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetiredInterfaceSettingsHostTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
    private val retired = listOf("ket27", "lyricsStyle", "design212_D1", "design212_D5", "viceCityTheme")

    @Before @After fun clean() {
        prefs.edit().apply { retired.forEach(::remove) }.remove("appIconVariant").remove(com.ketvoice.app.util.LauncherIcons.MIGRATED_KEY).commit()
    }

    @Test fun aStoredViceCityThemeChangesNothing() {
        prefs.edit().putBoolean("viceCityTheme", true).commit()
        SettingsStore.load(context)
        SettingsStore.save()
        assertTrue("left as it was", prefs.getBoolean("viceCityTheme", false))
        assertNull("no seasonal look", Seasons.inEffect())
    }

    @Test fun theGtaViIconIsTheDefaultAndAnIconPickedEarlierStays() {
        val icons = com.ketvoice.app.util.LauncherIcons
        // A new install, and an update from a version where every save wrote the old default (Liquid Glass).
        SettingsStore.load(context)
        assertEquals(icons.DEFAULT, AppSettings.appIconVariant.value)
        prefs.edit().putInt("appIconVariant", 1).remove(icons.MIGRATED_KEY).commit()
        SettingsStore.load(context)
        assertEquals(icons.DEFAULT, AppSettings.appIconVariant.value)
        // Another icon picked before 7.6 stays.
        prefs.edit().putInt("appIconVariant", 3).remove(icons.MIGRATED_KEY).commit()
        SettingsStore.load(context)
        assertEquals(3, AppSettings.appIconVariant.value)
        // Liquid Glass picked since 7.6 stays too.
        AppSettings.appIconVariant.value = 1
        SettingsStore.save()
        SettingsStore.load(context)
        assertEquals(1, AppSettings.appIconVariant.value)
        assertEquals(icons.DEFAULT, icons.loaded(null, false))
        assertEquals(icons.DEFAULT, icons.loaded(9, true))
        AppSettings.appIconVariant.value = icons.DEFAULT
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
        prefs.edit().putBoolean("ket27", true).putInt("lyricsStyle", 1).putBoolean("design212_D1", true).putBoolean("viceCityTheme", true).commit()
        val backup = PersonalBackup.collect(context)
        for (key in retired) assertFalse("$key is not in the backup", backup.settings.containsKey(key))
        // A backup made by 5.x that carries them restores without them.
        val json = org.json.JSONObject(String(PersonalBackup.encode(backup), Charsets.UTF_8))
        json.getJSONObject("settings").put("ket27", false).put("lyricsStyle", 0).put("design212_D1", false).put("viceCityTheme", true)
        val decoded = PersonalBackup.decode(json.toString().toByteArray(Charsets.UTF_8))
        for (key in retired) assertFalse("$key is dropped on restore", decoded.settings.containsKey(key))
    }
}
