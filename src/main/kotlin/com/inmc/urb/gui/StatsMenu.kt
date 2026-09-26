package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import com.inmc.urb.box.TierForecast
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * What a box has actually produced in live play.
 *
 * The simulator answers "what should happen" from the configured chances; this answers "what
 * did happen". Read together they separate two very different problems: a reward far below its
 * configured chance in *both* is a tuning issue, while one that only drifts here is small-sample
 * noise or a resolver failure.
 *
 * Everything shown is read from memory - the counters are already in RAM and the disk copy is
 * only a backup - so opening this screen costs nothing.
 */
class StatsMenu(
    urb: Urb,
    private val box: RandomBox,
    private var page: Int = 0,
) : Menu(urb, 54, title(box)) {

    override fun draw() {
        clear()
        for (slot in CONTENT_SIZE until 54) set(slot, Icon.EDGE)

        val opens = urb.stats.opens(box.name)
        val rewards = box.rewards
        val pages = Paging.pageCount(rewards.size, CONTENT_SIZE)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(rewards, page, CONTENT_SIZE).forEachIndexed { index, reward ->
            set(index, rewardIcon(reward, opens))
        }

        set(Paging.SLOT_BACK, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { BoxManageMenu(urb, box).open(it) }
        }

        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> switchPage(event.whoClicked, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> switchPage(event.whoClicked, page + 1) }

        set(48, Icon.of(Material.BOOK, "<yellow>요약</yellow>", summaryLore(opens)))

        set(
            49,
            Icon.of(
                Material.WRITTEN_BOOK, "<yellow>오픈 기록</yellow>",
                "<gray>최근 오픈 <white>${urb.stats.recentLog(1000).size}건</white>을 시간순으로 봅니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event -> (event.whoClicked as? Player)?.let { OpenLogMenu(urb, box.name).open(it) } }

        set(
            50,
            Icon.of(
                Material.GOLDEN_HELMET, "<yellow>오픈 랭킹</yellow>",
                "<gray>가장 많이 연 플레이어 순위입니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event -> (event.whoClicked as? Player)?.let { RankingMenu(urb).open(it) } }

        set(
            51,
            Icon.of(
                Material.ENCHANTED_BOOK, "<yellow>시뮬레이션과 비교</yellow>",
                "<gray>같은 설정으로 이론값을 계산해 봅니다.</gray>",
                "<dark_gray>실제 기록이 적을수록 편차가 큽니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            SimulationLauncher(urb).prompt(player, box)
        }

        set(
            52,
            Icon.of(
                Material.LAVA_BUCKET, "<red>통계 초기화</red>",
                "<gray>이 상자의 오픈 수와 보상 집계를 지웁니다.</gray>",
                "<dark_gray>플레이어별 총 오픈 수는 유지됩니다.</dark_gray>",
                "",
                "<red>▶ Shift+클릭</red>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (!event.isShiftClick) return@set
            val discarded = urb.stats.reset(box.name)
            player.sendMessage(Text.render("<yellow>기록 ${discarded}회를 초기화했습니다.</yellow>", null, player))
            StatsMenu(urb, box).open(player)
        }

        set(53, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    private fun rewardIcon(reward: com.inmc.urb.box.Reward, opens: Long): ItemStack {
        val icon = urb.itemResolver.icon(reward.item)
        val hits = urb.stats.hits(box.name, reward.id)
        val amount = urb.stats.amount(box.name, reward.id)
        val observed = urb.stats.observedRate(box.name, reward.id)

        val lore = mutableListOf(
            "<gray>설정 확률: <yellow>${Numbers.chance(reward.chance)}%</yellow></gray>",
        )
        if (observed == null) {
            lore.add("<dark_gray>아직 기록이 없습니다.</dark_gray>")
        } else {
            val drift = observed - reward.chance
            val driftText = when {
                drift >= 0.005 -> "<green>+${Numbers.chance(drift)}p</green>"
                drift <= -0.005 -> "<red>${Numbers.chance(drift)}p</red>"
                else -> "<gray>±0p</gray>"
            }
            lore.add("<gray>실제 출현률: <white>${Numbers.chance(observed)}%</white>  $driftText</gray>")
            lore.add("<gray>당첨: <white>${hits}회</white> / ${opens}회 오픈</gray>")
            if (reward.giveItem) {
                val perOpen = if (opens > 0) amount.toDouble() / opens else 0.0
                lore.add("<gray>누적 지급: <white>${amount}개</white>  <dark_gray>(오픈당 ${Numbers.chance(perOpen)}개)</dark_gray></gray>")
            }
        }
        reward.tier?.let { lore.add("<dark_gray>티어: $it</dark_gray>") }
        if (!reward.giveItem) lore.add("<dark_gray>아이템 지급 없음 (명령어 전용)</dark_gray>")
        lore.addAll(icon.notes())

        return Icon.annotate(icon.stack, lore = lore)
    }

    private fun summaryLore(opens: Long): List<String> {
        val lore = mutableListOf(
            "<gray>총 오픈: <white>${opens}회</white></gray>",
        )
        val last = urb.stats.lastOpenAt(box.name)
        if (last > 0L) {
            val ago = (System.currentTimeMillis() - last) / 1000L
            lore.add("<gray>마지막 오픈: <white>${Durations.formatShort(ago)} 전</white></gray>")
        }
        lore.add("<gray>전체 상자 누적: <white>${urb.stats.totalOpens()}회</white></gray>")

        if (opens <= 0L) {
            lore.add("")
            lore.add("<dark_gray>아직 아무도 이 상자를 열지 않았습니다.</dark_gray>")
            return lore
        }

        // The measured average, which is the number to sanity-check against the forecast.
        var totalHits = 0L
        for (reward in box.rewards) totalHits += urb.stats.hits(box.name, reward.id)
        val observedAverage = totalHits.toDouble() / opens

        val pools = TierForecast.pools(box.rollGroups())
        val forecast = TierForecast.distribution(pools)
        var expected = 0.0
        for (count in forecast.indices) expected += count * forecast[count]

        lore.add("")
        lore.add("<gray>오픈당 실제 개수: <white>${Numbers.chance(observedAverage)}개</white></gray>")
        lore.add("<gray>오픈당 이론 개수: <white>${Numbers.chance(expected)}개</white></gray>")

        val gap = observedAverage - expected
        if (opens < SMALL_SAMPLE) {
            lore.add("<dark_gray>표본이 ${opens}회뿐이라 편차가 큽니다.</dark_gray>")
        } else if (kotlin.math.abs(gap) >= 0.2) {
            lore.add("<yellow>이론값과 ${Numbers.chance(gap)}개 차이납니다.</yellow>")
            lore.add("<gray>불러오지 못한 보상이 있는지 확인하세요.</gray>")
        } else {
            lore.add("<green>이론값과 일치합니다.</green>")
        }
        return lore
    }

    private fun switchPage(who: org.bukkit.entity.HumanEntity, target: Int) {
        val player = who as? Player ?: return
        StatsMenu(urb, box, target).open(player)
    }

    companion object {
        private const val CONTENT_SIZE = 45

        /** Below this many opens the observed rate says more about luck than about settings. */
        private const val SMALL_SAMPLE = 50L

        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>통계 <gray>|</gray> ${box.name}</dark_gray>")
    }
}
