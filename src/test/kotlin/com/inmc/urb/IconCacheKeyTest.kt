package com.inmc.urb

import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.MMOItemsHook
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.ItemResolver
import kr.inmc.core.item.StorageMode
import kr.inmc.core.item.StoredItem
import org.bukkit.Material
import org.junit.jupiter.api.Test
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Menu icons are cached, and two different rewards must never share a cache entry.
 *
 * Every hand-made vanilla item - a renamed, re-lored or enchanted stack - carries
 * [ItemRef.None], and that serialises to the same literal `"snapshot"` for all of them. When
 * the cache key was built from the reference alone, two such rewards of the same material
 * collided: whichever was drawn first supplied the icon for both, in the admin reward list and
 * in the `/urb info` chance table players see. Drops stayed correct, so nothing ever failed
 * loudly - the pictures simply lied.
 *
 * The key is private because nothing outside the resolver should depend on its shape; it is
 * reached by reflection here rather than widened, since the property under test is exactly
 * that distinct items map to distinct keys.
 */
class IconCacheKeyTest {

    private val resolver = Logger.getLogger("urb-test").let { logger ->
        ItemResolver(MMOItemsHook(logger), CustomItemHook(logger), logger)
    }

    private val keyOf = ItemResolver::class.java
        .getDeclaredMethod("cacheKey", StoredItem::class.java)
        .apply { isAccessible = true }

    private fun key(item: StoredItem): String = keyOf.invoke(resolver, item) as String

    private fun snapshot(material: Material, name: String?, bytes: ByteArray) = StoredItem(
        ref = ItemRef.None,
        material = material,
        mode = StorageMode.SNAPSHOT,
        snapshot = bytes,
        displayName = name,
    )

    @Test
    fun `snapshot items of the same material stay distinct`() {
        val sword = snapshot(Material.STICK, "불의 검", byteArrayOf(1, 2, 3))
        val staff = snapshot(Material.STICK, "얼음 지팡이", byteArrayOf(9, 9, 9))

        assertNotEquals(key(sword), key(staff), "서로 다른 스냅샷 아이템이 같은 아이콘 캐시를 씁니다")
    }

    /** Same name, different contents - enchantment levels differ, say. */
    @Test
    fun `identical names with different snapshots stay distinct`() {
        val weak = snapshot(Material.DIAMOND_SWORD, "전설의 검", byteArrayOf(1))
        val strong = snapshot(Material.DIAMOND_SWORD, "전설의 검", byteArrayOf(2))

        assertNotEquals(key(weak), key(strong))
    }

    /** The cache still has to *work*: the same item must hit the same entry. */
    @Test
    fun `an equal item produces an equal key`() {
        val a = snapshot(Material.STICK, "불의 검", byteArrayOf(1, 2, 3))
        val b = snapshot(Material.STICK, "불의 검", byteArrayOf(1, 2, 3))

        assertEquals(key(a), key(b))
    }

    @Test
    fun `plain vanilla items key off their reference`() {
        val diamond = StoredItem(ItemRef.Vanilla(Material.DIAMOND), Material.DIAMOND)
        val emerald = StoredItem(ItemRef.Vanilla(Material.EMERALD), Material.EMERALD)

        assertNotEquals(key(diamond), key(emerald))
        assertEquals(key(diamond), key(StoredItem(ItemRef.Vanilla(Material.DIAMOND), Material.DIAMOND)))
    }

    /** A reference item and a snapshot of it are different rewards and must not share an icon. */
    @Test
    fun `storage mode separates otherwise identical entries`() {
        val reference = StoredItem(ItemRef.Vanilla(Material.DIAMOND), Material.DIAMOND, StorageMode.REFERENCE)
        val frozen = StoredItem(ItemRef.Vanilla(Material.DIAMOND), Material.DIAMOND, StorageMode.SNAPSHOT)

        assertNotEquals(key(reference), key(frozen))
    }
}
