package com.inmc.urb

import com.inmc.urb.box.OpenRecords
import com.inmc.urb.box.RandomBox
import com.inmc.urb.box.SpawnMode
import com.inmc.urb.util.BlockKey
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-player open allowance, and the settings check that says why a box never appears.
 *
 * Both are things whose failure is silent: a wrong period locks a player out of a box with no
 * error anywhere, and a box with no spawn target simply stops existing.
 */
class OpenLimitTest {

    private val second = 1_000L
    private val day = 86_400L

    // --- allowance periods -----------------------------------------------------

    @Test
    fun `a zero reset means the cap is for life`() {
        // However long ago they opened it, the period never rolls over.
        assertFalse(OpenRecords.isPeriodOver(start = 1L, resetSeconds = 0L, now = Long.MAX_VALUE / 2))
    }

    @Test
    fun `a period that has not elapsed is still running`() {
        val start = 1_000_000L
        assertFalse(OpenRecords.isPeriodOver(start, day, start + day * second - 1))
    }

    @Test
    fun `a period ends exactly on its boundary`() {
        val start = 1_000_000L
        assertTrue(OpenRecords.isPeriodOver(start, day, start + day * second))
        assertTrue(OpenRecords.isPeriodOver(start, day, start + day * second + 1))
    }

    /** A player with no record has no period running, so nothing to expire. */
    @Test
    fun `an unset start never counts as expired`() {
        assertFalse(OpenRecords.isPeriodOver(start = 0L, resetSeconds = day, now = Long.MAX_VALUE / 2))
    }

    // --- spawn diagnosis -------------------------------------------------------

    private fun box(configure: RandomBox.() -> Unit): RandomBox = RandomBox("t").apply {
        // A box needs loot before any other complaint is worth making.
        rewards.add(
            com.inmc.urb.box.Reward(
                item = kr.inmc.core.item.StoredItem(
                    ref = kr.inmc.core.item.ItemRef.Vanilla(org.bukkit.Material.DIAMOND),
                    material = org.bukkit.Material.DIAMOND,
                ),
            )
        )
        configure()
    }

    @Test
    fun `a fully configured random-area box has no complaint`() {
        val box = box {
            spawnMode = SpawnMode.RANDOM_AREA
            area = com.inmc.urb.box.SpawnArea(x1 = -100, z1 = -100, x2 = 100, z2 = 100)
            autoSpawnIntervalSeconds = 600L
        }
        assertNull(box.autoSpawnProblem())
    }

    /** The exact state the live test box was found in: right mode, no coordinates. */
    @Test
    fun `fixed points with no coordinates is reported`() {
        val box = box {
            spawnMode = SpawnMode.FIXED_POINTS
            autoSpawnIntervalSeconds = 10L
        }
        val problem = box.autoSpawnProblem()
        assertTrue(problem != null && problem.contains("좌표"), "좌표 없음이 보고되지 않았습니다: $problem")
    }

    @Test
    fun `random area with no area is reported`() {
        val box = box {
            spawnMode = SpawnMode.RANDOM_AREA
            autoSpawnIntervalSeconds = 10L
        }
        val problem = box.autoSpawnProblem()
        assertTrue(problem != null && problem.contains("영역"), "영역 없음이 보고되지 않았습니다: $problem")
    }

    @Test
    fun `an empty loot table outranks every other complaint`() {
        val box = RandomBox("t").apply { spawnMode = SpawnMode.FIXED_POINTS }
        assertEquals("등록된 보상이 없습니다", box.autoSpawnProblem())
    }

    @Test
    fun `a disabled box says so first`() {
        val box = box { enabled = false }
        assertEquals("상자가 비활성화되어 있습니다", box.autoSpawnProblem())
    }

    /** A permanent installation is placed once, so it has no interval to complain about. */
    @Test
    fun `a permanent box with points is fine at any interval`() {
        val box = box {
            spawnMode = SpawnMode.PERMANENT
            fixedPoints.add(BlockKey("world", 0, 64, 0))
            autoSpawnIntervalSeconds = 0L
        }
        assertNull(box.autoSpawnProblem())
    }

    // --- per-location record keys ------------------------------------------------

    @Test
    fun `box scoped key has two segments`() {
        val id = java.util.UUID.randomUUID()
        assertEquals("$id|gift", OpenRecords.recordKey(id, "gift", null))
    }

    @Test
    fun `location scoped key carries world and xyz`() {
        val id = java.util.UUID.randomUUID()
        val at = BlockKey("world", 10, 64, -5)
        assertEquals("$id|gift|world/10/64/-5", OpenRecords.recordKey(id, "gift", at))
    }

    @Test
    fun `different locations scope different records`() {
        val id = java.util.UUID.randomUUID()
        val a = OpenRecords.recordKey(id, "gift", BlockKey("world", 0, 64, 0))
        val b = OpenRecords.recordKey(id, "gift", BlockKey("world", 1, 64, 0))
        assertTrue(a != b)
        // 상자 단위 키와도 겹치지 않는다 — 옛 기록이 새 기록에 섞이지 않는다.
        assertTrue(OpenRecords.recordKey(id, "gift", null) != a)
    }

    @Test
    fun `a zero interval is reported for a spawning box`() {
        val box = box {
            spawnMode = SpawnMode.RANDOM_AREA
            area = com.inmc.urb.box.SpawnArea(x1 = -10, z1 = -10, x2 = 10, z2 = 10)
            autoSpawnIntervalSeconds = 0L
        }
        assertEquals("자동 생성 주기가 0입니다", box.autoSpawnProblem())
    }
}
