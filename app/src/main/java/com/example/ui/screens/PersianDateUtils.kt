package com.example.ui.screens

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar

object PersianDateUtils {
    private val persianDigits = charArrayOf('۰','۱','۲','۳','۴','۵','۶','۷','۸','۹')

    fun toPersianDigits(value: String): String =
        value.map { ch ->
            if (ch in '0'..'9') persianDigits[ch - '0'] else ch
        }.joinToString("")

    fun fromPersianDigits(value: String): String =
        value.map { ch ->
            when (ch) {
                '۰' -> '0'; '۱' -> '1'; '۲' -> '2'; '۳' -> '3'; '۴' -> '4'
                '۵' -> '5'; '۶' -> '6'; '۷' -> '7'; '۸' -> '8'; '۹' -> '9'
                else -> ch
            }
        }.joinToString("")

    fun format(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val gYear = c.get(Calendar.YEAR)
        val gMonth = c.get(Calendar.MONTH) + 1
        val gDay = c.get(Calendar.DAY_OF_MONTH)
        val (jy, jm, jd) = gregorianToJalali(gYear, gMonth, gDay)
        return toPersianDigits("%04d/%02d/%02d".format(jy, jm, jd))
    }

    fun parse(value: String, fallbackMillis: Long = System.currentTimeMillis()): Long {
        val normalized = fromPersianDigits(value.trim()).replace('-', '/')
        val parts = normalized.split('/').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return fallbackMillis
        val (jy, jm, jd) = parts
        if (jy < 1200 || jm !in 1..12 || jd !in 1..31) return fallbackMillis
        val (gy, gm, gd) = jalaliToGregorian(jy, jm, jd)
        return GregorianCalendar(gy, gm - 1, gd, 12, 0, 0).apply {
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int,Int,Int> {
        val gdm = intArrayOf(0,31,59,90,120,151,181,212,243,273,304,334)
        var gy2 = gy
        val gy0 = if (gm > 2) gy2 + 1 else gy2
        var days = 355666 + 365 * gy2 + ((gy0 + 3) / 4) - ((gy0 + 99) / 100) + ((gy0 + 399) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm = if (days < 186) 1 + days / 31 else 7 + (days - 186) / 30
        val jd = 1 + if (days < 186) days % 31 else (days - 186) % 30
        return Triple(jy, jm, jd)
    }

    private fun jalaliToGregorian(jy: Int, jm: Int, jd: Int): Triple<Int,Int,Int> {
        var y = jy
        val epBase = y - if (y >= 0) 474 else 473
        val epYear = 474 + (epBase % 2820)
        val monthDays = if (jm <= 7) (jm - 1) * 31 else (jm - 1) * 30 + 6
        val jdn = jd + monthDays +
            ((epYear * 682 - 110) / 2816) +
            (epYear - 1) * 365 +
            (epBase / 2820) * 1029983 +
            (1948320 - 1)
        val depoch = jdn - 2121446
        val cycle = depoch / 146097
        val cyear = depoch % 146097
        var ycycle = if (cyear == 146096) 36524 else (cyear % 36524) / 1461 * 365 + (cyear % 36524) % 1461 / 365
        y = 400 * cycle + ycycle
        val yday = jdn - (365 * y + y / 4 - y / 100 + y / 400)
        val gy = y + if (jdn < 0) 0 else 1
        val leap = GregorianCalendar().isLeapYear(gy)
        val gMonthDays = intArrayOf(31, if (leap) 29 else 28, 31,30,31,30,31,31,30,31,30,31)
        var day = yday
        var gm = 1
        while (gm <= 12 && day > gMonthDays[gm - 1]) {
            day -= gMonthDays[gm - 1]
            gm++
        }
        return Triple(gy, gm, day)
    }
}
