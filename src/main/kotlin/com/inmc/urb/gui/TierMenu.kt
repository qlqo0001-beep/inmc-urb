package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.BoxTier
import com.inmc.urb.box.RandomBox
import com.inmc.urb.box.TierForecast
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * Reward tiers: named pools that roll with their own count range.
 *
 * Without them every reward competes for the same slots, so "two commons and *maybe* one rare"
 * cannot be expressed - raising the rare's chance only makes it crowd the commons out, because
 * the ceiling is shared. A tier draws separately, and the results are merged.
 *
 * The default pool is always present and cannot be deleted: rewards that name no tier roll
 * there, and it is the same min/max the box's ④ 내용물 개수 screen edits.
 */
class TierMenu(urb: Urb, private val box: RandomBox) : Menu(urb, 54, title(box)) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        drawDefaultPool()

        box.tiers.entries.forEachIndexed { index, (name, tier) ->
            val slot = SLOTS.getOrNull(index) ?: return@forEachIndexed
            drawTier(slot, name, tier)
        }

        set(
            48,
            Icon.of(
                Material.WRITABLE_BOOK, "<green>＋ 티어 추가</green>",
                "<gray>새 보상 풀을 만듭니다.</gray>",
                "<gray>현재 <white>${box.tiers.size}</white> / ${SLOTS.size}개</gray>",
                "",
                "<yellow>▶ 클릭하여 이름 입력</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (box.tiers.size >= SLOTS.size) {
                player.sendMessage(Text.render("<red>티어는 최대 ${SLOTS.size}개까지 만들 수 있습니다.</red>", null, player))
                return@set
            }
            promptNewTier(player)
        }

        set(50, Icon.of(Material.KNOWLEDGE_BOOK, "<yellow>합산 예측</yellow>", forecastLore()))

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { BoxManageMenu(urb, box).open(it) }
        }
        set(53, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    // --- rows ------------------------------------------------------------------

    private fun drawDefaultPool() {
        val members = box.untieredRewards().size
        set(
            10,
            Icon.of(
                Material.CHEST, "<white>기본 풀</white>",
                "<gray>티어를 지정하지 않은 보상이 여기서 뽑힙니다.</gray>",
                "",
                "<gray>보상: <white>${members}종</white></gray>",
                "<gray>배출: <white>${box.minRolls} ~ ${box.maxRolls}개</white></gray>",
                "<dark_gray>④ 내용물 개수 화면과 같은 값입니다.</dark_gray>",
                "",
                "<yellow>▶ 좌클릭: 최대 +1  /  우클릭: 최대 -1</yellow>",
                "<yellow>▶ Shift+좌클릭: 최소 +1  /  Shift+우클릭: 최소 -1</yellow>",
                "<dark_gray>기본 풀은 삭제할 수 없습니다.</dark_gray>",
            )
        ) { event ->
            when {
                event.isShiftClick && event.isLeftClick -> box.minRolls += 1
                event.isShiftClick && event.isRightClick -> box.minRolls -= 1
                event.isLeftClick -> box.maxRolls += 1
                event.isRightClick -> box.maxRolls -= 1
            }
            save(); redraw(event.whoClicked)
        }
    }

    private fun drawTier(slot: Int, name: String, tier: BoxTier) {
        val members = box.rewardsIn(name)
        val chanceSum = members.sumOf { it.chance }

        val lore = mutableListOf(
            "<gray>상태: </gray>${Icon.toggle(tier.enabled)}",
            "<gray>보상: <white>${members.size}종</white>  <dark_gray>확률 합 ${Numbers.chance(chanceSum)}%</dark_gray></gray>",
            "<gray>배출: <white>${tier.minRolls} ~ ${tier.maxRolls}개</white></gray>",
        )
        if (tier.minRolls == 0) {
            lore.add("<dark_gray>최소 0 - 아무것도 안 나올 수 있습니다.</dark_gray>")
        }
        if (tier.maxRolls <= 0) {
            lore.add("<red>⚠ 최대가 0이라 이 티어는 추첨되지 않습니다.</red>")
        } else if (members.isEmpty()) {
            lore.add("<red>⚠ 이 티어에 속한 보상이 없어 추첨되지 않습니다.</red>")
        } else if (tier.maxRolls > members.size) {
            lore.add("<yellow>⚠ 실질 최대 ${members.size}개 (보상이 그만큼뿐입니다)</yellow>")
        }
        lore.add("")
        lore.add("<yellow>▶ 좌클릭: 최대 +1  /  우클릭: 최대 -1</yellow>")
        lore.add("<yellow>▶ Shift+좌클릭: 최소 +1  /  Shift+우클릭: 최소 -1</yellow>")
        lore.add("<yellow>▶ Q / F: 사용 여부 전환</yellow>")
        lore.add("<red>▶ Ctrl + Q: 삭제 (확인 창이 뜹니다)</red>")

        set(
            slot,
            Icon.of(
                if (tier.enabled) Material.BUNDLE else Material.GRAY_DYE,
                "<yellow>$name</yellow>",
                lore,
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            when (event.click) {
                // Not MIDDLE: the vanilla client only sends a middle-click ("clone") packet in
                // creative mode, so the binding silently did nothing for an admin in survival.
                // Ctrl+Q is delivered in every game mode.
                ClickType.CONTROL_DROP -> {
                    confirmDelete(player, name)
                    return@set
                }

                ClickType.DROP, ClickType.SWAP_OFFHAND -> tier.enabled = !tier.enabled

                else -> when {
                    event.isShiftClick && event.isLeftClick -> tier.minRolls += 1
                    event.isShiftClick && event.isRightClick -> tier.minRolls -= 1
                    event.isLeftClick -> tier.maxRolls += 1
                    event.isRightClick -> tier.maxRolls -= 1
                }
            }
            save(); redraw(player)
        }
    }

    // --- forecast --------------------------------------------------------------

    private fun forecastLore(): List<String> {
        val groups = box.rollGroups()
        if (groups.isEmpty()) {
            return listOf("<red>등록된 보상이 없습니다.</red>")
        }

        val pools = TierForecast.pools(groups)
        val distribution = TierForecast.distribution(pools)

        val lore = mutableListOf("<gray>모든 풀을 합쳤을 때의 개수 분포입니다.</gray>", "")
        for (pool in pools) {
            lore.add("<dark_gray>· ${pool.label}: ${pool.minRolls}~${pool.maxRolls}개 (${pool.chances.size}종)</dark_gray>")
        }
        lore.add("")
        for (count in distribution.indices) {
            val share = distribution[count] * 100.0
            if (share < 0.005) continue
            val bar = "|".repeat((share / 5).toInt().coerceAtMost(20))
            lore.add("<dark_gray>  ${count}개 <white>${Numbers.chance(share)}%</white> <dark_gray>$bar</dark_gray>")
        }
        lore.add("")
        lore.add("<gray>평균 당첨: <white>${Numbers.chance(TierForecast.expectedWinners(pools))}종</white></gray>")
        lore.add("<gray>최대 가능: <white>${TierForecast.effectiveMax(pools)}개</white></gray>")

        val truncation = TierForecast.truncationRate(pools)
        if (truncation >= 30.0) {
            lore.add("<yellow>한 개 이상의 풀에서 잘림 ${Numbers.chance(truncation)}%</yellow>")
        }
        return lore
    }

    // --- actions ---------------------------------------------------------------

    private fun promptNewTier(player: Player) {
        urb.prompts.request(
            player,
            listOf(
                "<yellow>새 티어의 이름을 입력하세요.</yellow>",
                "<gray>예: <white>전설</white>, <white>일반</white>, <white>보너스</white></gray>",
            ),
            onCancel = { reopen(player) },
        ) { input ->
            val name = input.trim()
            when {
                name.isEmpty() || name.length > 16 ->
                    player.sendMessage(Text.render("<red>티어 이름은 1~16자여야 합니다.</red>", null, player))

                box.tiers.containsKey(name) ->
                    player.sendMessage(Text.render("<red>이미 있는 티어입니다.</red>", null, player))

                else -> {
                    box.tiers[name] = BoxTier(name, minRolls = 0, maxRolls = 1)
                    save()
                    player.sendMessage(Text.render("<green>티어 '$name' 을(를) 추가했습니다.</green>", null, player))
                }
            }
            reopen(player)
        }
    }

    private fun confirmDelete(player: Player, name: String) {
        val members = box.rewardsIn(name).size
        ConfirmMenu(
            urb,
            question = "<yellow>⚠ 확인이 필요합니다</yellow>",
            title = "<dark_red>티어 삭제</dark_red>",
            detail = listOf(
                "<red>티어 '<white>$name</white>' 을(를) 삭제합니다.</red>",
                "<gray>보상 ${members}개는 삭제되지 않고 기본 풀로 돌아갑니다.</gray>",
            ),
            onConfirm = {
                box.tiers.remove(name)
                // Clear the tag too, so a tier later created with the same name does not
                // silently reclaim rewards the admin had already moved on from.
                box.rewards.forEach { if (it.tier == name) it.tier = null }
                save()
                reopen(player)
            },
            onCancel = { reopen(player) },
        ).open(player)
    }

    private fun save() = urb.boxes.markDirty(box)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = TierMenu(urb, box).open(player)

    companion object {
        /** Three rows of seven, leaving the default pool its own spot at slot 10. */
        private val SLOTS = intArrayOf(
            12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
        ).toList()

        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>보상 티어 <gray>|</gray> ${box.name}</dark_gray>")
    }
}
