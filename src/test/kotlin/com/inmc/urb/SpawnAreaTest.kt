package com.inmc.urb

import com.inmc.urb.box.AreaShape
import com.inmc.urb.box.OpenMode
import com.inmc.urb.box.SpawnArea
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnAreaTest {

    // --- rectangle -------------------------------------------------------------

    @Test
    fun `rectangle points stay inside the corners`() {
        val rng = Random(1)
        val area = SpawnArea(x1 = -50, z1 = 20, x2 = 30, z2 = -10)
        repeat(50_000) {
            val point = area.randomPoint(rng)!!
            assertTrue(point[0] in -50..30, "x 가 범위를 벗어났습니다: ${point[0]}")
            assertTrue(point[1] in -10..20, "z 가 범위를 벗어났습니다: ${point[1]}")
        }
    }

    /** Corner order must not matter - the GUI lets an admin set either one first. */
    @Test
    fun `rectangle is independent of corner order`() {
        val a = SpawnArea(x1 = -50, z1 = 20, x2 = 30, z2 = -10)
        val b = SpawnArea(x1 = 30, z1 = -10, x2 = -50, z2 = 20)
        assertEquals(a.minX, b.minX)
        assertEquals(a.maxX, b.maxX)
        assertEquals(a.minZ, b.minZ)
        assertEquals(a.maxZ, b.maxZ)
        assertEquals(a.describe(), b.describe())
    }

    @Test
    fun `rectangle covers both extremes`() {
        val rng = Random(2)
        val area = SpawnArea(x1 = 0, z1 = 0, x2 = 4, z2 = 4)
        val seen = HashSet<Int>()
        repeat(10_000) { seen.add(area.randomPoint(rng)!![0]) }
        assertEquals(setOf(0, 1, 2, 3, 4), seen)
    }

    // --- circle ----------------------------------------------------------------

    @Test
    fun `circle points stay inside the radius`() {
        val rng = Random(3)
        val radius = 500
        val area = SpawnArea(shape = AreaShape.CIRCLE, centerX = 100, centerZ = -250, radius = radius)

        repeat(100_000) {
            val point = area.randomPoint(rng)!!
            val dx = (point[0] - 100).toDouble()
            val dz = (point[1] + 250).toDouble()
            val distance = Math.sqrt(dx * dx + dz * dz)
            // +1 tolerance: coordinates are rounded to whole blocks.
            assertTrue(distance <= radius + 1, "반지름 밖으로 나갔습니다: $distance")
        }
    }

    /**
     * Points must be spread evenly by *area*, not by radius. Half a disc's area lies inside
     * r/sqrt(2), so roughly half the samples should land there - sampling the radius linearly
     * instead would bunch about 71% of them into that inner circle.
     */
    @Test
    fun `circle sampling is uniform by area`() {
        val rng = Random(4)
        val radius = 1000
        val area = SpawnArea(shape = AreaShape.CIRCLE, centerX = 0, centerZ = 0, radius = radius)
        val halfAreaRadius = radius / Math.sqrt(2.0)

        var inner = 0
        val trials = 200_000
        repeat(trials) {
            val point = area.randomPoint(rng)!!
            val distance = Math.sqrt(point[0].toDouble() * point[0] + point[1].toDouble() * point[1])
            if (distance <= halfAreaRadius) inner++
        }

        val ratio = inner.toDouble() / trials
        assertTrue(ratio in 0.48..0.52, "면적 균등 분포가 아닙니다 - 안쪽 비율 $ratio (기대 0.50)")
    }

    @Test
    fun `circle is centred on its centre`() {
        val rng = Random(5)
        val area = SpawnArea(shape = AreaShape.CIRCLE, centerX = 7000, centerZ = -3000, radius = 100)
        var sumX = 0L
        var sumZ = 0L
        val trials = 100_000
        repeat(trials) {
            val point = area.randomPoint(rng)!!
            sumX += point[0]
            sumZ += point[1]
        }
        assertTrue(Math.abs(sumX / trials - 7000) <= 2, "중심 X 가 치우쳤습니다: ${sumX / trials}")
        assertTrue(Math.abs(sumZ / trials + 3000) <= 2, "중심 Z 가 치우쳤습니다: ${sumZ / trials}")
    }

    // --- unconfigured ----------------------------------------------------------

    @Test
    fun `an unconfigured area yields nothing`() {
        val rng = Random(6)
        assertNull(SpawnArea().randomPoint(rng))
        assertNull(SpawnArea(shape = AreaShape.CIRCLE, radius = 0).randomPoint(rng))
        // A rectangle still counts as unconfigured while both corners sit on the same spot.
        assertNull(SpawnArea(x1 = 5, z1 = 5, x2 = 5, z2 = 5).randomPoint(rng))
    }

    @Test
    fun `shape toggle round trips`() {
        assertEquals(AreaShape.CIRCLE, AreaShape.RECTANGLE.toggle())
        assertEquals(AreaShape.RECTANGLE, AreaShape.RECTANGLE.toggle().toggle())
        assertEquals(AreaShape.RECTANGLE, AreaShape.parse("rectangle"))
        assertEquals(AreaShape.CIRCLE, AreaShape.parse("CIRCLE"))
        assertEquals(AreaShape.RECTANGLE, AreaShape.parse("nonsense"))
    }

    // --- open modes ------------------------------------------------------------

    @Test
    fun `open mode cycles through all three`() {
        assertEquals(OpenMode.GUI, OpenMode.DIRECT.next())
        assertEquals(OpenMode.DROP, OpenMode.GUI.next())
        assertEquals(OpenMode.DIRECT, OpenMode.DROP.next())
        assertEquals(OpenMode.DIRECT, OpenMode.DIRECT.next().next().next())
    }

    @Test
    fun `open mode parsing keeps GUI as the default`() {
        assertEquals(OpenMode.DIRECT, OpenMode.parse("direct"))
        assertEquals(OpenMode.DROP, OpenMode.parse("DROP"))
        assertEquals(OpenMode.GUI, OpenMode.parse(null))
        assertEquals(OpenMode.GUI, OpenMode.parse("nonsense"))
    }
}
