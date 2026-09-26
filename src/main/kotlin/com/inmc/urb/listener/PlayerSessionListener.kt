package com.inmc.urb.listener

import com.inmc.urb.Urb
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * Per-player session upkeep: movement cancels an in-progress open (spec §111), and quitting,
 * dying or teleporting tears down anything the player had going.
 *
 * Cancellation is event-driven rather than polled so it feels immediate, and it costs nothing
 * when no one is opening a box - the `hasSession` check is a single map lookup.
 */
class PlayerSessionListener(private val urb: Urb) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!urb.opens.hasSession(event.player.uniqueId)) return
        val from = event.from
        val to = event.to
        // Looking around is fine; stepping off the block is not.
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        urb.opens.cancel(event.player.uniqueId, notify = true)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        urb.opens.cancel(event.player.uniqueId, notify = false)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        urb.opens.cancel(event.entity.uniqueId, notify = false)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        urb.opens.cancel(id, notify = false)
        urb.prompts.cancel(id)
        urb.tracking.forget(id)
        urb.simulations.forget(id)
        // A boss bar the player still holds would still be there when they log back in.
        urb.bossBars.hideAll(id)
    }

    /**
     * Chat input for the admin GUI. Runs off the main thread, so [kr.inmc.core.input.ChatPrompt]
     * hops back before invoking the callback.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!urb.prompts.isWaiting(player.uniqueId)) return
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (urb.prompts.submit(player, text)) {
            event.isCancelled = true
        }
    }
}
