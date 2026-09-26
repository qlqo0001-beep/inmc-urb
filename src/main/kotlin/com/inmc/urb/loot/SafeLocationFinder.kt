package com.inmc.urb.loot

import com.inmc.urb.box.RandomBox
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import java.util.Random

/**
 * Decides where a box may legally sit (spec §59, §61).
 *
 * The rules, in the spec's own words: no spawning buried in dirt, floating in mid-air, or on
 * top of water or lava; caves are allowed. And in random-area mode the box must not destroy
 * decoration - flowers, carpets and the like - so the target block has to be genuinely empty
 * rather than merely replaceable.
 *
 * The previous plugin only ever used `getHighestBlockYAt`, which meant it happily dropped
 * chests onto oceans and lava lakes and could never place one in a cave.
 *
 * Every method here reads blocks, so callers must only invoke them for loaded chunks and
 * from the main thread.
 */
class SafeLocationFinder {

    /**
     * Finds a legal Y for the column at (x, z), or null when the column is unusable.
     * Only ever called with a loaded chunk.
     */
    fun findY(world: World, x: Int, z: Int, box: RandomBox, rng: Random): Int? {
        val minY = box.effectiveMinY(world.minHeight)
        val maxY = box.effectiveMaxY(world.maxHeight - 2)
        if (minY > maxY) return null

        surfaceCandidate(world, x, z)?.let { surfaceY ->
            if (surfaceY in minY..maxY && isPlaceable(world, x, surfaceY, z)) return surfaceY
        }

        if (!box.allowCaveSpawn) return null
        return caveCandidate(world, x, z, minY, maxY, rng)
    }

    /**
     * One block above the ground.
     *
     * [HeightMap.MOTION_BLOCKING_NO_LEAVES] rather than `WORLD_SURFACE`: leaves report
     * `Material.isSolid() == true`, so a surface lookup in a forest returns the top of the
     * canopy and the box ends up perched on a treetop. Skipping leaves finds the real ground
     * underneath instead. Water and lava still block motion, so an ocean column still reports
     * its surface and is still rejected by [isSolidGround].
     */
    private fun surfaceCandidate(world: World, x: Int, z: Int): Int? {
        val ground = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
        if (ground <= world.minHeight) return null
        return ground + 1
    }

    /**
     * Scans downwards for air pockets sitting on solid ground. Candidates are collected and
     * one is picked at random so repeated spawns in the same column do not stack up in the
     * same cave mouth. The scan is capped so a deep world cannot turn this into a long loop.
     */
    private fun caveCandidate(world: World, x: Int, z: Int, minY: Int, maxY: Int, rng: Random): Int? {
        val top = minOf(maxY, world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES))
        val candidates = ArrayList<Int>(MAX_CANDIDATES)
        var scanned = 0
        var y = top
        while (y >= minY && scanned < MAX_SCAN) {
            scanned++
            if (isPlaceable(world, x, y, z)) {
                candidates.add(y)
                if (candidates.size >= MAX_CANDIDATES) break
                // skip past this pocket so we do not return two spots one block apart
                y -= 3
                continue
            }
            y--
        }
        if (candidates.isEmpty()) return null
        return candidates[rng.nextInt(candidates.size)]
    }

    /**
     * The actual rule set for one position.
     *
     * - the box block and the block above it must be true air (so nothing is destroyed and
     *   the box is not buried)
     * - the block below must be solid, and must not be water or lava (no floating, no
     *   spawning on top of a liquid)
     */
    fun isPlaceable(world: World, x: Int, y: Int, z: Int): Boolean {
        if (y - 1 < world.minHeight || y + 1 >= world.maxHeight) return false

        val target = world.getBlockAt(x, y, z)
        if (!isEmpty(target)) return false
        if (!isEmpty(world.getBlockAt(x, y + 1, z))) return false

        val below = world.getBlockAt(x, y - 1, z)
        return isSolidGround(below)
    }

    /** True air only - flowers, carpets, snow layers and grass are deliberately excluded. */
    fun isEmpty(block: Block): Boolean = when (block.type) {
        Material.AIR, Material.CAVE_AIR, Material.VOID_AIR -> true
        else -> false
    }

    /**
     * Ground a box may stand on.
     *
     * The waterlogged test is the one that used to be missing. `isLiquid` only reports true for
     * an actual water or lava block, so a submerged slab, stair or fence read as perfectly good
     * ground and a box could be placed sitting in water - the very thing spec §61 forbids. The
     * line that stood here instead (`!blockData.material.isAir`) could never be false, because
     * `type.isSolid` had already excluded air.
     */
    private fun isSolidGround(block: Block): Boolean {
        val type = block.type
        if (type == Material.WATER || type == Material.LAVA) return false
        if (block.isLiquid) return false
        if (!type.isSolid) return false

        val data = block.blockData
        return !(data is org.bukkit.block.data.Waterlogged && data.isWaterlogged)
    }

    companion object {
        private const val MAX_SCAN = 192
        private const val MAX_CANDIDATES = 8
    }
}
