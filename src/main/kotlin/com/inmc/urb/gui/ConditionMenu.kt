package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Open conditions (spec §74-77): the capsule item, the key item and the money cost.
 *
 * Items are registered from the player's main hand rather than by dragging into a slot -
 * there is then no window in which the admin's item could be swallowed by the GUI.
 */
class ConditionMenu(urb: Urb, private val box: RandomBox) : Menu(urb, 45, title(box)) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val capsule = box.capsuleItem
        set(
            20,
            if (capsule != null) {
                val icon = urb.itemResolver.icon(capsule)
                Icon.annotate(
                    icon.stack,
                    name = "<yellow>캡슐 아이템</yellow>",
                    lore = listOf(
                        "<gray>현재: <white>${capsule.label()}</white></gray>",
                        "<dark_gray>${capsule.ref.serialize()}</dark_gray>",
                    ) + icon.notes() + listOf(
                        "",
                        "<gray>손에 들고 우클릭하면 이 상자가 열립니다.</gray>",
                        "<dark_gray>아이템 종류와 이름으로 인식합니다.</dark_gray>",
                        "",
                        "<yellow>▶ 좌클릭: 손에 든 아이템으로 변경</yellow>",
                        "<red>▶ 우클릭: 제거</red>",
                    ),
                )
            } else {
                Icon.of(
                    Material.ENDER_CHEST, "<yellow>캡슐 아이템</yellow>",
                    "<gray>현재: <white>없음</white></gray>",
                    "<gray>손에 들고 우클릭하면 이 상자가 열립니다.</gray>",
                    "",
                    "<yellow>▶ 좌클릭: 손에 든 아이템으로 설정</yellow>",
                )
            }
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            val hand = player.inventory.itemInMainHand
            if (!event.isRightClick && hand.type.isAir) {
                player.sendMessage(Text.render("<red>손에 아이템을 든 뒤 클릭하세요.</red>", null, player))
                return@set
            }
            // 커스텀아이템이 있으면 그 아이템을 커스텀아이템으로 만들어 "상자 캡슐" 역할을 붙인다(양쪽에서 보인다).
            if (!com.inmc.urb.box.UrbRoles.set(urb, box, com.inmc.urb.box.UrbRoles.CAPSULE, if (event.isRightClick) null else hand)) {
                box.capsuleItem = if (event.isRightClick) null else urb.itemResolver.capture(hand)
            }
            save()
            urb.boxes.rebuildCapsuleIndex()
            redraw(player)
        }

        val key = box.keyItem
        set(
            22,
            if (key != null) {
                val icon = urb.itemResolver.icon(key)
                Icon.annotate(
                    icon.stack,
                    name = "<yellow>열쇠 아이템</yellow>",
                    lore = listOf(
                        "<gray>현재: <white>${key.label()}</white></gray>",
                        "<dark_gray>${key.ref.serialize()}</dark_gray>",
                    ) + icon.notes() + listOf(
                        "",
                        "<gray>인벤토리에 있어야 상자가 열리며,</gray>",
                        "<gray>열 때 1개가 소모됩니다.</gray>",
                        "",
                        "<yellow>▶ 좌클릭: 손에 든 아이템으로 변경</yellow>",
                        "<red>▶ 우클릭: 제거</red>",
                    ),
                )
            } else {
                Icon.of(
                    Material.TRIPWIRE_HOOK, "<yellow>열쇠 아이템</yellow>",
                    "<gray>현재: <white>없음</white></gray>",
                    "<gray>설정하면 상자를 열 때 1개가 소모됩니다.</gray>",
                    "",
                    "<yellow>▶ 좌클릭: 손에 든 아이템으로 설정</yellow>",
                )
            }
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            val hand = player.inventory.itemInMainHand
            if (!event.isRightClick && hand.type.isAir) {
                player.sendMessage(Text.render("<red>손에 아이템을 든 뒤 클릭하세요.</red>", null, player))
                return@set
            }
            if (!com.inmc.urb.box.UrbRoles.set(urb, box, com.inmc.urb.box.UrbRoles.KEY, if (event.isRightClick) null else hand)) {
                box.keyItem = if (event.isRightClick) null else urb.itemResolver.capture(hand)
            }
            save(); redraw(player)
        }

        set(
            24,
            Icon.of(
                Material.GOLD_INGOT, "<yellow>소모될 돈</yellow>",
                buildList {
                    add("<gray>현재: <white>${if (box.moneyCost > 0) Numbers.money(box.moneyCost) + "원" else "없음"}</white></gray>")
                    if (box.moneyCost > 0 && !urb.economy.isEnabled) {
                        add("<red>⚠ Vault 경제 플러그인이 없어 적용되지 않습니다.</red>")
                    }
                    add("")
                    add("<yellow>▶ 좌클릭: 금액 입력</yellow>")
                    add("<red>▶ 우클릭: 제거</red>")
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                box.moneyCost = 0.0
                save(); redraw(player)
                return@set
            }
            urb.prompts.requestDouble(
                player,
                listOf(
                    "<yellow>상자를 열 때 소모할 금액을 입력하세요.</yellow>",
                    "<gray>0 을 입력하면 사용하지 않습니다.</gray>",
                ),
                min = 0.0, max = 1_000_000_000.0,
                onCancel = { reopen(player) },
            ) { value ->
                box.moneyCost = value
                save(); reopen(player)
            }
        }

        set(36, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { BoxManageMenu(urb, box).open(it) }
        }
        set(44, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    private fun save() = urb.boxes.markDirty(box)

    private fun redraw(player: Player) {
        refresh()
        player.updateInventory()
    }

    private fun reopen(player: Player) = ConditionMenu(urb, box).open(player)

    companion object {
        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>오픈 조건 <gray>|</gray> ${box.name}</dark_gray>")
    }
}
