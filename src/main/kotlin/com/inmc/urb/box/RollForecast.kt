package com.inmc.urb.box

/**
 * Exact prediction of how many items an open will yield, without running a simulation.
 *
 * Each reward is an independent trial, so the number of winners follows a Poisson-binomial
 * distribution - computable in O(n²) by dynamic programming, which for 54 rewards is a few
 * thousand operations. That is cheap enough to redraw on every GUI click, so an admin sees the
 * effect of a min/max change immediately instead of having to simulate first.
 */
object RollForecast {

    /**
     * Probability of each *final* item count, index 0..entries.size.
     *
     * The raw winner distribution is folded through the same clamp the roller applies: fewer
     * winners than the floor get topped up, more than the ceiling get truncated.
     */
    fun distribution(chances: List<Double>, minRolls: Int, maxRolls: Int): DoubleArray {
        val size = chances.size
        if (size == 0) return DoubleArray(1) { 1.0 }

        // dp[k] = P(exactly k rewards passed their own roll)
        var dp = DoubleArray(size + 1)
        dp[0] = 1.0
        for ((index, chance) in chances.withIndex()) {
            val p = (chance / 100.0).coerceIn(0.0, 1.0)
            val next = DoubleArray(size + 1)
            for (k in 0..index) {
                next[k] += dp[k] * (1.0 - p)
                next[k + 1] += dp[k] * p
            }
            dp = next
        }

        val ceiling = maxRolls.coerceIn(1, size)
        val floor = minRolls.coerceIn(0, ceiling)

        val result = DoubleArray(size + 1)
        for (k in dp.indices) {
            if (dp[k] == 0.0) continue
            result[k.coerceIn(floor, ceiling)] += dp[k]
        }
        return result
    }

    /** How often the ceiling discards a winner - the "is my chance setting doing anything" number. */
    fun truncationRate(chances: List<Double>, maxRolls: Int): Double {
        val size = chances.size
        if (size == 0) return 0.0
        val ceiling = maxRolls.coerceIn(1, size)

        var dp = DoubleArray(size + 1)
        dp[0] = 1.0
        for ((index, chance) in chances.withIndex()) {
            val p = (chance / 100.0).coerceIn(0.0, 1.0)
            val next = DoubleArray(size + 1)
            for (k in 0..index) {
                next[k] += dp[k] * (1.0 - p)
                next[k + 1] += dp[k] * p
            }
            dp = next
        }
        var over = 0.0
        for (k in (ceiling + 1)..size) over += dp[k]
        return over * 100.0
    }

    /** Average winners per open, before any clamp. Equal to the sum of the chances. */
    fun expectedWinners(chances: List<Double>): Double = chances.sumOf { it / 100.0 }

    /** The cap that is actually in force - a max of 5 with 3 rewards is really a max of 3. */
    fun effectiveMax(rewardCount: Int, maxRolls: Int): Int =
        if (rewardCount == 0) 0 else maxRolls.coerceIn(1, rewardCount)

    fun isMaxClamped(rewardCount: Int, maxRolls: Int): Boolean =
        rewardCount in 1 until maxRolls
}

/**
 * Multi-pool forecasting for boxes that use reward tiers.
 *
 * Each pool is an independent Poisson-binomial run through its own clamp, so the total count is
 * their convolution. Kept next to [RollForecast] rather than inside it because the single-pool
 * entry points stay the hot path - every box without tiers uses those unchanged.
 */
object TierForecast {

    data class Pool(val label: String, val chances: List<Double>, val minRolls: Int, val maxRolls: Int)

    fun pools(groups: List<RollGroup>): List<Pool> = groups.map { group ->
        Pool(
            label = group.tier ?: DEFAULT_LABEL,
            chances = group.entries.map { it.chance },
            minRolls = group.minRolls,
            maxRolls = group.maxRolls,
        )
    }

    /** Probability of each total item count across every pool. */
    fun distribution(pools: List<Pool>): DoubleArray {
        if (pools.isEmpty()) return doubleArrayOf(1.0)
        if (pools.size == 1) {
            return RollForecast.distribution(pools[0].chances, pools[0].minRolls, pools[0].maxRolls)
        }

        var acc = doubleArrayOf(1.0)
        for (pool in pools) {
            val own = RollForecast.distribution(pool.chances, pool.minRolls, pool.maxRolls)
            val next = DoubleArray(acc.size + own.size - 1)
            for (i in acc.indices) {
                val left = acc[i]
                if (left == 0.0) continue
                for (j in own.indices) {
                    if (own[j] == 0.0) continue
                    next[i + j] += left * own[j]
                }
            }
            acc = next
        }
        return acc
    }

    /** Chance that at least one pool had to discard a winner. */
    fun truncationRate(pools: List<Pool>): Double {
        if (pools.isEmpty()) return 0.0
        var survive = 1.0
        for (pool in pools) {
            survive *= 1.0 - RollForecast.truncationRate(pool.chances, pool.maxRolls) / 100.0
        }
        return (1.0 - survive) * 100.0
    }

    fun expectedWinners(pools: List<Pool>): Double =
        pools.sumOf { RollForecast.expectedWinners(it.chances) }

    /** Largest total the clamps allow. */
    fun effectiveMax(pools: List<Pool>): Int =
        pools.sumOf { RollForecast.effectiveMax(it.chances.size, it.maxRolls) }

    const val DEFAULT_LABEL = "기본"
}
