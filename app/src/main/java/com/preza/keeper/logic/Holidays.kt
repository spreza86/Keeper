package com.preza.keeper.logic

import java.time.LocalDate

/** A small built-in list of holidays and big events, matched by country and local date. */
object Holidays {
    private val fixed: Map<String, Map<String, String>> = mapOf(
        "VN" to mapOf("01-01" to "New Year's Day", "04-30" to "Liberation Day", "05-01" to "Labour Day", "09-02" to "National Day"),
        "HK" to mapOf("01-01" to "New Year's Day", "07-01" to "HKSAR Day", "10-01" to "National Day", "12-25" to "Christmas"),
        "SG" to mapOf("01-01" to "New Year's Day", "05-01" to "Labour Day", "08-09" to "National Day", "12-25" to "Christmas"),
        "TH" to mapOf("04-13" to "Songkran", "04-14" to "Songkran", "04-15" to "Songkran"),
        "JP" to mapOf("01-01" to "New Year's Day"),
        "CA" to mapOf("01-01" to "New Year's Day", "06-24" to "Fête nationale", "07-01" to "Canada Day", "10-31" to "Halloween", "12-25" to "Christmas", "12-31" to "New Year's Eve"),
        "US" to mapOf("01-01" to "New Year's Day", "07-04" to "Independence Day", "10-31" to "Halloween", "12-25" to "Christmas", "12-31" to "New Year's Eve"),
        "FR" to mapOf("07-14" to "Bastille Day", "12-25" to "Christmas"),
        "MX" to mapOf("09-16" to "Independence Day", "11-01" to "Día de Muertos", "11-02" to "Día de Muertos")
    )

    /** Lunar New Year moves every year. */
    private val lunarNewYear = setOf(
        "2023-01-22", "2024-02-10", "2025-01-29", "2026-02-17", "2027-02-06", "2028-01-26", "2029-02-13", "2030-02-03"
    )
    private val lunarCountries = setOf("VN", "HK", "SG", "CN", "TW", "MO", "MY", "KR")

    fun lookup(cc: String?, date: LocalDate): String? {
        if (cc == null) return null
        if (cc in lunarCountries) {
            for (d in 0..2) if (date.minusDays(d.toLong()).toString() in lunarNewYear) return if (cc == "VN") "Tết" else "Lunar New Year"
            if (date.plusDays(1).toString() in lunarNewYear) return "Lunar New Year's Eve"
        }
        val md = "%02d-%02d".format(date.monthValue, date.dayOfMonth)
        return fixed[cc]?.get(md)
    }
}
