package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import com.inmc.urb.box.Reward
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.item.StorageMode
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack

/**
 * Reward registration (spec §46-48).
 *
 * Add mode: drop items into the empty slots and press 확인. Remove mode: pull reward icons
 * out and press 확인 to delete them. Hovering a reward and pressing Q, F or shift-right-click
 * opens its detail screen, which is where the decimal chance lives.
 *
 * Only genuinely new stacks are captured on confirm - existing rewards keep their stored
 * item, so the display lore this menu adds never leaks into the saved data.
 */
class RewardListMenu(
    urb: Urb,
    private val box: RandomBox,
    private var page: Int = 0,
    private var removeMode: Boolean = false,
) : Menu(urb, SIZE, title(box)) {

    private val slotToReward = HashMap<Int, Reward>()

    /** Slots the admin has lifted out in remove mode, pending confirmation. */
    private val stagedRemovals = HashSet<Int>()

    override fun draw() {
        clear()
        slotToReward.clear()

        val rewards = box.rewards
        val pages = Paging.pageCount(rewards.size, CONTENT_SIZE)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(rewards, page, CONTENT_SIZE).forEachIndexed { index, reward ->
            slotToReward[index] = reward
            val icon = rewardIcon(reward)
            set(index, if (removeMode && index in stagedRemovals) null else icon) { event ->
                val player = event.whoClicked as? Player ?: return@set
                if (removeMode) {
                    // The icons are display copies, so they are never handed to the player -
                    // clicking just lifts the reward out of the window, and clicking the empty
                    // slot puts it back.
                    if (!stagedRemovals.add(index)) stagedRemovals.remove(index)
                    inventory.setItem(index, if (index in stagedRemovals) null else icon)
                    player.updateInventory()
                    return@set
                }
                when (event.click) {
                    ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND, ClickType.SHIFT_RIGHT ->
                        RewardDetailMenu(urb, box, reward, page).open(player)

                    else -> Unit
                }
            }
        }

        for (slot in CONTENT_SIZE until SIZE) set(slot, Icon.EDGE)

        set(Paging.SLOT_BACK, Icon.back()) { event ->
            val player = event.whoClicked as? Player ?: return@set
            returnStaged(player)
            BoxManageMenu(urb, box).open(player)
        }

        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> switchPage(event.whoClicked, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> switchPage(event.whoClicked, page + 1) }

        set(
            49,
            Icon.of(
                if (removeMode) Material.LAVA_BUCKET else Material.HOPPER,
                if (removeMode) "<red>제거 모드 (켜짐)</red>" else "<gray>제거 모드 (꺼짐)</gray>",
                if (removeMode) {
                    listOf(
                        "<gray>지울 아이템을 클릭해 빼낸 뒤</gray>",
                        "<gray>확인을 누르면 삭제됩니다.</gray>",
                        "<dark_gray>다시 클릭하면 되돌립니다.</dark_gray>",
                        "",
                        "<yellow>▶ 클릭하여 등록 모드로</yellow>",
                    )
                } else {
                    listOf(
                        "<gray>빈 칸에 아이템을 올린 뒤</gray>",
                        "<gray>확인을 누르면 등록됩니다.</gray>",
                        "",
                        "<yellow>▶ 클릭하여 제거 모드로</yellow>",
                    )
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            returnStaged(player)
            RewardListMenu(urb, box, page, !removeMode).open(player)
        }

        set(
            48,
            Icon.of(
                Material.COMPARATOR, "<yellow>확률 일괄 조정</yellow>",
                normalizerLore(),
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (box.rewards.isEmpty()) return@set
            if (event.isRightClick) promptSpread(player) else promptScale(player)
        }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>도움말</yellow>",
                "<gray>등록된 보상: <white>${box.rewards.size}개</white></gray>",
                "<gray>기본 확률: <white>${Numbers.chance(urb.config.defaults.itemChance)}%</white></gray>",
                "<gray>확률 합: <white>${Numbers.chance(box.rewards.sumOf { it.chance })}%</white></gray>",
                "",
                "<yellow>Q / F / Shift+우클릭</yellow><gray> : 확률·개수·티어 설정</gray>",
                "<dark_gray>확률은 소수점 두 자리까지 지정할 수 있습니다.</dark_gray>",
            )
        )

        set(
            53,
            Icon.confirm(
                if (removeMode) "<red>✔ 제거 확정</red>" else "<green>✔ 등록 확정</green>",
                listOf("<gray>변경 사항을 저장합니다.</gray>"),
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (removeMode) applyRemovals(player) else applyAdditions(player)
            urb.boxes.markDirty(box)
            RewardListMenu(urb, box, page, removeMode).open(player)
        }
    }

    override fun isSlotEditable(slot: Int): Boolean {
        if (slot >= CONTENT_SIZE) return false
        // Remove mode never hands out real items, so nothing is editable there.
        return !removeMode && !slotToReward.containsKey(slot)
    }

    override fun acceptsShiftInsert(): Boolean = !removeMode

    override fun onClose(event: InventoryCloseEvent) {
        (event.player as? Player)?.let { returnStaged(it) }
    }

    // --- confirm actions -------------------------------------------------------

    /** Captures every stack sitting in a slot that is not already a reward. */
    private fun applyAdditions(player: Player) {
        var added = 0
        for (slot in 0 until CONTENT_SIZE) {
            if (slotToReward.containsKey(slot)) continue
            val stack = inventory.getItem(slot) ?: continue
            if (stack.type.isAir) continue

            box.rewards.add(
                Reward(
                    item = urb.itemResolver.capture(stack),
                    chance = urb.config.defaults.itemChance,
                    minAmount = stack.amount,
                    maxAmount = stack.amount,
                )
            )
            inventory.setItem(slot, null)
            added++
        }
        if (added > 0) {
            player.sendMessage(Text.render("<green>보상 ${added}개를 등록했습니다.</green>", null, player))
        }
    }

    /** Deletes the rewards whose icons were lifted out of the window. */
    private fun applyRemovals(player: Player) {
        val removed = stagedRemovals.mapNotNull { slotToReward[it] }
        stagedRemovals.clear()
        if (removed.isEmpty()) return
        box.rewards.removeAll(removed.toSet())
        player.sendMessage(Text.render("<yellow>보상 ${removed.size}개를 제거했습니다.</yellow>", null, player))
    }

    /** Gives back anything the player staged but did not confirm. */
    private fun returnStaged(player: Player) {
        for (slot in 0 until CONTENT_SIZE) {
            if (slotToReward.containsKey(slot)) continue
            val stack = inventory.getItem(slot) ?: continue
            if (stack.type.isAir) continue
            inventory.setItem(slot, null)
            player.inventory.addItem(stack).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
        }
    }

    private fun switchPage(who: org.bukkit.entity.HumanEntity, target: Int) {
        val player = who as? Player ?: return
        returnStaged(player)
        RewardListMenu(urb, box, target, removeMode).open(player)
    }

    // --- chance normaliser -----------------------------------------------------

    /**
     * Two ways to retune a whole table at once.
     *
     * Scaling keeps the relative rarity an admin has already tuned and only moves the overall
     * generosity, which is what "the box feels too rich" actually calls for. Spreading throws
     * that away and makes every reward equally likely - useful when starting over, destructive
     * otherwise, so it sits behind a different click.
     */
    private fun normalizerLore(): List<String> {
        if (box.rewards.isEmpty()) {
            return listOf("<red>등록된 보상이 없습니다.</red>")
        }
        val sum = box.rewards.sumOf { it.chance }
        return listOf(
            "<gray>확률 합: <white>${Numbers.chance(sum)}%</white>  <dark_gray>(${box.rewards.size}종)</dark_gray></gray>",
            "<gray>평균 당첨: <white>${Numbers.chance(sum / 100.0)}종</white> / 오픈</gray>",
            "",
            "<gray>비율 유지 조정은 지금의 희귀도 차이를 그대로 두고</gray>",
            "<gray>전체 합만 목표치로 맞춥니다.</gray>",
            "",
            "<yellow>▶ 좌클릭: 목표 합으로 비율 유지 조정</yellow>",
            "<red>▶ 우클릭: 전부 같은 확률로 균등 분배</red>",
        )
    }

    private fun promptScale(player: Player) {
        val sum = box.rewards.sumOf { it.chance }
        urb.prompts.requestDouble(
            player,
            listOf(
                "<yellow>목표 확률 합을 입력하세요.</yellow>",
                "<gray>현재 합: <white>${Numbers.chance(sum)}%</white></gray>",
                "<gray>예: <white>200</white> 이면 오픈당 평균 2종이 당첨됩니다.</gray>",
            ),
            min = 0.01,
            max = 100.0 * box.rewards.size,
            onCancel = { reopen(player) },
        ) { target ->
            if (sum <= 0.0) {
                player.sendMessage(Text.render("<red>현재 확률 합이 0이라 비율을 유지할 수 없습니다.</red>", null, player))
                reopen(player)
                return@requestDouble
            }
            val factor = target / sum
            box.rewards.forEach { it.chance = it.chance * factor }
            urb.boxes.markDirty(box)

            val achieved = box.rewards.sumOf { it.chance }
            player.sendMessage(
                Text.render(
                    "<green>확률을 ${Numbers.chance(factor)}배로 조정했습니다. " +
                        "<gray>(합계 ${Numbers.chance(achieved)}%)</gray></green>",
                    null, player,
                )
            )
            // The clamp to 0.01..100 can stop a reward from reaching its share, so the achieved
            // total is reported rather than assumed.
            reopen(player)
        }
    }

    private fun promptSpread(player: Player) {
        urb.prompts.requestDouble(
            player,
            listOf(
                "<yellow>균등 분배할 목표 확률 합을 입력하세요.</yellow>",
                "<red>지금 설정된 개별 확률은 모두 사라집니다.</red>",
                "<gray>보상 ${box.rewards.size}종에 똑같이 나눠집니다.</gray>",
            ),
            min = 0.01,
            max = 100.0 * box.rewards.size,
            onCancel = { reopen(player) },
        ) { target ->
            val each = target / box.rewards.size
            box.rewards.forEach { it.chance = each }
            urb.boxes.markDirty(box)
            player.sendMessage(
                Text.render("<green>모든 보상을 ${Numbers.chance(each)}% 로 맞췄습니다.</green>", null, player)
            )
            reopen(player)
        }
    }

    private fun reopen(player: Player) = RewardListMenu(urb, box, page, removeMode).open(player)

    private fun rewardIcon(reward: Reward): ItemStack {
        val icon = urb.itemResolver.icon(reward.item)
        val amountText =
            if (reward.minAmount == reward.maxAmount) "${reward.minAmount}개"
            else "${reward.minAmount}~${reward.maxAmount}개"

        val lore = mutableListOf(
            "<gray>확률: <yellow>${Numbers.chance(reward.chance)}%</yellow></gray>",
            "<gray>수량: <white>$amountText</white></gray>",
            "<gray>저장 방식: <white>${if (reward.item.mode == StorageMode.REFERENCE) "참조 (자동 갱신)" else "스냅샷 (고정)"}</white></gray>",
            "<dark_gray>${reward.item.ref.serialize()}</dark_gray>",
        )
        reward.tier?.let { lore.add("<light_purple>티어: $it</light_purple>") }
        if (reward.announce) lore.add("<gold>★ 당첨 시 서버 공지</gold>")
        if (reward.commands.isNotEmpty()) lore.add("<aqua>실행 명령어 ${reward.commands.size}개</aqua>")
        if (!reward.giveItem) lore.add("<dark_gray>아이템 지급 없음 (명령어 전용)</dark_gray>")
        lore.addAll(icon.notes())
        lore.add("")
        lore.add(
            if (removeMode) "<red>▶ 클릭하여 빼낸 뒤 확인</red>"
            else "<yellow>▶ Q / F / Shift+우클릭: 상세 설정</yellow>"
        )

        return Icon.annotate(icon.stack, lore = lore)
    }

    companion object {
        private const val SIZE = 54
        private const val CONTENT_SIZE = 45

        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>아이템 설정 <gray>|</gray> ${box.name}</dark_gray>")
    }
}
