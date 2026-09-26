package com.inmc.urb

import com.inmc.urb.box.LootRoller
import com.inmc.urb.box.Weighted
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spec (§64-68) states the rule through two worked examples, and both have to hold at
 * the same time. These tests encode them directly.
 */
class LootRollerTest {

    private data class Entry(val name: String, override val chance: Double) : Weighted

    private fun table(size: Int, chance: Double) = (1..size).map { Entry("item$it", chance) }

    /**
     * "54개를 등록하고 최대갯수 2개를 설정하면 1~2개 사이로" - never 0, never 3.
     *
     * Which end of that range you land on is decided by the chances: at 2% apiece most rolls
     * produce zero or one winner, so both outcomes show up.
     */
    @Test
    fun `54 rewards capped at 2 always yields between 1 and 2`() {
        val rng = Random(1234)
        val entries = table(54, 2.0)
        val counts = IntArray(60)

        repeat(100_000) {
            val size = LootRoller.select(entries, minRolls = 1, maxRolls = 2, rng = rng).size
            counts[size]++
        }

        assertEquals(0, counts[0], "0개가 나오면 안 됩니다 - 최소 1개는 보장되어야 합니다")
        assertTrue(counts[1] > 0, "1개만 나오는 경우도 있어야 합니다")
        assertTrue(counts[2] > 0, "2개가 나오는 경우도 있어야 합니다")
        for (size in 3 until counts.size) {
            assertEquals(0, counts[size], "최대 개수(2)를 넘겨서는 안 됩니다")
        }
    }

    /** With generous chances the cap is what binds, and it must bind hard. */
    @Test
    fun `a generous table is still truncated to the cap`() {
        val rng = Random(4321)
        val entries = table(54, 50.0)
        repeat(20_000) {
            val size = LootRoller.select(entries, minRolls = 1, maxRolls = 2, rng = rng).size
            assertTrue(size in 1..2, "결과 개수가 1~2 범위를 벗어났습니다: $size")
        }
    }

    /**
     * "54개 등록 + 최대 54 → 전부 나오는게 아닌 확률에 따라 1개가 나올 수도 있어야 되며"
     * With a low chance the result must still spread across the whole range.
     */
    @Test
    fun `54 rewards capped at 54 spreads across the range and never returns zero`() {
        val rng = Random(9876)
        val entries = table(54, 3.0)
        var min = Int.MAX_VALUE
        var max = 0

        repeat(100_000) {
            val size = LootRoller.select(entries, minRolls = 1, maxRolls = 54, rng = rng).size
            assertTrue(size in 1..54, "결과 개수가 1~54 범위를 벗어났습니다: $size")
            if (size < min) min = size
            if (size > max) max = size
        }

        assertEquals(1, min, "확률이 낮으면 1개만 나오는 경우가 있어야 합니다")
        assertTrue(max >= 5, "확률에 따라 여러 개가 함께 나오기도 해야 합니다 (최대 관측 $max)")
    }

    /** A 100% table capped at 54 hands out everything. */
    @Test
    fun `guaranteed rewards all drop when the cap allows it`() {
        val rng = Random(5)
        val entries = table(10, 100.0)
        repeat(1_000) {
            assertEquals(10, LootRoller.select(entries, 1, 54, rng).size)
        }
    }

    /** Even a table where nothing can realistically win still yields the floor. */
    @Test
    fun `minimum is honoured when nothing wins its roll`() {
        val rng = Random(42)
        val entries = table(5, 0.01)
        repeat(10_000) {
            val result = LootRoller.select(entries, minRolls = 2, maxRolls = 3, rng = rng)
            assertTrue(result.size >= 2, "최소 개수 2가 지켜지지 않았습니다: ${result.size}")
            assertTrue(result.size <= 3)
            assertEquals(result.size, result.distinct().size, "같은 보상이 중복 선택되면 안 됩니다")
        }
    }

    /** Selection must never return the same entry twice. */
    @Test
    fun `results are distinct`() {
        val rng = Random(7)
        val entries = table(20, 90.0)
        repeat(5_000) {
            val result = LootRoller.select(entries, 1, 20, rng)
            assertEquals(result.size, result.distinct().size)
        }
    }

    @Test
    fun `empty table yields nothing`() {
        assertTrue(LootRoller.select(emptyList<Entry>(), 1, 5, Random()).isEmpty())
    }

    /** A cap larger than the table is clamped rather than looping forever. */
    @Test
    fun `cap larger than the table is clamped`() {
        val rng = Random(3)
        val entries = table(3, 100.0)
        repeat(1_000) {
            assertEquals(3, LootRoller.select(entries, minRolls = 10, maxRolls = 54, rng = rng).size)
        }
    }

    /**
     * With the cap out of the way, each reward's observed frequency must match its configured
     * chance. Twenty 50% fillers make "nothing won" effectively impossible (2^-20), so the
     * guaranteed-minimum top-up never fires and does not skew the measurement.
     */
    @Test
    fun `frequency follows the configured chance`() {
        val rng = Random(2024)
        val rare = Entry("rare", 5.0)
        val common = Entry("common", 90.0)
        val entries = table(20, 50.0) + rare + common

        var rareHits = 0
        var commonHits = 0
        val trials = 200_000
        repeat(trials) {
            val result = LootRoller.select(entries, minRolls = 1, maxRolls = entries.size, rng = rng)
            if (result.contains(rare)) rareHits++
            if (result.contains(common)) commonHits++
        }

        val rareRate = rareHits * 100.0 / trials
        val commonRate = commonHits * 100.0 / trials
        assertTrue(rareRate in 4.5..5.5, "희귀 아이템 실제 확률이 예상 범위를 벗어났습니다: $rareRate%")
        assertTrue(commonRate in 89.5..90.5, "일반 아이템 실제 확률이 예상 범위를 벗어났습니다: $commonRate%")
    }

    /**
     * When the floor does engage it should favour the likelier reward - a 90% item is the
     * sensible consolation prize, not a 5% one.
     */
    @Test
    fun `the guaranteed minimum picks weighted by chance`() {
        val rng = Random(99)
        val rare = Entry("rare", 0.01)
        val common = Entry("common", 0.09)
        val entries = listOf(rare, common)

        var commonHits = 0
        val trials = 50_000
        repeat(trials) {
            // Chances this low mean almost every result comes from the top-up path.
            if (LootRoller.select(entries, 1, 1, rng).contains(common)) commonHits++
        }
        val rate = commonHits * 100.0 / trials
        assertTrue(rate in 85.0..95.0, "가중치 기반 보정 선택이 예상 범위를 벗어났습니다: $rate%")
    }

    /** Slots must be distinct and inside the 6x9 window (spec §67). */
    @Test
    fun `random slots are distinct and in range`() {
        val rng = Random(11)
        repeat(1_000) {
            val slots = LootRoller.randomSlots(20, 54, rng)
            assertEquals(20, slots.size)
            assertEquals(20, slots.distinct().size)
            assertTrue(slots.all { it in 0..53 })
        }
    }

    @Test
    fun `amount roll stays within bounds`() {
        val rng = Random(13)
        repeat(10_000) {
            assertTrue(LootRoller.rollAmount(2, 5, rng) in 2..5)
            assertEquals(7, LootRoller.rollAmount(7, 7, rng))
        }
    }
}
