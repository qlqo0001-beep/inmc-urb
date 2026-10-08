package com.inmc.urb

import com.inmc.urb.box.BoxMarks
import com.inmc.urb.box.BoxMarks.Mark
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoxMarksTest {

    @Test
    fun `a mark survives the round trip, block data commas and all`() {
        val mark = Mark(-126, 129, 158, "minecraft:chest[facing=north,type=single,waterlogged=false]", "inmc:fb_chest_71")
        assertEquals(mark, BoxMarks.decode(BoxMarks.encode(mark)))
    }

    @Test
    fun `a vanilla box keeps no ref`() {
        val mark = Mark(5, -60, -5, "minecraft:air", null)
        assertEquals("5/-60/-5|minecraft:air|", BoxMarks.encode(mark))
        assertEquals(mark, BoxMarks.decode(BoxMarks.encode(mark)))
    }

    @Test
    fun `garbage is skipped rather than thrown`() {
        assertNull(BoxMarks.decode(""))
        assertNull(BoxMarks.decode("1/2|minecraft:air|"))
        assertNull(BoxMarks.decode("a/2/3|minecraft:air|"))
        assertNull(BoxMarks.decode("1/2/3||"))
    }

    @Test
    fun `one position holds one mark`() {
        val first = BoxMarks.with(emptyList(), Mark(1, 2, 3, "minecraft:air", null))
        val replaced = BoxMarks.with(first, Mark(1, 2, 3, "minecraft:stone", "minecraft:chest"))
        assertEquals(listOf("1/2/3|minecraft:stone|minecraft:chest"), replaced)
    }

    @Test
    fun `removing one position leaves look-alike coordinates alone`() {
        val list = listOf(
            BoxMarks.encode(Mark(1, 2, 3, "minecraft:air", null)),
            BoxMarks.encode(Mark(11, 2, 3, "minecraft:air", null)),
            BoxMarks.encode(Mark(-1, 2, 3, "minecraft:air", null)),
        )
        assertEquals(list.drop(1), BoxMarks.without(list, 1, 2, 3))
    }
}
