package com.example.ui.screens

import java.util.Calendar
import java.util.GregorianCalendar

object PersianDateUtils {
    private val pDigits = charArrayOf('۰','۱','۲','۳','۴','۵','۶','۷','۸','۹')

    fun toPersianDigits(value: String): String =
        value.map { if (it in '0'..'9') pDigits[it - '0'] else it }.joinToString("")

    fun fromPersianDigits(value: String): String =
        value.map {
            when (it) {
                '۰' -> '0'; '۱' -> '1'; '۲' -> '2'; '۳' -> '3'; '۴' -> '4'
                '۵' -> '5'; '۶' -> '6'; '۷' -> '7'; '۸' -> '8'; '۹' -> '9'
                else -> it
            }
        }.joinToString("")

    fun format(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val j = gregorianToJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return toPersianDigits("%04d/%02d/%02d".format(j[0], j[1], j[2]))
    }

    fun jalaliParts(millis: Long): IntArray {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return gregorianToJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun toMillis(jy: Int, jm: Int, jd: Int): Long {
        val g = jalaliToGregorian(jy, jm, jd)
        return GregorianCalendar(g[0], g[1] - 1, g[2], 12, 0, 0).apply {
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun daysInMonth(jy: Int, jm: Int): Int {
        if (jm in 1..6) return 31
        if (jm in 7..11) return 30
        return if (toMillis(jy + 1, 1, 1) - toMillis(jy, 1, 1) >= 366L * 24L * 60L * 60L * 1000L) 30 else 29
    }

    fun parse(value: String, fallbackMillis: Long = System.currentTimeMillis()): Long {
        val p = fromPersianDigits(value.trim()).replace('-', '/').split('/')
        if (p.size != 3) return fallbackMillis
        val jy = p[0].toIntOrNull() ?: return fallbackMillis
        val jm = p[1].toIntOrNull() ?: return fallbackMillis
        val jd = p[2].toIntOrNull() ?: return fallbackMillis
        if (jm !in 1..12 || jd !in 1..31) return fallbackMillis
        val g = jalaliToGregorian(jy, jm, jd)
        return GregorianCalendar(g[0], g[1] - 1, g[2], 12, 0, 0).apply {
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): IntArray {
        var gy2 = gy - 1600
        val gm2 = gm - 1
        val gd2 = gd - 1
        val gDays = intArrayOf(0,31,59,90,120,151,181,212,243,273,304,334)
        var days = 365 * gy2 + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400
        days += gDays[gm2] + gd2
        if (gm2 > 1 && (gy % 4 == 0 && (gy % 100 != 0 || gy % 400 == 0))) days++
        var jDays = days - 79
        val jNp = jDays / 12053
        jDays %= 12053
        var jy = 979 + 33 * jNp + 4 * (jDays / 1461)
        jDays %= 1461
        if (jDays >= 366) {
            jy += (jDays - 1) / 365
            jDays = (jDays - 1) % 365
        }
        val jm = if (jDays < 186) 1 + jDays / 31 else 7 + (jDays - 186) / 30
        val jd = 1 + if (jDays < 186) jDays % 31 else (jDays - 186) % 30
        return intArrayOf(jy, jm, jd)
    }

    private fun jalaliToGregorian(jy: Int, jm: Int, jd: Int): IntArray {
        var jy2 = jy - 979
        var days = 365 * jy2 + (jy2 / 33) * 8 + ((jy2 % 33) + 3) / 4
        days += if (jm < 7) (jm - 1) * 31 else (jm - 1) * 30 + 6
        days += jd - 1
        var gy = 1600 + 400 * (days / 146097)
        days %= 146097
        var leap = true
        if (days >= 36525) {
            days--
            gy += 100 * (days / 36524)
            days %= 36524
            if (days >= 365) days++
            else leap = false
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days >= 366) {
            leap = false
            days--
            gy += days / 365
            days %= 365
        }
        var gd = days + 1
        val monthDays = intArrayOf(31, if (leap) 29 else 28,31,30,31,30,31,31,30,31,30,31)
        var gm = 0
        while (gm < 12 && gd > monthDays[gm]) {
            gd -= monthDays[gm]
            gm++
        }
        return intArrayOf(gy, gm + 1, gd)
    }
}
