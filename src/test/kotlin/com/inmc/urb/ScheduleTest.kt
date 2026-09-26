package com.inmc.urb

import com.inmc.urb.box.BoxSchedule
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The spawn window. The midnight-wrapping case is the one worth pinning down: an evening event
 * configured as 22:00~02:00 has an end *earlier* than its start, and a naive range check would
 * report it closed for the entire day.
 */
class ScheduleTest {

    private fun at(day: DayOfWeek, hour: Int, minute: Int = 0): ZonedDateTime {
        // 2024-01-01 was a Monday, so adding the ordinal lands on the requested weekday.
        val date = LocalDateTime.of(2024, 1, 1 + day.ordinal, hour, minute)
        return ZonedDateTime.of(date, ZoneId.systemDefault())
    }

    @Test
    fun `a disabled schedule allows everything`() {
        val schedule = BoxSchedule(enabled = false, days = mutableSetOf(), from = LocalTime.NOON, to = LocalTime.NOON)
        assertTrue(schedule.allows(at(DayOfWeek.WEDNESDAY, 3)))
        assertEquals("항상", schedule.describe())
    }

    @Test
    fun `a same-day window includes both ends`() {
        val schedule = BoxSchedule(
            enabled = true,
            days = DayOfWeek.entries.toMutableSet(),
            from = LocalTime.of(20, 0),
            to = LocalTime.of(22, 0),
        )
        assertFalse(schedule.allows(at(DayOfWeek.MONDAY, 19, 59)))
        assertTrue(schedule.allows(at(DayOfWeek.MONDAY, 20, 0)))
        assertTrue(schedule.allows(at(DayOfWeek.MONDAY, 21, 30)))
        assertTrue(schedule.allows(at(DayOfWeek.MONDAY, 22, 0)))
        assertFalse(schedule.allows(at(DayOfWeek.MONDAY, 22, 1)))
    }

    @Test
    fun `a window whose end is earlier than its start wraps past midnight`() {
        val schedule = BoxSchedule(
            enabled = true,
            days = DayOfWeek.entries.toMutableSet(),
            from = LocalTime.of(22, 0),
            to = LocalTime.of(2, 0),
        )
        assertTrue(schedule.allows(at(DayOfWeek.FRIDAY, 23, 30)), "자정 전")
        assertTrue(schedule.allows(at(DayOfWeek.FRIDAY, 1, 0)), "자정 후")
        assertFalse(schedule.allows(at(DayOfWeek.FRIDAY, 12, 0)), "낮에는 닫혀 있어야 합니다")
    }

    @Test
    fun `days filter independently of the time window`() {
        val schedule = BoxSchedule(
            enabled = true,
            days = mutableSetOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            from = LocalTime.MIN,
            to = LocalTime.MAX,
        )
        assertTrue(schedule.allows(at(DayOfWeek.SATURDAY, 12)))
        assertTrue(schedule.allows(at(DayOfWeek.SUNDAY, 12)))
        assertFalse(schedule.allows(at(DayOfWeek.MONDAY, 12)))
        assertEquals("주말 00:00~23:59", schedule.describe())
    }

    @Test
    fun `an empty day set blocks everything rather than allowing it`() {
        val schedule = BoxSchedule(enabled = true, days = mutableSetOf())
        DayOfWeek.entries.forEach { assertFalse(schedule.allows(at(it, 12))) }
    }

    @Test
    fun `time parsing clamps rather than throwing`() {
        assertEquals(LocalTime.of(23, 59), BoxSchedule.parseTime("99:99", LocalTime.NOON))
        assertEquals(LocalTime.of(7, 0), BoxSchedule.parseTime("7", LocalTime.NOON))
        assertEquals(LocalTime.NOON, BoxSchedule.parseTime("abc", LocalTime.NOON))
        assertEquals(LocalTime.NOON, BoxSchedule.parseTime(null, LocalTime.NOON))
    }

    @Test
    fun `weekday and everyday shorthands are recognised in the summary`() {
        val everyday = BoxSchedule(enabled = true, days = DayOfWeek.entries.toMutableSet())
        assertTrue(everyday.describe().startsWith("매일"))

        val weekdays = BoxSchedule(
            enabled = true,
            days = mutableSetOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ),
        )
        assertTrue(weekdays.describe().startsWith("평일"))
    }
}
