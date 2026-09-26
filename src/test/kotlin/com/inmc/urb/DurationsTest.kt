package com.inmc.urb

import kr.inmc.core.util.Durations
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DurationsTest {

    @Test
    fun `parses the full 1d 1h 1m 1s form`() {
        assertEquals(90061L, Durations.parse("1d 1h 1m 1s"))
    }

    @Test
    fun `round trips through format`() {
        val seconds = 90061L
        assertEquals("1d 1h 1m 1s", Durations.format(seconds))
        assertEquals(seconds, Durations.parse(Durations.format(seconds)))
    }

    @Test
    fun `accepts partial and unordered input`() {
        assertEquals(172800L, Durations.parse("2d"))
        assertEquals(5400L, Durations.parse("90m"))
        assertEquals(3661L, Durations.parse("1s 1m 1h"))
        assertEquals(3600L, Durations.parse("1H"))
        assertEquals(7200L, Durations.parse("1h1h"))
    }

    @Test
    fun `a bare number is read as seconds`() {
        assertEquals(45L, Durations.parse("45"))
        assertEquals(0L, Durations.parse("0"))
    }

    @Test
    fun `falls back when the input is unusable`() {
        assertEquals(600L, Durations.parse(null, 600L))
        assertEquals(600L, Durations.parse("", 600L))
        assertEquals(600L, Durations.parse("   ", 600L))
        assertEquals(600L, Durations.parse("나중에", 600L))
        assertEquals(600L, Durations.parse("-30", 600L))
    }

    @Test
    fun `formats zero and short spans`() {
        assertEquals("0s", Durations.format(0L))
        assertEquals("0s", Durations.format(-5L))
        assertEquals("30s", Durations.format(30L))
        assertEquals("1h", Durations.format(3600L))
        assertEquals("2d 3h", Durations.format(2 * 86400L + 3 * 3600L))
    }

    @Test
    fun `short form is korean`() {
        assertEquals("0초", Durations.formatShort(0L))
        assertEquals("1분 30초", Durations.formatShort(90L))
        assertEquals("1일 1시간 1분 1초", Durations.formatShort(90061L))
    }
}
