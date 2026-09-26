package com.inmc.urb.listener

import com.inmc.urb.Urb
import com.inmc.urb.util.BlockKey
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Turns clicks into box opens.
 *
 * A spawned box is found by looking its position up in the in-memory index, not by reading
 * block state, which is what allows any Material to be a box (spec §53 - a box configured as
 * stone appears as stone and still opens a custom window). The interaction is always
 * cancelled on a hit so vanilla never gets a chance to show its own chest UI.
 */
class BoxInteractListener(private val urb: Urb) : Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (!urb.ready) return
        val player = event.player

        // 1. Capsule item in hand (spec §75)
        if (event.hand == EquipmentSlot.HAND &&
            (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK)
        ) {
            val hand = event.item
            val box = urb.boxes.byCapsule(hand)
            if (box != null && hand != null) {
                event.isCancelled = true
                urb.opens.openViaCapsule(player, box) {
                    if (hand.amount <= 1) {
                        player.inventory.setItemInMainHand(null)
                    } else {
                        hand.amount -= 1
                        player.inventory.setItemInMainHand(hand)
                    }
                }
                return
            }
        }

        // 2. A box block in the world
        val block = event.clickedBlock ?: return
        val spawned = urb.spawns.at(BlockKey.of(block)) ?: return

        // Block every vanilla interaction with the box, including left-click and off-hand.
        event.isCancelled = true

        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        if (player.isSneaking) return

        val box = urb.boxes.get(spawned.boxName) ?: return
        urb.opens.beginBlockOpen(player, spawned, box)
    }
}
