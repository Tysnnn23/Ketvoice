package com.ketvoice.app.data

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Vice City limited edition (owner, 2026-11-17: « pour chaque mise à jour jusqu'au 20 novembre quand les
 * utilisateurs lancent la première fois ils ont un écran Profiter de GTA VI … Et créer un mode Vice City
 * (thème supplémentaire édition limitée jusqu'au 10 décembre) … qui sera proposé au premier lancement »).
 *
 * Two windows, both counted in the phone's own time zone, last day included:
 *  - the welcome screen opens once per app version (versionCode), at the first launch after an install or an
 *    update, up to [WELCOME_LAST_DAY];
 *  - the theme (`viceCityTheme`) and the launcher icon ([ICON_VARIANT]) are offered up to [THEME_LAST_DAY].
 *    After it the theme is hidden and switched off and the icon goes back to [DEFAULT_ICON], silently: the
 *    listener's own theme, accent and colours were never changed by it, so they simply come back.
 *
 * Everything here is a pure function of a time, a zone and a few numbers, so the windows are host-tested.
 */
object ViceCityEdition {
    val WELCOME_LAST_DAY: LocalDate = LocalDate.of(2026, 11, 20)
    val THEME_LAST_DAY: LocalDate = LocalDate.of(2026, 12, 10)

    /** The launcher icon of the edition in `appIconVariant` (see `util/LauncherIcons.kt`). */
    const val ICON_VARIANT = 4
    /** The icon a fresh install starts with (Liquid Glass), restored when the edition ends. */
    const val DEFAULT_ICON = 1

    /** The clock and the time zone; tests set them, the app keeps the phone's. */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    /** The calendar day of [millis] where the listener is. */
    fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun welcomeOpen(millis: Long = clock(), zone: ZoneId = zone()): Boolean = !day(millis, zone).isAfter(WELCOME_LAST_DAY)

    fun themeAvailable(millis: Long = clock(), zone: ZoneId = zone()): Boolean = !day(millis, zone).isAfter(THEME_LAST_DAY)

    /**
     * Whether the welcome screen opens now: within its window, and not yet shown for [versionCode]
     * ([seenVersion] is the version it was last shown for, or -1).
     */
    fun shouldWelcome(versionCode: Int, seenVersion: Int, millis: Long = clock(), zone: ZoneId = zone()): Boolean =
        welcomeOpen(millis, zone) && seenVersion != versionCode

    /** The theme is on: chosen by the listener, and the edition still running. */
    val themeOn: Boolean get() = AppSettings.viceCityTheme.value && themeAvailable()

    /** Whether [variant] may be offered or kept as the launcher icon now. */
    fun iconAllowed(variant: Int, millis: Long = clock(), zone: ZoneId = zone()): Boolean =
        variant != ICON_VARIANT || themeAvailable(millis, zone)

    /**
     * Once the edition is over, the theme is switched off and its icon replaced by [DEFAULT_ICON] in [AppSettings].
     * Returns whether something changed (the caller saves the settings and applies the icon).
     */
    fun expire(millis: Long = clock(), zone: ZoneId = zone()): Boolean {
        if (themeAvailable(millis, zone)) return false
        var changed = false
        if (AppSettings.viceCityTheme.value) { AppSettings.viceCityTheme.value = false; changed = true }
        if (AppSettings.appIconVariant.value == ICON_VARIANT) { AppSettings.appIconVariant.value = DEFAULT_ICON; changed = true }
        return changed
    }

    // ------------------------------------------------------------------ the welcome screen, once per version

    private const val PREFS = "ketvoice-editions"
    private const val WELCOME_SEEN = "viceCityWelcomeVersion"

    /** Whether the welcome screen is due at this launch (this app version has not shown it yet). */
    fun welcomeDue(context: Context, versionCode: Int = com.ketvoice.app.BuildConfig.VERSION_CODE): Boolean =
        shouldWelcome(versionCode, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(WELCOME_SEEN, -1))

    /** The welcome screen was shown for [versionCode]: it does not come back before the next update. */
    fun welcomeShown(context: Context, versionCode: Int = com.ketvoice.app.BuildConfig.VERSION_CODE) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(WELCOME_SEEN, versionCode).apply()
    }
}
