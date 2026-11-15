package com.voicepilot.core.time

import com.voicepilot.core.text.NumberWords
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Source of the current time; injectable for tests. */
interface Clock {
    fun now(): Instant
    fun zone(): ZoneId
    fun localNow(): LocalDateTime = LocalDateTime.ofInstant(now(), zone())

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun now(): Instant = Instant.now()
            override fun zone(): ZoneId = ZoneId.systemDefault()
        }

        fun fixed(dateTime: LocalDateTime, zone: ZoneId = ZoneId.of("Africa/Lagos")): Clock = object : Clock {
            override fun now(): Instant = dateTime.atZone(zone).toInstant()
            override fun zone(): ZoneId = zone
        }
    }
}

/**
 * Parses spoken English time expressions ("7am", "half past six", "in 20 minutes",
 * "tomorrow at 9", "on Friday at 3pm") into java.time values. Input should be
 * lowercase; punctuation other than ':' and '.' is ignored.
 */
class TimeExpressionParser(private val clock: Clock) {

    private val num = NumberWords.PATTERN

    private val clockTime = Regex(
        "\\b(\\d{1,2})(?::|\\.)(\\d{2})\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?![\\d])",
    )
    private val hourMeridiem = Regex("\\b($num)\\s*(?:o'?clock\\s*)?(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])")
    private val wordHourMinute = Regex(
        "\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\s+" +
            "(fifteen|thirty|forty[ -]five|o[ -]five|o[ -]clock)\\s*(a\\.?m\\.?|p\\.?m\\.?)?",
    )
    private val halfPast = Regex("\\b(half|quarter) (past|to) ($num)\\b\\s*(a\\.?m\\.?|p\\.?m\\.?)?")
    private val bareHour = Regex("\\b(?:at|for|by|around)\\s+($num)(?:\\s*o'?clock)?\\b(?!\\s*(?:minute|min|hour|hr|second|sec|day))")
    private val durationPart = Regex(
        "\\b($num|\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b",
    )

    fun parseTimeOfDay(text: String, preferMorning: Boolean = false, sameDay: Boolean = true): LocalTime? {
        val t = text.lowercase()
        this.sameDay = sameDay
        when {
            Regex("\\bnoon\\b|\\bmidday\\b").containsMatchIn(t) -> return LocalTime.NOON
            Regex("\\bmidnight\\b").containsMatchIn(t) -> return LocalTime.MIDNIGHT
        }
        clockTime.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (min > 59) return null
            return resolve(h, min, m.groupValues[3], t, preferMorning)
        }
        wordHourMinute.find(t)?.let { m ->
            val h = NumberWords.parse(m.groupValues[1]) ?: return null
            val minWord = m.groupValues[2].replace('-', ' ')
            val min = when {
                minWord.startsWith("o clock") || minWord == "o'clock" -> 0
                minWord.startsWith("o ") -> NumberWords.parse(minWord.removePrefix("o ")) ?: 0
                else -> NumberWords.parse(minWord) ?: 0
            }
            return resolve(h, min, m.groupValues[3], t, preferMorning)
        }
        halfPast.find(t)?.let { m ->
            val base = NumberWords.parse(m.groupValues[3]) ?: return null
            val delta = if (m.groupValues[1] == "half") 30 else 15
            val (h, min) = if (m.groupValues[2] == "past") base to delta else (base - 1).floorMod(12) to 60 - delta
            return resolve(if (h == 0) 12 else h, min, m.groupValues[4], t, preferMorning)
        }
        hourMeridiem.find(t)?.let { m ->
            val h = NumberWords.parse(m.groupValues[1]) ?: return null
            return resolve(h, 0, m.groupValues[2], t, preferMorning)
        }
        bareHour.find(t)?.let { m ->
            val h = NumberWords.parse(m.groupValues[1]) ?: return null
            if (h > 24) return null
            return resolve(h, 0, "", t, preferMorning)
        }
        return null
    }

    private var sameDay = true

    private fun Int.floorMod(m: Int) = Math.floorMod(this, m)

    private fun resolve(hour: Int, minute: Int, meridiemRaw: String, context: String, preferMorning: Boolean): LocalTime? {
        if (hour !in 0..24 || minute !in 0..59) return null
        val meridiem = meridiemRaw.replace(".", "")
        val h = when {
            meridiem == "am" -> if (hour == 12) 0 else hour
            meridiem == "pm" -> if (hour == 12) 12 else hour + 12
            hour >= 13 || hour == 0 -> hour % 24
            Regex("\\b(tonight|evening|afternoon)\\b").containsMatchIn(context) && hour < 12 -> hour + 12
            Regex("\\bmorning\\b").containsMatchIn(context) -> hour % 12
            preferMorning -> hour % 12
            else -> AMBIGUOUS_FLAG + hour
        }
        if (h >= AMBIGUOUS_FLAG) {
            // No am/pm: choose the next occurrence of h or h+12 after now.
            val base = h - AMBIGUOUS_FLAG
            if (!sameDay) {
                // Another day: typical waking-hours heuristic (7-11 morning, 12-6 afternoon/evening).
                return if (base in 7..11) LocalTime.of(base, minute) else LocalTime.of(base % 12 + 12, minute)
            }
            val now = clock.localNow().toLocalTime()
            val am = LocalTime.of(base % 12, minute)
            val pm = LocalTime.of(base % 12 + 12, minute)
            return listOf(am, pm).firstOrNull { it.isAfter(now) } ?: am
        }
        if (h > 23) return null
        return LocalTime.of(h, minute)
    }

    /** "10 minutes", "an hour and a half", "1 hour 30 minutes", "half an hour". */
    fun parseDuration(text: String): Duration? {
        var t = text.lowercase()
        var total = Duration.ZERO
        var matched = false
        val halfHour = Regex("\\bhalf an? hour\\b")
        if (halfHour.containsMatchIn(t)) {
            total = total.plusMinutes(30); matched = true
            t = t.replace(halfHour, " ")
        }
        for (m in durationPart.findAll(t)) {
            val amountText = m.groupValues[1]
            val amount = amountText.toDoubleOrNull() ?: NumberWords.parse(amountText)?.toDouble() ?: continue
            val unit = m.groupValues[2]
            val seconds = when {
                unit.startsWith("h") -> amount * 3600
                unit.startsWith("m") -> amount * 60
                else -> amount
            }
            total = total.plusSeconds(seconds.toLong()); matched = true
        }
        if (matched && Regex("\\b(?:hours?|hour) and a half\\b").containsMatchIn(t)) total = total.plusMinutes(30)
        return if (matched && !total.isZero) total else null
    }

    /**
     * Resolves a date-time expression relative to now. Returns null if no time
     * information was found. A date without a time defaults to 09:00.
     */
    fun parseDateTime(text: String): LocalDateTime? {
        val t = text.lowercase()
        val now = clock.localNow().withSecond(0).withNano(0)
        Regex("\\bin\\s+(.+)").find(t)?.let { m ->
            parseDuration(m.groupValues[1])?.let { return now.plus(it) }
        }
        val date: LocalDate? = parseDate(t, now.toLocalDate())
        val time = parseTimeOfDay(stripDateWords(t), sameDay = date == null || date == now.toLocalDate()) ?: when {
            Regex("\\btonight\\b").containsMatchIn(t) -> LocalTime.of(20, 0)
            Regex("\\bthis evening\\b|\\bevening\\b").containsMatchIn(t) -> LocalTime.of(18, 0)
            Regex("\\bafternoon\\b").containsMatchIn(t) -> LocalTime.of(14, 0)
            Regex("\\bmorning\\b").containsMatchIn(t) -> LocalTime.of(9, 0)
            date != null -> LocalTime.of(9, 0)
            else -> null
        } ?: return null
        if (date != null) return LocalDateTime.of(date, time)
        val today = LocalDateTime.of(now.toLocalDate(), time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun stripDateWords(t: String): String =
        t.replace(Regex("\\b(?:on\\s+)?(?:next\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"), " ")

    private fun parseDate(t: String, today: LocalDate): LocalDate? {
        if (Regex("\\bday after tomorrow\\b").containsMatchIn(t)) return today.plusDays(2)
        if (Regex("\\btomorrow\\b").containsMatchIn(t)) return today.plusDays(1)
        if (Regex("\\b(today|tonight|this (morning|afternoon|evening))\\b").containsMatchIn(t)) return today
        val dow = Regex("\\b(next\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").find(t)
        if (dow != null) {
            val day = DayOfWeek.valueOf(dow.groupValues[2].uppercase())
            val next = today.with(TemporalAdjusters.next(day))
            return next
        }
        return null
    }

    private companion object {
        const val AMBIGUOUS_FLAG = 100
    }
}
