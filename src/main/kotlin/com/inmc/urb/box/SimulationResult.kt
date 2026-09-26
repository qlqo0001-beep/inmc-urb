package com.inmc.urb.box

import kr.inmc.core.util.Numbers

/** Per-reward outcome of a simulation run. */
class RewardStat(
    val reward: Reward,
    /** Opens in which this reward was selected. */
    val hits: Long,
    /** Summed item amounts across all hits (0 for command-only rewards). */
    val totalAmount: Long,
    /**
     * False when the reward's item cannot currently be built - the source plugin is missing or
     * the id was deleted. A real open silently skips it, so the admin needs to know that the
     * simulated numbers overstate what players would actually receive.
     */
    val resolvable: Boolean,
) {
    /** Share of opens containing this reward, 0..100. */
    fun observedRate(iterations: Long): Double =
        if (iterations <= 0) 0.0 else hits * 100.0 / iterations

    /** Observed minus configured, in percentage points. */
    fun drift(iterations: Long): Double = observedRate(iterations) - reward.chance

    fun averageAmountPerOpen(iterations: Long): Double =
        if (iterations <= 0) 0.0 else totalAmount.toDouble() / iterations

    fun amountPer(opens: Int, iterations: Long): Double =
        averageAmountPerOpen(iterations) * opens
}

/**
 * Everything one simulation run measured.
 *
 * The headline number an admin asks for is "how often does this drop", but the interesting
 * part is the gap between the configured chance and the observed rate: the max-rolls cap
 * pushes it down and the guaranteed minimum pushes it up, so a 25% item genuinely can land
 * at 18%. [truncatedOpens] and [flooredOpens] explain which of the two is doing it.
 */
class SimulationResult(
    val boxName: String,
    val displayName: String,
    val iterations: Long,
    val seed: Long,
    val minRolls: Int,
    val maxRolls: Int,
    /** index = item count in one open, value = how many opens produced that count. */
    val countDistribution: LongArray,
    val rewards: List<RewardStat>,
    /** Opens where more rewards won than the ceiling allowed, so some were discarded. */
    val truncatedOpens: Long,
    /** Opens where too few won and the guaranteed minimum had to top the result up. */
    val flooredOpens: Long,
    /** Opens that fired at least one announcement. */
    val announceOpens: Long,
    /** Total announcement events (one open can trigger several). */
    val announceEvents: Long,
    /** Total commands that would have been dispatched. */
    val commandRuns: Long,
    val elapsedMillis: Long,
    val cancelled: Boolean,
    /** One line per reward pool ("기본 1~2개", "전설 0~1개"); a single entry means no tiers. */
    val pools: List<String> = emptyList(),
    /** Largest total one open can produce once every pool's ceiling is applied. */
    val effectiveMax: Int = maxRolls,
) {

    val averageCount: Double
        get() {
            if (iterations <= 0) return 0.0
            var sum = 0L
            for (count in countDistribution.indices) sum += count * countDistribution[count]
            return sum.toDouble() / iterations
        }

    /** Total items handed out per open, across every reward. */
    val averageItemsPerOpen: Double
        get() = rewards.sumOf { it.averageAmountPerOpen(iterations) }

    fun countShare(count: Int): Double =
        if (iterations <= 0 || count !in countDistribution.indices) 0.0
        else countDistribution[count] * 100.0 / iterations

    fun truncatedRate(): Double = ratio(truncatedOpens)

    fun flooredRate(): Double = ratio(flooredOpens)

    fun announceRate(): Double = ratio(announceOpens)

    private fun ratio(value: Long): Double =
        if (iterations <= 0) 0.0 else value * 100.0 / iterations

    val unresolvable: List<RewardStat> get() = rewards.filterNot { it.resolvable }

    /**
     * Plain-language reading of the diagnostics. This is the part an admin acts on - the raw
     * percentages above only say *what* happened, not whether it is a problem.
     */
    fun diagnostics(): List<String> {
        val notes = ArrayList<String>()

        when {
            truncatedRate() >= 70.0 -> notes.add(
                "<red>최대 개수(${effectiveMax}개)가 결과를 지배합니다 (${Numbers.chance(truncatedRate())}%)</red>|" +
                    "<gray>개별 확률을 올려도 체감이 거의 바뀌지 않습니다. 최대 개수를 늘리거나 확률을 낮추세요.</gray>"
            )

            truncatedRate() >= 30.0 -> notes.add(
                "<yellow>최대 개수로 잘린 비율 ${Numbers.chance(truncatedRate())}%</yellow>|" +
                    "<gray>확률이 결과에 반영되긴 하지만 상한의 영향이 큽니다.</gray>"
            )
        }

        when {
            flooredRate() >= 50.0 -> notes.add(
                "<red>최소 보장이 ${Numbers.chance(flooredRate())}% 발동합니다</red>|" +
                    "<gray>확률이 전반적으로 너무 낮아 사실상 가중치 뽑기로 동작합니다.</gray>"
            )

            flooredRate() >= 15.0 -> notes.add(
                "<yellow>최소 보장 발동 ${Numbers.chance(flooredRate())}%</yellow>|" +
                    "<gray>아무것도 당첨되지 않는 경우가 꽤 있습니다.</gray>"
            )
        }

        if (announceRate() >= 5.0) {
            notes.add(
                "<red>전체 공지가 ${Numbers.chance(announceRate())}% 확률로 발생합니다</red>|" +
                    "<gray>20번에 한 번 이상 채팅이 도배됩니다. 공지 대상을 줄이세요.</gray>"
            )
        } else if (announceEvents > 0) {
            notes.add(
                "<green>전체 공지 ${Numbers.chance(announceRate())}%</green>|" +
                    "<gray>100번 열 때 약 ${Numbers.chance(announceRate())}회 공지됩니다.</gray>"
            )
        }

        if (unresolvable.isNotEmpty()) {
            notes.add(
                "<red>불러올 수 없는 보상 ${unresolvable.size}종</red>|" +
                    "<gray>실제 오픈에서는 건너뛰므로 아래 수치보다 적게 나옵니다.</gray>"
            )
        }

        if (notes.isEmpty()) {
            notes.add(
                "<green>특이사항 없음</green>|" +
                    "<gray>설정한 확률이 결과에 정상적으로 반영되고 있습니다.</gray>"
            )
        }
        return notes
    }
}
