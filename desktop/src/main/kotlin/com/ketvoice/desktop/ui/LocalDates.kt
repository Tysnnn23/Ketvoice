package com.ketvoice.desktop.ui

import com.ibm.icu.text.DateFormatSymbols
import com.ibm.icu.util.ULocale
import com.ketvoice.app.data.AppText
import java.time.DayOfWeek

/**
 * Short day and month names in the app language. They come from ICU4J's own data, not the JDK's: the
 * packaged runtime carries English locale data only, so `DayOfWeek.getDisplayName` gave « Mon Tue » in every
 * language. Stand-alone forms, the ones a chart's axis uses (« lun. », « janv. », « 月 »).
 */
object LocalDates {
    private var cached: Pair<String, DateFormatSymbols>? = null

    private fun symbols(): DateFormatSymbols {
        val code = AppText.language().code
        cached?.let { if (it.first == code) return it.second }
        return DateFormatSymbols(ULocale.forLanguageTag(code)).also { cached = code to it }
    }

    /** « lun. » for Monday in French; ICU numbers the days from Sunday = 1. */
    fun weekday(day: DayOfWeek): String =
        symbols().getWeekdays(DateFormatSymbols.STANDALONE, DateFormatSymbols.ABBREVIATED)[day.value % 7 + 1]

    /** « janv. » for [month] 1 in French. */
    fun month(month: Int): String =
        symbols().getMonths(DateFormatSymbols.STANDALONE, DateFormatSymbols.ABBREVIATED)[(month - 1).coerceIn(0, 11)]
}
