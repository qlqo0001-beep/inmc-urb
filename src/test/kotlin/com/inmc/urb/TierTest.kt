package com.inmc.urb

import com.inmc.urb.box.BoxTier
import com.inmc.urb.box.LootRoller
import com.inmc.urb.box.Reward
import com.inmc.urb.box.TierForecast
import com.inmc.urb.box.buildRollGroups
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import org.bukkit.Material
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reward tiers, and the forecast that has to agree with them.
 *
 * The property that matters most is that a box with no tiers behaves exactly as it did before
 * tiers existed - the grouping is the code path every single open now goes through, so a
 * regression here would change every server's loot at once.
 */
class TierTest {

    private fun reward(name: String, chance: Double, tier: String? = null): Reward = Reward(
        id = name,
        item = StoredItem(ref = ItemRef.Vanilla(Material.DIAMOND), material = Material.DIAMOND),
        chance = chance,
        tier = tier,
    )

    // --- grouping --------------------------------------------------------------

    @Test
    fun `no tiers yields exactly one pool using the box range`() {
        val rewards = listOf(reward("a", 50.0), reward("b", 50.0))
        val groups = buildRollGroups(rewards, emptyMap(), minRolls = 1, maxRolls = 2)

        assertEquals(1, groups.size)
        assertNull(groups[0].tier)
        assertEquals(2, groups[0].entries.size)
        assertEquals(1, groups[0].minRolls)
        assertEquals(2, groups[0].maxRolls)
    }

    @Test
    fun `an empty reward list produces no pools at all`() {
        assertTrue(buildRollGroups(emptyList(), emptyMap(), 1, 2).isEmpty())
    }

    @Test
    fun `tiered and untiered rewards land in separate pools`() {
        val rewards = listOf(
            reward("common1", 80.0),
            reward("common2", 80.0),
            reward("rare", 5.0, tier = "희귀"),
        )
        val tiers = mapOf("희귀" to BoxTier("희귀", minRolls = 0, maxRolls = 1))
        val groups = buildRollGroups(rewards, tiers, minRolls = 2, maxRolls = 2)

        assertEquals(2, groups.size)
        assertNull(groups[0].tier)
        assertEquals(2, groups[0].entries.size)
        assertEquals("희귀", groups[1].tier)
        assertEquals(1, groups[1].entries.size)
        assertEquals(0, groups[1].minRolls)
    }

    /** Deleting a tier must not delete the loot that pointed at it. */
    @Test
    fun `a reward naming an unknown tier falls back to the default pool`() {
        val rewards = listOf(reward("orphan", 50.0, tier = "삭제된티어"))
        val tiers = mapOf("희귀" to BoxTier("희귀"))
        val groups = buildRollGroups(rewards, tiers, minRolls = 1, maxRolls = 1)

        assertEquals(1, groups.size)
        assertNull(groups[0].tier)
        assertEquals("orphan", groups[0].entries[0].id)
    }

    @Test
    fun `disabled and empty tiers are skipped`() {
        val rewards = listOf(reward("a", 50.0), reward("off", 50.0, tier = "꺼짐"))
        val tiers = mapOf(
            "꺼짐" to BoxTier("꺼짐", enabled = false),
            "빈티어" to BoxTier("빈티어"),
        )
        val groups = buildRollGroups(rewards, tiers, minRolls = 1, maxRolls = 1)

        // The disabled tier's own member is not silently promoted into the default pool - the
        // admin turned it off, so it must not drop at all.
        assertEquals(1, groups.size)
        assertEquals(listOf("a"), groups[0].entries.map { it.id })
    }

    // --- rolling ---------------------------------------------------------------

    @Test
    fun `every pool rolls independently and the results concatenate`() {
        val rng = Random(7)
        val rewards = listOf(
            reward("c1", 100.0),
            reward("c2", 100.0),
            reward("r", 100.0, tier = "희귀"),
        )
        val tiers = mapOf("희귀" to BoxTier("희귀", minRolls = 1, maxRolls = 1))
        val groups = buildRollGroups(rewards, tiers, minRolls = 1, maxRolls = 2)

        repeat(200) {
            val rolled = groups.flatMap { LootRoller.select(it.entries, it.minRolls, it.maxRolls, rng) }
            assertEquals(3, rolled.size, "일반 2개 + 희귀 1개가 항상 나와야 합니다")
            assertEquals(3, rolled.distinctBy { it.id }.size, "중복 지급은 없어야 합니다")
        }
    }

    /** A tier with `min-rolls: 0` is the "maybe a rare" case and must be able to produce nothing. */
    @Test
    fun `a zero minimum tier can come up empty`() {
        val rng = Random(11)
        val entries = listOf(reward("r", 10.0))
        var empties = 0
        repeat(2_000) {
            if (LootRoller.select(entries, minRolls = 0, maxRolls = 1, rng = rng).isEmpty()) empties++
        }
        assertTrue(empties in 1_600..1_950, "10% 확률이면 약 1800회가 비어야 합니다 (실제 $empties)")
    }

    // --- forecast --------------------------------------------------------------

    @Test
    fun `the combined distribution is a probability distribution`() {
        val pools = listOf(
            TierForecast.Pool("기본", listOf(50.0, 50.0, 25.0), 1, 2),
            TierForecast.Pool("희귀", listOf(10.0), 0, 1),
        )
        val distribution = TierForecast.distribution(pools)
        assertEquals(1.0, distribution.sum(), 1e-9)
        assertTrue(distribution.all { it >= 0.0 })
    }

    @Test
    fun `two certain single-item pools always total two`() {
        val pools = listOf(
            TierForecast.Pool("a", listOf(100.0), 1, 1),
            TierForecast.Pool("b", listOf(100.0), 1, 1),
        )
        val distribution = TierForecast.distribution(pools)
        assertEquals(1.0, distribution[2], 1e-9)
        assertEquals(2, TierForecast.effectiveMax(pools))
    }

    /** The forecast has to match what the roller actually does, or it is worse than useless. */
    @Test
    fun `the forecast matches a simulation of the same pools`() {
        val rewards = listOf(
            reward("c1", 60.0),
            reward("c2", 60.0),
            reward("c3", 30.0),
            reward("r1", 20.0, tier = "희귀"),
            reward("r2", 5.0, tier = "희귀"),
        )
        val tiers = mapOf("희귀" to BoxTier("희귀", minRolls = 0, maxRolls = 1))
        val groups = buildRollGroups(rewards, tiers, minRolls = 1, maxRolls = 2)
        val predicted = TierForecast.distribution(TierForecast.pools(groups))

        val rng = Random(2024)
        val runs = 60_000
        val observed = IntArray(predicted.size)
        repeat(runs) {
            val size = groups.sumOf { LootRoller.select(it.entries, it.minRolls, it.maxRolls, rng).size }
            if (size < observed.size) observed[size]++
        }

        for (count in predicted.indices) {
            val expected = predicted[count]
            val actual = observed[count].toDouble() / runs
            assertTrue(
                abs(expected - actual) < 0.01,
                "${count}개: 예측 $expected vs 실측 $actual",
            )
        }
    }
}
