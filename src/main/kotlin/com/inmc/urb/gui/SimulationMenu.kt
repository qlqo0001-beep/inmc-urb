package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import com.inmc.urb.box.RewardStat
import com.inmc.urb.box.SimulationResult
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Simulation results (admin only).
 *
 * The reward grid answers "how often and how much", and the bottom row carries the four
 * readings that actually drive a decision: the count distribution, the diagnostics, the
 * projected daily output, and the cost per open.
 */
class SimulationMenu(
    urb: Urb,
    private val box: RandomBox,
    private val result: SimulationResult,
    private var page: Int = 0,
    private var sortByRate: Boolean = true,
) : Menu(urb, SIZE, title(box)) {

    override fun draw() {
        clear()

        val stats = if (sortByRate) {
            result.rewards.sortedByDescending { it.observedRate(result.iterations) }
        } else {
            result.rewards.sortedBy { it.reward.chance }
        }

        val pages = Paging.pageCount(stats.size, PER_PAGE)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(stats, page, PER_PAGE).forEachIndexed { index, stat ->
            set(index, rewardIcon(stat))
        }

        for (slot in PER_PAGE until SIZE) set(slot, Icon.EDGE)

        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> repage(event.whoClicked, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> repage(event.whoClicked, page + 1) }

        // 마지막 줄 배치 (이 화면만 유독 빽빽하다):
        //   45 뒤로 · 46 이전 · 47 다음 · 48 처리량 · 49 비용 · 50 정렬 · 51 다시실행
        //   52 개수분포 · 53 진단
        //
        // 46·47 은 이전/다음 자리(Paging.SLOT_PREV / SLOT_NEXT)인데 이 화면만 그 두 칸을
        // 패널로 쓰고 있었다. 그대로 두면 패널이 버튼을 덮어 페이지 이동이 아예 막힌다.
        // 53 은 관례상 닫기 자리지만 이 화면에는 닫기가 없어 패널에 내준다.
        set(48, throughputPanel())
        set(49, costPanel())
        set(52, countPanel())
        set(53, diagnosticsPanel())

        set(
            50,
            Icon.of(
                Material.HOPPER,
                "<yellow>정렬: <white>${if (sortByRate) "실제 출현률 높은 순" else "설정 확률 낮은 순"}</white></yellow>",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            SimulationMenu(urb, box, result, 0, !sortByRate).open(player)
        }

        set(
            51,
            Icon.of(
                Material.CLOCK, "<yellow>다시 실행</yellow>",
                "<gray>시행 횟수를 새로 입력해 다시 돌립니다.</gray>",
                "<dark_gray>같은 시드로 돌리면 설정 변경의 효과만 비교됩니다.</dark_gray>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            SimulationLauncher(urb).prompt(player, box)
        }

        set(Paging.SLOT_BACK, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { BoxManageMenu(urb, box).open(it) }
        }
    }

    // --- reward grid -----------------------------------------------------------

    private fun rewardIcon(stat: RewardStat): org.bukkit.inventory.ItemStack {
        val icon = urb.itemResolver.icon(stat.reward.item)
        val observed = stat.observedRate(result.iterations)
        val drift = stat.drift(result.iterations)

        val lore = mutableListOf(
            "<gray>설정 확률: <white>${Numbers.chance(stat.reward.chance)}%</white></gray>",
            "<gray>실제 출현: <yellow>${Numbers.chance(observed)}%</yellow>  ${driftLabel(drift)}</gray>",
            "<dark_gray>${formatCount(stat.hits)} / ${formatCount(result.iterations)}회</dark_gray>",
            "",
        )

        if (stat.reward.giveItem) {
            lore.add("<gray>1회당 평균: <white>${Numbers.chance(stat.averageAmountPerOpen(result.iterations))}개</white></gray>")
            lore.add("<gray>100회당 총량: <white>${Numbers.chance(stat.amountPer(100, result.iterations))}개</white></gray>")
        } else {
            lore.add("<dark_gray>아이템 지급 없음 (명령어 전용)</dark_gray>")
        }
        if (stat.reward.commands.isNotEmpty()) {
            lore.add("<aqua>명령어 ${stat.reward.commands.size}개 · 총 ${formatCount(stat.hits * stat.reward.commands.size)}회 실행</aqua>")
        }
        if (stat.reward.announce) lore.add("<gold>★ 당첨 시 서버 공지</gold>")
        if (!stat.resolvable) {
            lore.add("")
            lore.add("<red>⚠ 실제 오픈에서는 지급되지 않습니다</red>")
            lore.add("<dark_gray>원본 플러그인이 없거나 ID가 삭제되었습니다</dark_gray>")
        }

        return Icon.annotate(icon.stack, lore = lore)
    }

    /** Configured vs observed: the gap is the part admins misread, so it gets its own label. */
    private fun driftLabel(drift: Double): String = when {
        Math.abs(drift) < 0.05 -> "<dark_gray>(설정대로)</dark_gray>"
        drift < 0 -> "<red>(${Numbers.chance(drift)}%p)</red>"
        else -> "<green>(+${Numbers.chance(drift)}%p)</green>"
    }

    // --- summary panels --------------------------------------------------------

    private fun countPanel(): org.bukkit.inventory.ItemStack {
        val lore = mutableListOf(
            "<gray>설정 범위: <white>${result.minRolls} ~ ${result.maxRolls}개</white></gray>",
        )
        if (result.pools.size > 1) {
            // Tiered box: the box-level range only governs the default pool, so listing the
            // pools is the only honest way to explain where the total comes from.
            lore.add("<gray>추첨 풀 <white>${result.pools.size}개</white>  합산 최대 <white>${result.effectiveMax}개</white></gray>")
            result.pools.forEach { lore.add("<dark_gray>  · $it</dark_gray>") }
        }
        lore.add("<gray>평균: <yellow>${Numbers.chance(result.averageCount)}개</yellow></gray>")
        lore.add("")
        for (count in result.countDistribution.indices) {
            val share = result.countShare(count)
            if (share <= 0.0) continue
            lore.add("<gray>${count}개: <white>${Numbers.chance(share)}%</white> <dark_gray>(${formatCount(result.countDistribution[count])}회)</dark_gray></gray>")
        }
        lore.add("")
        lore.add("<gray>1회당 총 아이템: <white>${Numbers.chance(result.averageItemsPerOpen)}개</white></gray>")

        // Analytic prediction beside the measured one: a gap means the run was too short.
        val forecast = com.inmc.urb.box.TierForecast.distribution(
            com.inmc.urb.box.TierForecast.pools(box.rollGroups())
        )
        lore.add("")
        lore.add("<dark_gray>이론값 (해석적 계산)</dark_gray>")
        for (count in forecast.indices) {
            val share = forecast[count] * 100.0
            if (share < 0.005) continue
            lore.add("<dark_gray>  ${count}개: ${Numbers.chance(share)}%</dark_gray>")
        }
        return Icon.of(Material.COMPARATOR, "<yellow>개수 분포</yellow>", lore)
    }

    private fun diagnosticsPanel(): org.bukkit.inventory.ItemStack {
        val lore = mutableListOf(
            "<gray>최대 개수로 잘림: <white>${Numbers.chance(result.truncatedRate())}%</white></gray>",
            "<gray>최소 보장 발동: <white>${Numbers.chance(result.flooredRate())}%</white></gray>",
            "<gray>전체 공지 발생: <white>${Numbers.chance(result.announceRate())}%</white></gray>",
            "",
        )
        // Each diagnostic is "headline|explanation"; both halves are worth showing.
        for (note in result.diagnostics()) {
            note.split('|').forEach { lore.add(it) }
        }
        return Icon.of(Material.SPYGLASS, "<yellow>진단</yellow>", lore)
    }

    private fun throughputPanel(): org.bukkit.inventory.ItemStack {
        val lore = mutableListOf<String>()

        if (box.isPermanent) {
            lore.add("<gray>생성 방식: <white>고정 설치</white></gray>")
            lore.add("<gray>설치 지점: <white>${box.fixedPoints.size}곳</white></gray>")
            if (box.maxOpensPerPlayer > 0) {
                lore.add("<gray>1인당 최대: <white>${box.maxOpensPerPlayer}회</white></gray>")
                lore.add("")
                lore.add("<gray>1인당 총 산출: <white>${Numbers.chance(result.averageItemsPerOpen * box.maxOpensPerPlayer)}개</white></gray>")
            } else {
                lore.add("")
                lore.add("<red>1인당 오픈 제한이 없습니다</red>")
                lore.add("<gray>고정 설치 상자는 제한을 두는 것을 권장합니다.</gray>")
            }
            return Icon.of(Material.BEDROCK, "<yellow>산출량 예측</yellow>", lore)
        }

        val interval = box.autoSpawnIntervalSeconds
        if (interval <= 0) {
            lore.add("<gray>자동 생성이 꺼져 있어 예측할 수 없습니다.</gray>")
            lore.add("<dark_gray>수동 소환만 사용하는 상자입니다.</dark_gray>")
            return Icon.of(Material.CLOCK, "<yellow>산출량 예측</yellow>", lore)
        }

        val cyclesPerDay = 86400.0 / interval
        val spawnsPerDay = cyclesPerDay * box.spawnAmountPerCycle

        lore.add("<gray>생성 주기: <white>${Durations.formatShort(interval)}</white> × <white>${box.spawnAmountPerCycle}개</white></gray>")
        lore.add("<gray>하루 생성: <yellow>약 ${Numbers.chance(spawnsPerDay)}개</yellow></gray>")
        lore.add("<gray>동시 최대: <white>${box.maxSpawnCount}개</white></gray>")
        lore.add("")
        lore.add("<dark_gray>아래는 생성된 상자가 전부 열린다고 가정한 상한입니다.</dark_gray>")
        lore.add("<gray>하루 총 아이템: <white>${Numbers.chance(result.averageItemsPerOpen * spawnsPerDay)}개</white></gray>")
        lore.add("<gray>하루 전체공지: <white>${Numbers.chance(result.announceRate() / 100.0 * spawnsPerDay)}회</white></gray>")
        lore.add("")

        val top = result.rewards
            .filter { it.reward.giveItem }
            .sortedByDescending { it.averageAmountPerOpen(result.iterations) }
            .take(3)
        if (top.isNotEmpty()) {
            lore.add("<gray>하루 배출량 상위</gray>")
            top.forEach {
                lore.add("<dark_gray>· ${it.reward.label()} ${Numbers.chance(it.averageAmountPerOpen(result.iterations) * spawnsPerDay)}개</dark_gray>")
            }
        }
        return Icon.of(Material.CLOCK, "<yellow>산출량 예측</yellow>", lore)
    }

    private fun costPanel(): org.bukkit.inventory.ItemStack {
        val key = box.keyItem
        val money = box.moneyCost

        if (key == null && money <= 0.0) {
            return Icon.of(
                Material.GOLD_INGOT, "<yellow>비용 분석</yellow>",
                "<gray>이 상자는 열쇠도 비용도 없습니다.</gray>",
                if (box.isPermanent) "<yellow>고정 설치 상자는 무료로 열립니다. 무제한 파밍 주의.</yellow>"
                else "<dark_gray>무료로 열리는 상자입니다.</dark_gray>",
            )
        }

        val lore = mutableListOf("<gray>1회 오픈 비용</gray>")
        key?.let { lore.add("<dark_gray>· ${it.label()} 1개</dark_gray>") }
        if (money > 0) lore.add("<dark_gray>· ${Numbers.money(money)}원</dark_gray>")

        lore.add("")
        lore.add("<gray>100회 기준</gray>")
        key?.let { lore.add("<dark_gray>· ${it.label()} 100개</dark_gray>") }
        if (money > 0) lore.add("<dark_gray>· ${Numbers.money(money * 100)}원</dark_gray>")
        lore.add("<gray>· 산출 아이템 <white>${Numbers.chance(result.averageItemsPerOpen * 100)}개</white></gray>")

        if (money > 0 && result.averageItemsPerOpen > 0) {
            lore.add("")
            lore.add("<gray>아이템 1개당 비용: <white>${Numbers.money(money / result.averageItemsPerOpen)}원</white></gray>")
        }
        if (money > 0 && !urb.economy.isEnabled) {
            lore.add("")
            lore.add("<red>⚠ Vault 경제 플러그인이 없어 실제로는 적용되지 않습니다</red>")
        }
        lore.add("")
        lore.add("<dark_gray>아이템의 시세는 플러그인이 알 수 없어</dark_gray>")
        lore.add("<dark_gray>손익 판단은 직접 하셔야 합니다.</dark_gray>")

        return Icon.of(Material.GOLD_INGOT, "<yellow>비용 분석</yellow>", lore)
    }

    private fun repage(who: org.bukkit.entity.HumanEntity, target: Int) {
        val player = who as? Player ?: return
        SimulationMenu(urb, box, result, target, sortByRate).open(player)
    }

    companion object {
        private const val SIZE = 54
        private const val PER_PAGE = 45

        fun formatCount(value: Long): String = String.format("%,d", value)

        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>시뮬레이션 <gray>|</gray> ${box.name}</dark_gray>")
    }
}
