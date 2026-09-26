package com.inmc.urb.util

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block

/**
 * Identity of a single block position, used as the key of the spawned-box index.
 *
 * The original plugin identified boxes by reading a [org.bukkit.persistence.PersistentDataContainer]
 * off the block's `TileState`, which meant only block entities (chests, barrels, ...) could
 * ever be a box. Keying an in-memory map by position instead lifts that restriction - any
 * Material can be a box - and makes the lookup an O(1) map hit with no block state read.
 */
data class BlockKey(val world: String, val x: Int, val y: Int, val z: Int) {

    val chunkKey: Long get() = chunkKey(x shr 4, z shr 4)

    fun toLocation(world: World): Location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

    override fun toString(): String = "$world/$x/$y/$z"

    companion object {
        fun of(location: Location): BlockKey =
            BlockKey(location.world.name, location.blockX, location.blockY, location.blockZ)

        fun of(block: Block): BlockKey =
            BlockKey(block.world.name, block.x, block.y, block.z)

        fun chunkKey(chunkX: Int, chunkZ: Int): Long =
            (chunkX.toLong() and 0xffffffffL) or ((chunkZ.toLong() and 0xffffffffL) shl 32)

        fun parse(raw: String): BlockKey? {
            val parts = raw.split('/')
            if (parts.size != 4) return null
            val x = parts[1].toIntOrNull() ?: return null
            val y = parts[2].toIntOrNull() ?: return null
            val z = parts[3].toIntOrNull() ?: return null
            return BlockKey(parts[0], x, y, z)
        }
    }
}
