package com.inmc.urb

import com.inmc.urb.box.LootRoller
import com.inmc.urb.box.RollForecast
import com.inmc.urb.box.Weighted
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The forecast is shown in the settings menu as fact, so it has to agree with what the roller
 * actually does - every case here cross-checks the analytic result against a real run.
 */
class RollForecastTest {

    private data class Entry(val name: String, override val chance: Double) : Weighted

    private fun measure(chances: List<Double>, minRolls: Int, maxRolls: Int, trials: Int = 200_000): DoubleArray {
        val entries = chances.mapIndexed { index, chance -> Entry("e$index", chance) }
        val rng = Random(20260826L)
        val counts = LongArray(chances.size + 1)
        repeat(trials) { counts[LootRoller.select(entries, minRolls, maxRolls, rng).size]++ }
        return DoubleArray(counts.size) { counts[it].toDouble() / trials }
    }

    private fun assertMatches(chances: List<Double>, minRolls: Int, maxRolls: Int) {
        val predicted = RollForecast.distribution(chances, minRolls, maxRolls)
        val measured = measure(chances, minRolls, maxRolls)
        for (count in predicted.indices) {
            assertTrue(
                Math.abs(predicted[count] - measured[count]) < 0.006,
                "개수 ${count}개 예측 ${predicted[count]} vs 실측 ${measured[count]} (확률 $chances, $minRolls~$maxRolls)",
            )
        }
    }

    @Test
    fun `forecast matches the roller for a uniform table`() {
        assertMatches(List(3) { 50.0 }, 1, 3)
        assertMatches(List(4) { 50.0 }, 1, 3)
        assertMatches(List(6) { 50.0 }, 1, 3)
        assertMatches(List(10) { 50.0 }, 1, 3)
    }

    @Test
    fun `forecast matches the roller for a tiered table`() {
        val tiered = listOf(80.0, 70.0, 65.0, 45.0, 30.0, 15.0, 5.0, 1.0, 0.1)
        assertMatches(tiered, 1, 3)
        assertMatches(tiered, 1, 5)
        assertMatches(tiered, 2, 4)
    }

    @Test
    fun `forecast matches when the cap exceeds the table size`() {
        assertMatches(listOf(100.0, 60.0, 30.0), 1, 5)
    }

    /** Probabilities must be a distribution, whatever the inputs. */
    @Test
    fun `forecast always sums to one`() {
        listOf(
            List(1) { 100.0 },
            List(5) { 0.01 },
            List(20) { 90.0 },
            listOf(50.0, 50.0),
        ).forEach { chances ->
            val sum = RollForecast.distribution(chances, 1, 3).sum()
            assertTrue(Math.abs(sum - 1.0) < 1e-9, "합이 1이 아닙니다: $sum")
        }
    }

    /** Nothing below the floor or above the ceiling may carry probability. */
    @Test
    fun `forecast respects the floor and the ceiling`() {
        val forecast = RollForecast.distribution(List(10) { 40.0 }, minRolls = 2, maxRolls = 4)
        assertEquals(0.0, forecast[0])
        assertEquals(0.0, forecast[1])
        assertTrue(forecast.drop(5).all { it == 0.0 })
        assertTrue(forecast[2] > 0.0 && forecast[3] > 0.0 && forecast[4] > 0.0)
    }

    /** The exact case the admin hit: three 50% rewards reach the cap one open in eight. */
    @Test
    fun `three fifty percent rewards land on three about one in eight`() {
        val forecast = RollForecast.distribution(List(3) { 50.0 }, 1, 3)
        assertTrue(Math.abs(forecast[3] - 0.125) < 1e-9, "3개 확률이 12.5%가 아닙니다: ${forecast[3]}")
        assertTrue(Math.abs(forecast[2] - 0.375) < 1e-9)
        // 0 winners is topped up to 1, so 1개 = 12.5% + 37.5%
        assertTrue(Math.abs(forecast[1] - 0.5) < 1e-9)
    }

    /** A cap above the table size is silently clamped - the menu warns about exactly this. */
    @Test
    fun `effective max is clamped to the reward count`() {
        assertEquals(3, RollForecast.effectiveMax(rewardCount = 3, maxRolls = 5))
        assertEquals(5, RollForecast.effectiveMax(rewardCount = 10, maxRolls = 5))
        assertEquals(0, RollForecast.effectiveMax(rewardCount = 0, maxRolls = 5))
        assertTrue(RollForecast.isMaxClamped(3, 5))
        assertTrue(!RollForecast.isMaxClamped(10, 5))
        assertTrue(!RollForecast.isMaxClamped(5, 5))
    }

    @Test
    fun `truncation rate matches the roller`() {
        val chances = List(20) { 50.0 }
        val predicted = RollForecast.truncationRate(chances, maxRolls = 3)

        val entries = chances.mapIndexed { index, chance -> Entry("e$index", chance) }
        val rng = Random(99)
        var truncated = 0
        val trials = 100_000
        repeat(trials) {
            if (LootRoller.selectDetailed(entries, 1, 3, rng).rawWinners > 3) truncated++
        }
        val measured = truncated * 100.0 / trials
        assertTrue(Math.abs(predicted - measured) < 0.5, "예측 $predicted% vs 실측 $measured%")
    }

    @Test
    fun `expected winners equals the sum of the chances`() {
        assertEquals(2.75, RollForecast.expectedWinners(listOf(80.0, 70.0, 65.0, 60.0)), 1e-9)
        assertEquals(0.0, RollForecast.expectedWinners(emptyList()), 1e-9)
    }
}
