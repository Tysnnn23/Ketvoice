package com.ketvoice.app.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** The Vice City edition (6.1) in the stored settings, the welcome's once-per-version mark and the launcher icon. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViceCitySettingsHostTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(paris).toInstant().toEpochMilli()

    @After fun reset() {
        ViceCityEdition.clock = { System.currentTimeMillis() }
        ViceCityEdition.zone = { ZoneId.systemDefault() }
        AppSettings.viceCityTheme.value = false
        AppSettings.appIconVariant.value = 1
        prefs.edit().remove("viceCityTheme").remove("appIconVariant").commit()
        context.getSharedPreferences("ketvoice-editions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun theThemeSurvivesAReloadWhileTheEditionLasts() {
        ViceCityEdition.zone = { paris }
        ViceCityEdition.clock = { at("2026-11-25T12:00") }
        SettingsStore.load(context)
        assertFalse("off by default", AppSettings.viceCityTheme.value)
        AppSettings.viceCityTheme.value = true
        AppSettings.appIconVariant.value = ViceCityEdition.ICON_VARIANT
        SettingsStore.save()
        AppSettings.viceCityTheme.value = false
        AppSettings.appIconVariant.value = 1
        SettingsStore.load(context)
        assertTrue(AppSettings.viceCityTheme.value)
        assertTrue(ViceCityEdition.themeOn)
        assertEquals(ViceCityEdition.ICON_VARIANT, AppSettings.appIconVariant.value)
    }

    @Test fun afterTheEditionTheNextStartGoesBackAndRemembersIt() {
        ViceCityEdition.zone = { paris }
        prefs.edit().putBoolean("viceCityTheme", true).putInt("appIconVariant", ViceCityEdition.ICON_VARIANT)
            .putInt("themeMode", 1).commit()
        ViceCityEdition.clock = { at("2026-12-11T08:00") }
        SettingsStore.load(context)
        assertFalse(AppSettings.viceCityTheme.value)
        assertEquals(ViceCityEdition.DEFAULT_ICON, AppSettings.appIconVariant.value)
        assertEquals("the listener's theme is still theirs", 1, AppSettings.themeMode.value)
        assertFalse("stored at once", prefs.getBoolean("viceCityTheme", true))
        assertEquals(ViceCityEdition.DEFAULT_ICON, prefs.getInt("appIconVariant", -1))
        prefs.edit().remove("themeMode").commit(); AppSettings.themeMode.value = 0
    }

    @Test fun theWelcomeIsDueOncePerVersion() {
        ViceCityEdition.zone = { paris }
        ViceCityEdition.clock = { at("2026-11-19T18:00") }
        assertTrue(ViceCityEdition.welcomeDue(context, versionCode = 130))
        ViceCityEdition.welcomeShown(context, versionCode = 130)
        assertFalse(ViceCityEdition.welcomeDue(context, versionCode = 130))
        assertTrue("the next update shows it again", ViceCityEdition.welcomeDue(context, versionCode = 131))
        ViceCityEdition.clock = { at("2026-11-21T00:00") }
        assertFalse(ViceCityEdition.welcomeDue(context, versionCode = 131))
    }

    @Test fun everyIconVariantHasItsLauncherAlias() {
        val pm = context.packageManager
        com.ketvoice.app.util.LauncherIcons.aliasNames.forEach { alias ->
            val info = pm.getActivityInfo(ComponentName(context.packageName, com.ketvoice.app.util.LauncherIcons.className(alias)),
                PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(com.ketvoice.app.MainActivity::class.java.name, info.targetActivity)
        }
        assertEquals(ViceCityEdition.ICON_VARIANT, com.ketvoice.app.util.LauncherIcons.aliasNames.indexOf("LauncherViceCity"))
    }

    @Test fun afterTheEditionTheViceCityIconIsNeverEnabledAgain() {
        ViceCityEdition.zone = { paris }
        val pm = context.packageManager
        fun state(alias: String) = pm.getComponentEnabledSetting(ComponentName(context.packageName, com.ketvoice.app.util.LauncherIcons.className(alias)))
        ViceCityEdition.clock = { at("2026-12-01T12:00") }
        com.ketvoice.app.util.LauncherIcons.apply(context, ViceCityEdition.ICON_VARIANT)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state("LauncherViceCity"))
        ViceCityEdition.clock = { at("2026-12-11T12:00") }
        com.ketvoice.app.util.LauncherIcons.apply(context, ViceCityEdition.ICON_VARIANT)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("LauncherViceCity"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state("LauncherGlass"))
    }
}
