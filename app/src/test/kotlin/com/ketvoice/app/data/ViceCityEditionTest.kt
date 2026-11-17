package com.ketvoice.app.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The two windows of the Vice City edition (6.1), in the listener's own time zone, last day included. */
class ViceCityEditionTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val losAngeles = ZoneId.of("America/Los_Angeles")

    private fun at(text: String, zone: ZoneId) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @After fun reset() {
        ViceCityEdition.clock = { System.currentTimeMillis() }
        ViceCityEdition.zone = { ZoneId.systemDefault() }
        AppSettings.viceCityTheme.value = false
        AppSettings.appIconVariant.value = 1
    }

    @Test fun theWelcomeOpensUpToTheTwentiethOfNovemberIncluded() {
        assertTrue(ViceCityEdition.welcomeOpen(at("2026-11-17T09:00", paris), paris))
        assertTrue(ViceCityEdition.welcomeOpen(at("2026-11-20T00:00", paris), paris))
        assertTrue("the whole last day", ViceCityEdition.welcomeOpen(at("2026-11-20T23:59:59", paris), paris))
        assertFalse(ViceCityEdition.welcomeOpen(at("2026-11-21T00:00", paris), paris))
        assertFalse(ViceCityEdition.welcomeOpen(at("2027-01-01T12:00", paris), paris))
    }

    @Test fun theDayIsTheListenersOwn() {
        // 21 November 06:30 in Tokyo is still 20 November 22:30 in Paris (and 13:30 in Los Angeles).
        val moment = at("2026-11-21T06:30", tokyo)
        assertFalse(ViceCityEdition.welcomeOpen(moment, tokyo))
        assertTrue(ViceCityEdition.welcomeOpen(moment, paris))
        assertTrue(ViceCityEdition.welcomeOpen(moment, losAngeles))
        // The theme's last day, likewise.
        val end = at("2026-12-11T00:30", paris)
        assertFalse(ViceCityEdition.themeAvailable(end, paris))
        assertTrue(ViceCityEdition.themeAvailable(end, losAngeles))
    }

    @Test fun theWelcomeOpensOncePerVersion() {
        val now = at("2026-11-18T10:00", paris)
        assertTrue("first launch after an install", ViceCityEdition.shouldWelcome(128, -1, now, paris))
        assertFalse("the same version, launched again", ViceCityEdition.shouldWelcome(128, 128, now, paris))
        assertTrue("first launch after an update", ViceCityEdition.shouldWelcome(129, 128, now, paris))
        assertFalse("never after the 20th", ViceCityEdition.shouldWelcome(130, 129, at("2026-11-21T08:00", paris), paris))
    }

    @Test fun theThemeLastsUntilTheTenthOfDecemberIncluded() {
        assertTrue(ViceCityEdition.themeAvailable(at("2026-11-17T09:00", paris), paris))
        assertTrue(ViceCityEdition.themeAvailable(at("2026-12-10T23:59:59", paris), paris))
        assertFalse(ViceCityEdition.themeAvailable(at("2026-12-11T00:00", paris), paris))
        assertTrue(ViceCityEdition.iconAllowed(ViceCityEdition.ICON_VARIANT, at("2026-12-10T12:00", paris), paris))
        assertFalse(ViceCityEdition.iconAllowed(ViceCityEdition.ICON_VARIANT, at("2026-12-11T12:00", paris), paris))
        assertTrue("other icons are never limited", ViceCityEdition.iconAllowed(2, at("2027-06-01T12:00", paris), paris))
    }

    @Test fun afterTheEditionTheThemeAndIconGoBackSilently() {
        ViceCityEdition.zone = { paris }
        AppSettings.viceCityTheme.value = true
        AppSettings.appIconVariant.value = ViceCityEdition.ICON_VARIANT
        AppSettings.themeMode.value = 1
        AppSettings.accentArgb.value = 0xFF34C759L
        try {
            ViceCityEdition.clock = { at("2026-12-10T22:00", paris) }
            assertFalse("nothing changes while it lasts", ViceCityEdition.expire())
            assertTrue(ViceCityEdition.themeOn)
            ViceCityEdition.clock = { at("2026-12-11T00:01", paris) }
            assertFalse("over: no longer on, even before the settings are touched", ViceCityEdition.themeOn)
            assertTrue(ViceCityEdition.expire())
            assertFalse(AppSettings.viceCityTheme.value)
            assertEquals(ViceCityEdition.DEFAULT_ICON, AppSettings.appIconVariant.value)
            // The listener's own look was never changed by the theme.
            assertEquals(1, AppSettings.themeMode.value)
            assertEquals(0xFF34C759L, AppSettings.accentArgb.value)
            assertFalse("once is enough", ViceCityEdition.expire())
        } finally {
            AppSettings.themeMode.value = 0
            AppSettings.accentArgb.value = 0xFFFF2D55L
        }
    }

    @Test fun backupsCarryTheEditionOnlyWhileItLasts() {
        ViceCityEdition.zone = { paris }
        val data = PersonalBackupData(emptyList(), emptySet(),
            mapOf("viceCityTheme" to true, "appIconVariant" to ViceCityEdition.ICON_VARIANT, "themeMode" to 2),
            emptyList(), emptyList(), emptyMap(), 0)
        ViceCityEdition.clock = { at("2026-11-30T12:00", paris) }
        val during = PersonalBackup.decode(PersonalBackup.encode(data))
        assertEquals(true, during.settings["viceCityTheme"])
        assertEquals(ViceCityEdition.ICON_VARIANT, during.settings["appIconVariant"])
        val encoded = PersonalBackup.encode(data)
        ViceCityEdition.clock = { at("2026-12-11T09:00", paris) }
        val after = PersonalBackup.decode(encoded)
        assertFalse("not restored after the edition", after.settings.containsKey("viceCityTheme"))
        assertFalse(after.settings.containsKey("appIconVariant"))
        assertEquals("the rest of the backup is untouched", 2, after.settings["themeMode"])
        // Off always travels.
        val off = PersonalBackup.decode(PersonalBackup.encode(data.copy(settings = mapOf("viceCityTheme" to false))))
        assertEquals(false, off.settings["viceCityTheme"])
        // A garbage icon number never passes.
        val wrong = PersonalBackup.decode(PersonalBackup.encode(data.copy(settings = mapOf("appIconVariant" to 9))))
        assertFalse(wrong.settings.containsKey("appIconVariant"))
    }
}
