package com.inmc.urb

import kr.inmc.core.util.Numbers
import com.inmc.urb.util.Ph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec §96 names its placeholders in Korean; English aliases resolve to the same values. */
class PlaceholderTest {

    @Test
    fun `korean tokens are replaced`() {
        val text = Ph.of()
            .player("스티브")
            .box("보급 상자")
            .key("황금 열쇠")
            .money("1,000")
            .location("world", 100, 64, -200)
            .apply("{플레이어네임}님이 {상자이름}을 {열쇠이름}로 열었습니다. 비용 {필요한돈}원, 좌표 {좌표}")

        assertEquals(
            "스티브님이 보급 상자을 황금 열쇠로 열었습니다. 비용 1,000원, 좌표 world 100, 64, -200",
            text,
        )
    }

    @Test
    fun `english aliases resolve to the same values`() {
        val ph = Ph.of().player("Alex").box("Supply").key("Key").money("500")
        assertEquals("Alex Supply Key 500", ph.apply("{player} {box} {key} {money}"))
    }

    /** The previous plugin used {name} for the box display name. */
    @Test
    fun `legacy name token still maps to the box`() {
        assertEquals("보급 상자", Ph.of().box("보급 상자").apply("{name}"))
    }

    /** A queued spawn has no Y yet, so it renders as a question mark rather than a lie. */
    @Test
    fun `unknown y renders as a question mark`() {
        val ph = Ph.of().location("world", 10, null, 20)
        assertEquals("world 10, ?, 20", ph.apply("{좌표}"))
        assertEquals("?", ph.apply("{y}"))
        assertEquals("10 20", ph.apply("{x} {z}"))
    }

    @Test
    fun `unknown tokens are left alone`() {
        assertEquals("{알수없음}", Ph.of().player("x").apply("{알수없음}"))
    }

    @Test
    fun `copy does not share state`() {
        val original = Ph.of().box("A")
        val copy = original.copy().box("B")
        assertEquals("A", original.apply("{상자이름}"))
        assertEquals("B", copy.apply("{상자이름}"))
    }

    @Test
    fun `chance formatting keeps two decimals without trailing zeros`() {
        assertEquals("50", Numbers.chance(50.0))
        assertEquals("12.34", Numbers.chance(12.34))
        assertEquals("0.01", Numbers.chance(0.01))
        assertEquals("7.5", Numbers.chance(7.5))
    }

    @Test
    fun `chance is clamped into the configurable range`() {
        assertEquals(100.0, Numbers.clampChance(150.0))
        assertEquals(0.01, Numbers.clampChance(0.0))
        assertEquals(0.01, Numbers.clampChance(-4.0))
        assertTrue(Numbers.clampChance(33.333) == 33.33)
    }
}
