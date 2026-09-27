package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import com.inmc.urb.command.UrbCommand
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * `/urb info` - the player-facing chance table.
 *
 * Read only, no permission needed, and each box can hide itself with `open.preview: false`.
 * Rewards are sorted rarest first so the headline drop is easy to spot.
 */
class PreviewMenu(
    urb: Urb,
    private val box: RandomBox,
    private val viewerIsAdmin: Boolean = false,
    private var page: Int = 0,
) : Menu(urb, SIZE, title(box)) {

    override fun draw() {
        clear()

        val rewards = box.rewards.sortedBy { it.chance }
        val pages = Paging.pageCount(rewards.size, PER_PAGE)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(rewards, page, PER_PAGE).forEachIndexed { index, reward ->
            val amount =
                if (reward.minAmount == reward.maxAmount) "${reward.minAmount}개"
                else "${reward.minAmount}~${reward.maxAmount}개"

            val lore = mutableListOf(
                "<gray>확률: <yellow>${Numbers.chance(reward.chance)}%</yellow></gray>",
                "<gray>수량: <white>$amount</white></gray>",
            )
            if (reward.announce) lore.add("<gold>★ 획득 시 서버에 공지됩니다</gold>")
            if (!reward.giveItem) lore.add("<dark_gray>아이템 대신 효과가 지급됩니다</dark_gray>")

            // annotate, not relabel: a custom item's own stat lore is the part players most
            // need to see in a chance table.
            val icon = urb.itemResolver.icon(reward.item)
            // "stored as a snapshot" is an admin implementation detail, not player-facing info.
            lore.addAll(if (viewerIsAdmin) icon.notes() else icon.notes().filter { !icon.resolved })
            set(index, Icon.annotate(icon.stack, lore = lore))
        }

        for (slot in PER_PAGE until SIZE) set(slot, Icon.EDGE)

        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> repage(event.whoClicked, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> repage(event.whoClicked, page + 1) }

        set(
            49,
            blockIcon(
                box, box.displayName,
                buildList {
                    addAll(box.lore)
                    if (box.lore.isNotEmpty()) add("")
                    add("<gray>등록된 아이템: <white>${box.rewards.size}종</white></gray>")
                    if (box.usesTiers) {
                        // With tiers the box-level range covers only the untiered pool, so a
                        // single "1~2개" line here would understate what the player gets.
                        val pools = com.inmc.urb.box.TierForecast.pools(box.rollGroups())
                        val distribution = com.inmc.urb.box.TierForecast.distribution(pools)
                        val low = distribution.indexOfFirst { it > 0.0005 }.coerceAtLeast(0)
                        val high = distribution.indexOfLast { it > 0.0005 }.coerceAtLeast(low)
                        add("<gray>한 번에 나오는 개수: <white>$low ~ ${high}개</white></gray>")
                        for (pool in pools) {
                            add("<dark_gray>  · ${pool.label}: ${pool.minRolls}~${pool.maxRolls}개</dark_gray>")
                        }
                    } else {
                        add("<gray>한 번에 나오는 개수: <white>${box.minRolls} ~ ${box.maxRolls}개</white></gray>")
                    }
                    box.keyItem?.let { add("<gray>필요한 열쇠: <white>${it.label()}</white></gray>") }
                    if (box.moneyCost > 0) add("<gray>필요한 돈: <white>${Numbers.money(box.moneyCost)}원</white></gray>")
                    add("")
                    add("<dark_gray>각 아이템은 독립적으로 추첨되며,</dark_gray>")
                    add(
                        if (box.usesTiers) "<dark_gray>등급마다 따로 뽑습니다.</dark_gray>"
                        else "<dark_gray>최소 ${box.minRolls}개는 반드시 나옵니다.</dark_gray>"
                    )
                },
            )
        )

        set(50, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }

        if (rewards.isEmpty()) {
            set(22, Icon.of(Material.BARRIER, "<red>등록된 아이템이 없습니다</red>"))
        }
    }

    private fun repage(who: org.bukkit.entity.HumanEntity, target: Int) {
        val player = who as? Player ?: return
        PreviewMenu(urb, box, viewerIsAdmin, target).open(player)
    }

    companion object {
        private const val SIZE = 54
        private const val PER_PAGE = 45

        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>확률표 <gray>|</gray> ${box.name}</dark_gray>")

        /** Box picker shown by `/urb info` with no argument. */
        fun chooser(urb: Urb, viewer: Player): Menu = ChooserMenu(urb, viewer.hasPermission(UrbCommand.PERMISSION))
    }

    private class ChooserMenu(urb: Urb, private val viewerIsAdmin: Boolean) :
        Menu(urb, 54, Text.renderFlat("<dark_gray>확률표 <gray>|</gray> 상자 선택</dark_gray>")) {

        override fun draw() {
            clear()

            val boxes = urb.boxes.all().filter { it.previewEnabled || viewerIsAdmin }

            boxes.take(45).forEachIndexed { index, box ->
                set(
                    index,
                    blockIcon(
                        box, box.displayName,
                        buildList {
                            addAll(box.lore)
                            if (box.lore.isNotEmpty()) add("")
                            add("<gray>아이템 <white>${box.rewards.size}</white>종</gray>")
                            if (!box.previewEnabled) add("<red>비공개 (관리자만 보임)</red>")
                            add("")
                            add("<yellow>▶ 클릭하여 확률표 보기</yellow>")
                        },
                    )
                ) { event ->
                    (event.whoClicked as? Player)?.let { PreviewMenu(urb, box, viewerIsAdmin).open(it) }
                }
            }

            for (slot in 45 until 54) set(slot, Icon.EDGE)
            set(49, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }

            if (boxes.isEmpty()) {
                set(22, Icon.of(Material.BARRIER, "<red>공개된 상자가 없습니다</red>"))
            }
        }
    }
}
