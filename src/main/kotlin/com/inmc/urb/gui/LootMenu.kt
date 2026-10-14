package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.LootRoller
import com.inmc.urb.box.RandomBox
import kr.inmc.core.util.Text
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import java.util.Random

/**
 * The 6x9 window a GUI-mode box opens into.
 *
 * Contents land in random slots rather than packed from the top left, per spec §67
 * ("상자 칸 6x9에서 랜덤한 칸에 나타나야됨"). Anything still inside when the window closes is
 * dropped at the player's feet, matching the previous plugin's behaviour so loot is never
 * silently lost. 접속을 끊어 닫힌 경우와 고정 설치 상자의 넘침은 우편 창구가 있으면 우편으로(2026-10-10).
 */
class LootMenu(
    urb: Urb,
    private val box: RandomBox,
    items: List<ItemStack>,
    /**
     * Permanent boxes cost a key or money to open, so anything left in the window when it
     * closes goes into the player's inventory instead of onto the floor - a paid reward must
     * not be lost to a misclick or a full hotbar.
     */
    private val returnToInventory: Boolean = false,
) : Menu(urb, SIZE, Text.renderFlat(box.displayName)) {

    private val placement: Map<Int, ItemStack> = buildPlacement(items)

    private fun buildPlacement(items: List<ItemStack>): Map<Int, ItemStack> {
        if (items.isEmpty()) return emptyMap()
        val slots = LootRoller.randomSlots(items.size, SIZE, Random())
        return slots.zip(items).toMap()
    }

    override fun draw() {
        clear()
        placement.forEach { (slot, stack) -> set(slot, stack) }
    }

    override fun isSlotEditable(slot: Int): Boolean = true

    override fun acceptsShiftInsert(): Boolean = false

    override fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val left = event.inventory.contents.filterNotNull().filter { !it.type.isAir }.map { it.clone() }
        event.inventory.clear()
        if (left.isEmpty()) return
        // 창을 연 채 나갔다 — 고르지 못한 보상이다. 나가는 사람 발밑에 떨구면 주인 없이 사라지니 우편으로(사용자 결정 2026-10-10).
        if (event.reason == InventoryCloseEvent.Reason.DISCONNECT && urb.opens.mail(player.uniqueId, box, left)) return
        if (!returnToInventory) {
            for (stack in left) player.world.dropItemNaturally(player.location, stack)
            return
        }
        // Only what genuinely will not fit leaves the inventory - 우편이 있으면 우편으로, 없으면 바닥에.
        val overflow = left.flatMap { player.inventory.addItem(it).values }
        if (overflow.isEmpty()) return
        if (urb.opens.mail(player.uniqueId, box, overflow)) {
            urb.messages.send(player, "open-inventory-full-mail")
            return
        }
        for (stack in overflow) player.world.dropItemNaturally(player.location, stack)
    }

    companion object {
        const val SIZE = 54
    }
}
