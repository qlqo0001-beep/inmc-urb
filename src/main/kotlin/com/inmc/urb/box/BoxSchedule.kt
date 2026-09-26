package com.inmc.urb.box

import org.bukkit.configuration.ConfigurationSection
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Optional window during which a box is allowed to spawn automatically.
 *
 * Only the automatic schedule is gated - `/urb spawn`, `/urb spawnnow` and capsules still work
 * outside the window, because an admin placing a box by hand has already decided.
 *
 * A window whose end is earlier than its start wraps past midnight (22:00 ~ 02:00), which is
 * the common case for an evening event.
 */
class BoxSchedule(
    var enabled: Boolean = false,
    var days: MutableSet<DayOfWeek> = DayOfWeek.entries.toMutableSet(),
    var from: LocalTime = LocalTime.of(0, 0),
    var to: LocalTime = LocalTime.of(23, 59),
) {

    fun allows(now: ZonedDateTime): Boolean {
        if (!enabled) return true
        if (now.dayOfWeek !in days) return false

        val time = now.toLocalTime()
        return if (from <= to) {
            !time.isBefore(from) && !time.isAfter(to)
        } else {
            // wraps midnight
            !time.isBefore(from) || !time.isAfter(to)
        }
    }

    fun toggleDay(day: DayOfWeek) {
        if (!days.remove(day)) days.add(day)
    }

    fun describe(): String {
        if (!enabled) return "항상"
        val dayText = when {
            days.size == 7 -> "매일"
            days == WEEKEND -> "주말"
            days == WEEKDAYS -> "평일"
            days.isEmpty() -> "요일 없음"
            else -> DayOfWeek.entries.filter { it in days }.joinToString("") { KOREAN[it.ordinal] }
        }
        return "$dayText ${format(from)}~${format(to)}"
    }

    fun save(section: ConfigurationSection) {
        section.set("enabled", enabled)
        section.set("days", days.map { it.name })
        section.set("from", format(from))
        section.set("to", format(to))
    }

    private fun format(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    companion object {
        private val KOREAN = listOf("월", "화", "수", "목", "금", "토", "일")
        private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        private val WEEKDAYS = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )

        fun koreanDay(day: DayOfWeek): String = KOREAN[day.ordinal]

        fun parseTime(raw: String?, fallback: LocalTime): LocalTime {
            if (raw.isNullOrBlank()) return fallback
            val parts = raw.trim().split(':')
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return fallback
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return runCatching { LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)) }
                .getOrDefault(fallback)
        }

        fun load(section: ConfigurationSection?): BoxSchedule {
            if (section == null) return BoxSchedule()
            val days = section.getStringList("days")
                .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name.equals(name, true) } }
                .toMutableSet()
            return BoxSchedule(
                enabled = section.getBoolean("enabled", false),
                days = if (days.isEmpty()) DayOfWeek.entries.toMutableSet() else days,
                from = parseTime(section.getString("from"), LocalTime.of(0, 0)),
                to = parseTime(section.getString("to"), LocalTime.of(23, 59)),
            )
        }
    }
}
