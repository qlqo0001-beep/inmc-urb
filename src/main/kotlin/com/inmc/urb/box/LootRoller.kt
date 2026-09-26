package com.inmc.urb.box

import java.util.Random

/** Anything with a 0.01 .. 100 percentage chance. Kept separate so the roller stays testable. */
interface Weighted {
    val chance: Double
}

/**
 * The loot selection rule from spec §64-68.
 *
 * The spec gives two worked examples that any implementation has to satisfy at once:
 *
 *  - 54 rewards registered, max 54: the result may be anything from a single item up to all
 *    54, purely as the individual chances fall.
 *  - 54 rewards registered, max 2: the result is *1 or 2* items - not always exactly 2.
 *
 * So every reward rolls independently, the winners are then truncated to `maxRolls`, and only
 * if nothing at all won does the guaranteed floor kick in ("다만 1개는 꼭나와야 됩니다").
 *
 * The previous implementation re-rolled recursively until it reached `maxSelect`, which both
 * forced the count to the maximum and could recurse forever when every chance was low.
 */
object LootRoller {

    /**
     * A roll, plus how it got there.
     *
     * [rawWinners] is the count *before* the ceiling and floor were applied. The simulator
     * uses it to tell an admin whether their chance values are actually deciding anything:
     * if nearly every roll is truncated, the max-rolls cap is what shapes the result and the
     * individual percentages barely matter.
     */
    data class Selection<T : Weighted>(val selected: List<T>, val rawWinners: Int)

    fun <T : Weighted> select(
        entries: List<T>,
        minRolls: Int,
        maxRolls: Int,
        rng: Random,
    ): List<T> = selectDetailed(entries, minRolls, maxRolls, rng).selected

    fun <T : Weighted> selectDetailed(
        entries: List<T>,
        minRolls: Int,
        maxRolls: Int,
        rng: Random,
    ): Selection<T> {
        if (entries.isEmpty()) return Selection(emptyList(), 0)

        val ceiling = maxRolls.coerceIn(1, entries.size)

        // The floor may be 0: a tier can be optional ("0~1 rare"), and the box-level pool
        // never passes 0 because RandomBox clamps its own minRolls to 1.
        val floor = minRolls.coerceIn(0, ceiling)

        // 1. independent trial per reward
        val winners = entries.filter { rng.nextDouble() * 100.0 < it.chance }.toMutableList()
        val rawWinners = winners.size
        winners.shuffle(rng)

        // 2. never hand out more than the configured ceiling
        if (winners.size > ceiling) {
            return Selection(winners.subList(0, ceiling).toList(), rawWinners)
        }

        // 3. top up to the guaranteed floor, weighted by chance, without repeats
        if (winners.size < floor) {
            val remaining = entries.filterNot { candidate -> winners.any { it === candidate } }.toMutableList()
            while (winners.size < floor && remaining.isNotEmpty()) {
                winners.add(takeWeighted(remaining, rng))
            }
        }
        return Selection(winners.toList(), rawWinners)
    }

    fun rollAmount(min: Int, max: Int, rng: Random): Int {
        val low = min.coerceAtLeast(1)
        val high = max.coerceAtLeast(low)
        return if (low == high) low else low + rng.nextInt(high - low + 1)
    }

    /** Removes and returns one entry, picked proportionally to its chance. */
    private fun <T : Weighted> takeWeighted(pool: MutableList<T>, rng: Random): T {
        val total = pool.sumOf { it.chance }
        if (total <= 0.0) return pool.removeAt(rng.nextInt(pool.size))
        var cursor = rng.nextDouble() * total
        for (index in pool.indices) {
            cursor -= pool[index].chance
            if (cursor <= 0.0) return pool.removeAt(index)
        }
        return pool.removeAt(pool.size - 1)
    }

    /**
     * Distinct slot indices inside a 6x9 window. Spec §67: contents appear in random slots,
     * never packed in registration order.
     */
    fun randomSlots(count: Int, size: Int, rng: Random): List<Int> {
        val slots = (0 until size).toMutableList()
        slots.shuffle(rng)
        return slots.take(count.coerceIn(0, size))
    }
}
