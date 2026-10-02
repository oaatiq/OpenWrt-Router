package io.wrtpilot.core.domain

import java.util.Locale

/**
 * Weekly "allowed internet hours" as a 7 × 24 grid of hours, convertible to
 * and from the router's schedule rules ("mon,tue 17:00-21:00").
 *
 * Days are indexed with ISO numbering minus one: 0 = Monday … 6 = Sunday.
 * The UI decides the display order (first day of week depends on locale).
 */
class ScheduleGrid private constructor(private val cells: BooleanArray) {

    fun isAllowed(day: Int, hour: Int): Boolean = cells[index(day, hour)]

    fun with(day: Int, hour: Int, allowed: Boolean): ScheduleGrid {
        if (isAllowed(day, hour) == allowed) return this
        val copy = cells.copyOf()
        copy[index(day, hour)] = allowed
        return ScheduleGrid(copy)
    }

    fun withDay(day: Int, allowed: Boolean): ScheduleGrid {
        val copy = cells.copyOf()
        for (h in 0 until HOURS) copy[index(day, h)] = allowed
        return ScheduleGrid(copy)
    }

    /** Copies one day's hours to other days. */
    fun copyDay(from: Int, to: Collection<Int>): ScheduleGrid {
        val copy = cells.copyOf()
        for (d in to) for (h in 0 until HOURS) copy[index(d, h)] = cells[index(from, h)]
        return ScheduleGrid(copy)
    }

    val allowedHours: Int get() = cells.count { it }
    val isEmpty: Boolean get() = allowedHours == 0
    val isFull: Boolean get() = allowedHours == DAYS * HOURS

    fun allowedHoursOn(day: Int): Int = (0 until HOURS).count { isAllowed(day, it) }

    /** Contiguous allowed ranges of one day as [start, end) hours. */
    fun ranges(day: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        for (h in 0..HOURS) {
            val on = h < HOURS && isAllowed(day, h)
            if (on && start < 0) start = h
            if (!on && start >= 0) {
                out += start until h
                start = -1
            }
        }
        return out
    }

    /**
     * Router rules, one per distinct time range, days merged:
     * Mon–Fri 17–21 => "mon,tue,wed,thu,fri 17:00-21:00".
     */
    fun toRules(): List<String> {
        val byRange = LinkedHashMap<Pair<Int, Int>, MutableList<Int>>()
        for (d in 0 until DAYS) {
            for (r in ranges(d)) byRange.getOrPut(r.first to r.last + 1) { mutableListOf() } += d
        }
        return byRange.entries
            .sortedWith(compareBy({ it.value.first() }, { it.key.first }))
            .map { (range, days) ->
                val dayList = days.joinToString(",") { DAY_NAMES[it] }
                // wire format: never localise digits (Arabic locales would emit ١٧:٠٠)
                String.format(Locale.ROOT, "%s %02d:00-%02d:00", dayList, range.first, range.second)
            }
    }

    override fun equals(other: Any?): Boolean = other is ScheduleGrid && cells.contentEquals(other.cells)

    override fun hashCode(): Int = cells.contentHashCode()

    override fun toString(): String = "ScheduleGrid(${toRules()})"

    companion object {
        const val DAYS = 7
        const val HOURS = 24

        /** Router day names in ISO order (Monday first). */
        val DAY_NAMES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        private fun index(day: Int, hour: Int): Int {
            require(day in 0 until DAYS && hour in 0 until HOURS) { "bad cell $day/$hour" }
            return day * HOURS + hour
        }

        fun empty(): ScheduleGrid = ScheduleGrid(BooleanArray(DAYS * HOURS))

        fun full(): ScheduleGrid = ScheduleGrid(BooleanArray(DAYS * HOURS) { true })

        /** School nights: weekdays 7–8 and 16–21, weekend 9–22. */
        fun schoolNights(): ScheduleGrid {
            var g = empty()
            for (d in 0..4) {
                g = g.with(d, 7, true)
                for (h in 16 until 21) g = g.with(d, h, true)
            }
            for (d in 5..6) for (h in 9 until 22) g = g.with(d, h, true)
            return g
        }

        /** Daytime only: every day 8–21 (no internet at night). */
        fun daytime(): ScheduleGrid {
            var g = empty()
            for (d in 0 until DAYS) for (h in 8 until 21) g = g.with(d, h, true)
            return g
        }

        /**
         * Parses router rules. Returns null when a rule cannot be shown on an
         * hour grid (minutes other than :00, unknown syntax); the UI then keeps
         * the rules as they are unless the user starts over.
         */
        fun fromRules(rules: List<String>): ScheduleGrid? {
            val cells = BooleanArray(DAYS * HOURS)
            for (rule in rules) {
                val parsed = parseRule(rule) ?: return null
                for (day in parsed.days) {
                    var h = parsed.startHour
                    while (h < parsed.endHour) {
                        val d = (day + h / HOURS) % DAYS
                        cells[d * HOURS + h % HOURS] = true
                        h++
                    }
                }
            }
            return ScheduleGrid(cells)
        }

        private class Rule(val days: List<Int>, val startHour: Int, val endHour: Int)

        private fun parseRule(rule: String): Rule? {
            val parts = rule.trim().lowercase().split(Regex("\\s+"))
            if (parts.size != 2) return null
            val days = parseDays(parts[0]) ?: return null
            val times = parts[1].split('-')
            if (times.size != 2) return null
            val start = parseHour(times[0]) ?: return null
            var end = parseHour(times[1]) ?: return null
            if (start >= HOURS) return null
            if (end <= start) end += HOURS
            return Rule(days, start, end)
        }

        private fun parseHour(s: String): Int? {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").find(s) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (min != 0 || h > 24) return null
            return h
        }

        private fun parseDays(s: String): List<Int>? {
            when (s) {
                "daily", "everyday", "all" -> return (0 until DAYS).toList()
                "weekdays" -> return (0..4).toList()
                "weekend" -> return listOf(5, 6)
            }
            val out = mutableListOf<Int>()
            for (part in s.split(',')) {
                val r = part.split('-')
                when (r.size) {
                    1 -> out += DAY_NAMES.indexOf(r[0]).takeIf { it >= 0 } ?: return null
                    2 -> {
                        var a = DAY_NAMES.indexOf(r[0])
                        val b = DAY_NAMES.indexOf(r[1])
                        if (a < 0 || b < 0) return null
                        while (true) {
                            out += a
                            if (a == b) break
                            a = (a + 1) % DAYS
                        }
                    }
                    else -> return null
                }
            }
            return out.distinct()
        }
    }
}
