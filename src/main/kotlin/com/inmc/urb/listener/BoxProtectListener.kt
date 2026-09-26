package com.inmc.urb.listener

import com.inmc.urb.Urb
import com.inmc.urb.util.BlockKey
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.ChunkLoadEvent

/**
 * Keeps a spawned box in place until it is opened or expires.
 *
 * Only despawning is allowed to remove the block, so mining, TNT, pistons, flowing lava,
 * fire and endermen all bounce off. Also the hook that materialises queued spawns and
 * finishes deferred block restores when a chunk comes back.
 */
class BoxProtectListener(private val urb: Urb) : Listener {

    private fun isBox(block: Block): Boolean =
        urb.ready && urb.spawns.at(BlockKey.of(block)) != null

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (isBox(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (isBox(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { isBox(it) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { isBox(it) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any { isBox(it) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any { isBox(it) }) event.isCancelled = true
    }

    /** Stops water or lava from washing a box away. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFlow(event: BlockFromToEvent) {
        if (isBox(event.toBlock)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (isBox(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        if (isBox(event.block)) event.isCancelled = true
    }

    /** Endermen carrying blocks, falling sand landing, sheep eating grass. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (isBox(event.block)) event.isCancelled = true
    }

    /** Queued spawns and deferred restores both resolve here (see BoxSpawnService). */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!urb.ready) return
        urb.spawns.onChunkLoad(event.chunk)
    }
}
