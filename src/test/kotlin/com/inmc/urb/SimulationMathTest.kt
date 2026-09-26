package com.inmc.urb

import com.inmc.urb.box.LootRoller
import com.inmc.urb.box.Weighted
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulator's whole value is that its numbers can be trusted, so the quantities it
 * reports are checked here against the roller directly.
 */
class SimulationMathTest {

    private data class Entry(val name: String, override val chance: Double) : Weighted

    private fun table(size: Int, chance: Double) = (1..size).map { Entry("item$it", chance) }

    /** `rawWinners` must be the count before the cap, not after. */
    @Test
    fun `raw winner count is reported before truncation`() {
        val rng = Random(1)
        val entries = table(20, 100.0)
        repeat(500) {
            val selection = LootRoller.selectDetailed(entries, minRolls = 1, maxRolls = 3, rng = rng)
            assertEquals(20, selection.rawWinners, "모든 보상이 100%면 원래 당첨 수는 20이어야 합니다")
            assertEquals(3, selection.selected.size, "지급은 최대 3개로 잘려야 합니다")
        }
    }

    /** And before the floor top-up, so "nothing won" is visible. */
    @Test
    fun `raw winner count is reported before the floor tops up`() {
        val rng = Random(2)
        val entries = table(5, 0.01)
        var sawZero = false
        repeat(5_000) {
            val selection = LootRoller.selectDetailed(entries, minRolls = 2, maxRolls = 3, rng = rng)
            if (selection.rawWinners == 0) {
                sawZero = true
                assertEquals(2, selection.selected.size, "최소 보장이 2개를 채워야 합니다")
            }
        }
        assertTrue(sawZero, "확률이 0.01%면 아무것도 당첨되지 않는 경우가 관측되어야 합니다")
    }

    /** select() must stay a thin wrapper - the two entry points cannot diverge. */
    @Test
    fun `select and selectDetailed agree for the same seed`() {
        repeat(200) { seed ->
            val entries = table(12, 35.0)
            val a = LootRoller.select(entries, 1, 4, Random(seed.toLong()))
            val b = LootRoller.selectDetailed(entries, 1, 4, Random(seed.toLong())).selected
            assertEquals(a, b)
        }
    }

    /**
     * A run with a fixed seed must be reproducible, otherwise comparing "before and after a
     * chance change" is meaningless - the difference could just be noise.
     */
    @Test
    fun `a fixed seed reproduces the same run`() {
        val entries = table(15, 25.0)

        fun run(): List<Int> {
            val rng = Random(4242)
            return (1..2_000).map { LootRoller.selectDetailed(entries, 1, 3, rng).selected.size }
        }
        assertEquals(run(), run())
    }

    /**
     * Observed rate is pushed *below* the configured chance when the cap bites. This is the
     * number admins misread, so it is pinned down here.
     */
    @Test
    fun `truncation pushes observed rate below the configured chance`() {
        val rng = Random(7)
        val entries = table(20, 50.0)
        val hits = HashMap<String, Int>()
        val trials = 50_000

        repeat(trials) {
            LootRoller.selectDetailed(entries, 1, 2, rng).selected.forEach {
                hits.merge(it.name, 1, Int::plus)
            }
        }

        // 20 items at 50% cut down to 2 => each appears about 2/20 = 10% of opens.
        val observed = hits.values.map { it * 100.0 / trials }
        observed.forEach {
            assertTrue(it in 8.0..12.0, "잘림 후 실제 출현률이 예상 범위를 벗어났습니다: $it%")
        }
        assertTrue(observed.max() < 50.0, "설정 50%보다 실제 출현률이 낮아야 합니다")
    }

    /** With no cap pressure, observed rate should track the configured chance closely. */
    @Test
    fun `without truncation observed rate matches the configured chance`() {
        val rng = Random(11)
        val entries = table(20, 50.0)
        var hits = 0
        val trials = 50_000
        val target = entries.first()

        repeat(trials) {
            if (LootRoller.selectDetailed(entries, 1, entries.size, rng).selected.contains(target)) hits++
        }
        val observed = hits * 100.0 / trials
        assertTrue(observed in 49.0..51.0, "상한이 없으면 설정값과 일치해야 합니다: $observed%")
    }

    /** Average amount per open is what the throughput projection multiplies up. */
    @Test
    fun `amount rolls average to the midpoint of the range`() {
        val rng = Random(13)
        var total = 0L
        val trials = 200_000
        repeat(trials) { total += LootRoller.rollAmount(2, 8, rng) }
        val average = total.toDouble() / trials
        assertTrue(average in 4.9..5.1, "2~8 범위의 평균은 5 근처여야 합니다: $average")
    }

    /** The count distribution must sum back to the number of iterations. */
    @Test
    fun `count distribution accounts for every iteration`() {
        val rng = Random(17)
        val entries = table(10, 30.0)
        val distribution = LongArray(entries.size + 1)
        val trials = 20_000

        repeat(trials) {
            val size = LootRoller.selectDetailed(entries, 1, 4, rng).selected.size
            distribution[size]++
        }
        assertEquals(trials.toLong(), distribution.sum())
        assertEquals(0L, distribution[0], "최소 보장 때문에 0개는 나올 수 없습니다")
        assertTrue(distribution.drop(5).all { it == 0L }, "최대 4개를 넘을 수 없습니다")
    }
}
