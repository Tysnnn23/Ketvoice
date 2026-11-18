package com.ketvoice.app.data

import android.content.Context
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/**
 * When a seasonal theme may be used: from [start] to [end], both days included, counted in the phone's own time
 * zone. A window whose end comes before its start in the calendar runs over New Year (31 Dec – 2 Jan). With
 * [year], the window happens once, that year only (the Vice City edition); without, it comes back every year.
 */
data class SeasonWindow(val start: MonthDay, val end: MonthDay, val year: Int? = null) {
    /** The first and last day of the occurrence that contains [day], or null when [day] is outside every one. */
    fun occurrence(day: LocalDate): Pair<LocalDate, LocalDate>? {
        val years = year?.let { listOf(it) } ?: listOf(day.year - 1, day.year)
        for (y in years) {
            val first = start.atYear(y)
            val last = if (end.isBefore(start)) end.atYear(y + 1) else end.atYear(y)
            if (!day.isBefore(first) && !day.isAfter(last)) return first to last
        }
        return null
    }
}

/**
 * One limited theme of the registry ([Seasons]). Its look (palette, glows, decor) is in `ui/seasons/SeasonLooks.kt`,
 * keyed by [id]; the texts here are French, localised where they are shown.
 *
 * Images the owner may add later, all optional (drawn decor stands in until then), looked up by name in `res`:
 *  - `season_<id>_welcome` — the full-screen welcome picture, portrait, about 1440 × 3040 px (WebP, `drawable-nodpi`);
 *  - `season_<id>_header` — Home's header decor over the glow, transparent, 1080 × 750 px (WebP, `drawable-nodpi`);
 *  - `season_<id>_emblem` — the square badge in Settings and on the welcome card, 512 × 512 px (WebP, `drawable-nodpi`).
 */
data class SeasonalTheme(
    val id: String,
    /** The switch's label in Settings → Apparence, e.g. « Thème Noël ». */
    val title: String,
    /** The line under it, e.g. « Édition limitée, jusqu'au 31 décembre ». */
    val until: String,
    /** A sentence on its look, under its preview. */
    val description: String,
    /** The welcome card's headline. */
    val headline: String,
    val window: SeasonWindow,
    /** Its launcher icon in `appIconVariant`, when it has one (an activity-alias in the manifest). */
    val iconVariant: Int? = null,
    /** It has its own welcome screen and offer (Vice City), not the registry's welcome card. */
    val ownWelcome: Boolean = false,
)

/**
 * The seasonal themes (6.2): a limited theme for each season, generalised from the Vice City edition, which stays
 * the first entry and works as before (its own welcome, `viceCityTheme` key and launcher icon).
 *
 *  - Only one season is active on a day: when two windows overlap, the one whose occurrence started later wins
 *    (Nouvel An over Noël on 31 December).
 *  - A season is offered once per occurrence, at the first launch inside its window ([offerDue]); it can be
 *    switched on or off in Settings → Apparence only while active, and it is switched off silently afterwards
 *    ([expire]): the listener's theme and accent were never changed, so they simply come back.
 *  - The choice of a season other than Vice City is the `seasonTheme` key (its id, or empty).
 */
object Seasons {
    private fun md(month: Int, day: Int): MonthDay = MonthDay.of(month, day)

    val VICE_CITY = SeasonalTheme("vicecity", "Thème Vice City", "Édition limitée, jusqu'au 10 décembre",
        "Rose flamant et coucher de soleil sur Ocean Drive. Votre thème clair ou sombre est gardé ; votre couleur d'accent revient quand vous le désactivez.",
        "Profitez de GTA VI en écoutant vos musiques sur Ketvoice !",
        SeasonWindow(md(11, 1), md(ViceCityEdition.THEME_LAST_DAY.monthValue, ViceCityEdition.THEME_LAST_DAY.dayOfMonth),
            ViceCityEdition.THEME_LAST_DAY.year),
        iconVariant = ViceCityEdition.ICON_VARIANT, ownWelcome = true)
    val NOEL = SeasonalTheme("noel", "Thème Noël", "Édition limitée, jusqu'au 31 décembre",
        "Nuit de sapin, guirlande dorée et flocons. Votre thème et votre couleur d'accent reviennent quand vous le désactivez.",
        "Le thème Noël est arrivé", SeasonWindow(md(12, 11), md(12, 31)))
    val NOUVEL_AN = SeasonalTheme("nouvelan", "Thème Nouvel An", "Édition limitée, jusqu'au 2 janvier",
        "Minuit bleu nuit, or et feux d'artifice. Votre thème et votre couleur d'accent reviennent quand vous le désactivez.",
        "Bonne année avec Ketvoice", SeasonWindow(md(12, 31), md(1, 2)))
    val SAINT_VALENTIN = SeasonalTheme("saintvalentin", "Thème Saint-Valentin", "Édition limitée, jusqu'au 15 février",
        "Rouge velours, rose tendre et petits cœurs. Votre thème et votre couleur d'accent reviennent quand vous le désactivez.",
        "Le thème Saint-Valentin est arrivé", SeasonWindow(md(2, 7), md(2, 15)))
    val PRINTEMPS = SeasonalTheme("printemps", "Thème Printemps", "Édition limitée, jusqu'au 20 avril",
        "Fleurs de cerisier, vert tendre et lumière douce. Votre thème et votre couleur d'accent reviennent quand vous le désactivez.",
        "Le printemps arrive sur Ketvoice", SeasonWindow(md(3, 20), md(4, 20)))
    val ETE = SeasonalTheme("ete", "Thème Été", "Édition limitée, jusqu'au 31 août",
        "Soleil couchant sur l'océan, orange et turquoise. Votre thème et votre couleur d'accent reviennent quand vous le désactivez.",
        "L'été arrive sur Ketvoice", SeasonWindow(md(6, 21), md(8, 31)))
    val HALLOWEEN = SeasonalTheme("halloween", "Thème Halloween", "Édition limitée, jusqu'au 2 novembre",
        "Nuit d'automne, citrouille orange et chauves-souris. Votre thème et votre couleur d'accent reviennent quand vous le désactivez.",
        "Le thème Halloween est arrivé", SeasonWindow(md(10, 20), md(11, 2)))

    val all: List<SeasonalTheme> = listOf(VICE_CITY, NOEL, NOUVEL_AN, SAINT_VALENTIN, PRINTEMPS, ETE, HALLOWEEN)

    fun byId(id: String?): SeasonalTheme? = all.firstOrNull { it.id == id }

    /** The clock and the zone: the Vice City edition's, so one setting moves both in tests. */
    @Volatile internal var clock: () -> Long = { ViceCityEdition.clock() }
    @Volatile internal var zone: () -> ZoneId = { ViceCityEdition.zone() }

    /** The season active on [day] and the first day of its occurrence; the latest start wins an overlap. */
    fun activeOn(day: LocalDate): Pair<SeasonalTheme, LocalDate>? =
        all.mapNotNull { season -> season.window.occurrence(day)?.let { season to it.first } }
            .maxByOrNull { it.second }

    fun active(millis: Long = clock(), zone: ZoneId = zone()): SeasonalTheme? =
        activeOn(ViceCityEdition.day(millis, zone))?.first

    /** Whether the listener has switched [season] on (it shows only while active, see [inEffect]). */
    fun chosen(season: SeasonalTheme): Boolean =
        if (season.ownWelcome) AppSettings.viceCityTheme.value else AppSettings.seasonTheme.value == season.id

    /** The seasonal theme drawn now: the active season, when chosen. */
    fun inEffect(millis: Long = clock(), zone: ZoneId = zone()): SeasonalTheme? = active(millis, zone)?.takeIf(::chosen)

    /** Switches [season] on or off (the caller saves the settings). Turning one on turns any other off. */
    fun choose(season: SeasonalTheme, on: Boolean) {
        if (season.ownWelcome) { AppSettings.viceCityTheme.value = on; return }
        AppSettings.seasonTheme.value = if (on) season.id else if (AppSettings.seasonTheme.value == season.id) "" else AppSettings.seasonTheme.value
    }

    /** Whether a stored `seasonTheme` value may be kept or restored now: empty, or the active season. */
    fun storable(id: String, millis: Long = clock(), zone: ZoneId = zone()): Boolean =
        id.isEmpty() || (byId(id)?.takeIf { !it.ownWelcome } != null && active(millis, zone)?.id == id)

    /**
     * Ends what is over: the Vice City edition ([ViceCityEdition.expire]) and a season no longer active.
     * Returns whether something changed (the caller saves the settings and applies the icon).
     */
    fun expire(millis: Long = clock(), zone: ZoneId = zone()): Boolean {
        val vice = ViceCityEdition.expire(millis, zone)
        val stale = !storable(AppSettings.seasonTheme.value, millis, zone)
        if (stale) AppSettings.seasonTheme.value = ""
        return vice || stale
    }

    // ------------------------------------------------------------------ offered once per occurrence

    private const val PREFS = "ketvoice-editions"

    /** The key that remembers [season] was offered in the occurrence starting on [start]. */
    internal fun offerKey(season: SeasonalTheme, start: LocalDate): String = "seasonOffered_${season.id}_$start"

    /** The season to offer now, if any: active, not Vice City (it has its own welcome), not offered in this occurrence. */
    fun offerDue(offered: (String) -> Boolean, millis: Long = clock(), zone: ZoneId = zone()): SeasonalTheme? {
        val (season, start) = activeOn(ViceCityEdition.day(millis, zone)) ?: return null
        if (season.ownWelcome || chosen(season)) return null
        return season.takeUnless { offered(offerKey(season, start)) }
    }

    fun offerDue(context: Context): SeasonalTheme? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return offerDue({ prefs.getBoolean(it, false) })
    }

    /** [season] was offered: not again before its next occurrence. */
    fun offered(context: Context, season: SeasonalTheme, millis: Long = clock(), zone: ZoneId = zone()) {
        val start = season.window.occurrence(ViceCityEdition.day(millis, zone))?.first ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(offerKey(season, start), true).apply()
    }
}
