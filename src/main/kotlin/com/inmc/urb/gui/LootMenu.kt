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
 * silently lost.
 */
class LootMenu(
    urb: Urb,
    box: RandomBox,
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
        for (stack in event.inventory.contents) {
            if (stack == null || stack.type.isAir) continue
            if (returnToInventory) {
                // Only what genuinely will not fit hits the ground.
                player.inventory.addItem(stack).values.forEach {
                    player.world.dropItemNaturally(player.location, it)
                }
            } else {
                player.world.dropItemNaturally(player.location, stack)
            }
        }
        event.inventory.clear()
    }

    companion object {
        const val SIZE = 54
    }
}
