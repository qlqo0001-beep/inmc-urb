package com.inmc.urb.box

import com.inmc.urb.util.BlockKey
import java.util.UUID

/**
 * A box that physically exists in the world.
 *
 * [originalBlockData] is the serialized [org.bukkit.block.data.BlockData] of whatever stood
 * there before, so despawning restores the terrain exactly instead of punching an air hole.
 * [expiresAt] is an absolute epoch timestamp rather than a countdown, which is what lets a
 * box survive a restart and still despawn on schedule.
 */
class SpawnedBox(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val boxName: String,
    val key: BlockKey,
    val originalBlockData: String,
    /**
     * The material that was *actually written* to the world, read back after placement.
     *
     * Restoring compares against this rather than against the box's configured block, because
     * the two drift apart in two ordinary situations and a mismatch silently cancels the
     * restore - leaving the box block in the world for good:
     *
     *  - an admin edits the box's block while boxes of that type are already standing
     *  - the box uses a custom block, where the configured [org.bukkit.Material] is only an
     *    icon fallback and the plugin that owns the block places something else entirely
     *
     * null for entries written before this was recorded; the caller falls back to the old
     * comparison so existing state files keep behaving exactly as they did.
     */
    val placedMaterial: org.bukkit.Material? = null,
    val spawnedAt: Long,
    /** Absolute epoch millis; 0 means the box never expires on its own. */
    var expiresAt: Long,
    /** GUI-mode boxes vanish after a single open, taken items or not (spec §72). */
    var opened: Boolean = false,
) {

    val chunkKey: Long get() = key.chunkKey

    fun isExpired(now: Long): Boolean = expiresAt in 1..now

    fun remainingSeconds(now: Long): Long =
        if (expiresAt <= 0L) -1L else ((expiresAt - now) / 1000L).coerceAtLeast(0L)
}
