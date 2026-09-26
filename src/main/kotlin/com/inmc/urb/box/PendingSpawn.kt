package com.inmc.urb.box

import com.inmc.urb.util.BlockKey
import java.util.UUID

/**
 * A spawn that has been announced but whose chunk is not loaded yet.
 *
 * This is deliberately *not* resolved by force-loading the chunk. A random box is a supply
 * drop: the broadcast tells players where to go, and the box materialises when someone
 * actually gets there and the chunk loads naturally. Force-generating far-away chunks is
 * exactly the server hitch the queue exists to avoid.
 *
 * [expiresAt] comes from the global `spawn.pending-max-age` so the queue cannot grow without
 * bound and the box's max-count slot is eventually released.
 */
class PendingSpawn(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val boxName: String,
    val world: String,
    val x: Int,
    val z: Int,
    /** Set for fixed-point spawns, where the exact Y is already known. */
    val forcedY: Int? = null,
    /** Fixed-point spawns clear whatever block is in the way (spec §59). */
    val replaceBlock: Boolean = false,
    val announcedAt: Long,
    var expiresAt: Long,
) {

    val chunkX: Int get() = x shr 4
    val chunkZ: Int get() = z shr 4
    val chunkKey: Long get() = BlockKey.chunkKey(chunkX, chunkZ)

    fun isExpired(now: Long): Boolean = expiresAt in 1..now

    fun remainingSeconds(now: Long): Long =
        if (expiresAt <= 0L) -1L else ((expiresAt - now) / 1000L).coerceAtLeast(0L)
}
