package com.inmc.urb.box

import com.inmc.urb.Urb
import kr.inmc.core.integration.ItemRoles
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * 랜덤박스가 커스텀아이템에 내놓는 역할(core [ItemRoles]) — **상자 캡슐**과 **상자 열쇠**. 값은 어느 상자의 것인지(`box`).
 *
 * 커스텀아이템이 있으면 상자의 캡슐·열쇠는 **그 역할을 맡은 아이템**이다([sync]). 커스텀아이템의 아이템 설정에서 역할을 붙여도,
 * 랜덤박스 조건 화면에서 손에 든 것으로 정해도(그 아이템을 커스텀아이템으로 만들어 역할을 붙인다) 같은 곳이 바뀐다.
 * 상자 파일에 적혀 있던 옛 캡슐·열쇠는 처음 한 번 커스텀아이템으로 옮기고, 이미 나가 있는 옛 아이템도 계속 알아본다(legacy).
 */
object UrbRoles {

    val CAPSULE = "urb.capsule"
    val KEY = "urb.key"
    val ALL = listOf(CAPSULE, KEY)
    const val OWNER = "랜덤박스"

    fun roles(urb: Urb): List<ItemRoles.Role> {
        val box = ItemRoles.Choice("box", "상자", { urb.boxes.all().map { it.name to it.displayName } })
        return listOf(
            ItemRoles.Role(CAPSULE, OWNER, "상자 캡슐", Material.ENDER_CHEST,
                listOf("손에 들고 우클릭하면 이 상자가 열립니다."), listOf(box)),
            ItemRoles.Role(KEY, OWNER, "상자 열쇠", Material.TRIPWIRE_HOOK,
                listOf("가방에 있어야 상자가 열리고, 열 때 한 개가 소모됩니다."), listOf(box)),
        )
    }

    private fun ours(item: StoredItem?) = (item?.ref as? ItemRef.Namespaced)?.namespace.equals("inmc", ignoreCase = true)

    private var syncing = false

    /**
     * 상자의 캡슐·열쇠를 역할에 맞춘다. 커스텀아이템이 없으면 아무것도 안 한다(상자 파일 그대로).
     * 처음이면 상자 파일의 옛 캡슐·열쇠를 커스텀아이템으로 옮긴다 — 옮긴 뒤 상자 파일에는 커스텀아이템 참조가 적혀 다시 옮기지 않는다.
     */
    fun sync(urb: Urb) {
        if (!ItemRoles.active) {
            for (box in urb.boxes.all()) {
                box.capsuleLegacy = null
                box.keyLegacy = null
            }
            urb.boxes.rebuildCapsuleIndex()
            return
        }
        if (syncing) return
        syncing = true
        try {
            for (box in urb.boxes.all()) {
                box.capsuleItem?.takeIf { !ours(it) }?.let { migrate(urb, box, CAPSULE, it) }
                box.keyItem?.takeIf { !ours(it) }?.let { migrate(urb, box, KEY, it) }
            }
            val capsules = ItemRoles.holders(CAPSULE).groupBy { it.values["box"] }
            val keys = ItemRoles.holders(KEY).groupBy { it.values["box"] }
            for (box in urb.boxes.all()) {
                val capsule = capsules[box.name]?.firstOrNull()
                val key = keys[box.name]?.firstOrNull()
                val newCapsule = capsule?.item()
                val newKey = key?.item()
                if (box.capsuleItem?.ref != newCapsule?.ref || box.keyItem?.ref != newKey?.ref) {
                    box.capsuleItem = newCapsule
                    box.keyItem = newKey
                    urb.boxes.markDirty(box)
                }
                box.capsuleLegacy = capsule?.legacy()
                box.keyLegacy = key?.legacy()
            }
            urb.boxes.rebuildCapsuleIndex()
        } finally {
            syncing = false
        }
    }

    private fun migrate(urb: Urb, box: RandomBox, role: String, item: StoredItem) {
        val stack = urb.itemResolver.create(item, 1)?.let { ItemRoles.sample(it, item) }
        val ref = stack?.let { ItemRoles.adopt(it, box.name + if (role == CAPSULE) "_캡슐" else "_열쇠") }
        if (ref == null) {
            urb.logger.warning("'${box.name}' 상자의 ${if (role == CAPSULE) "캡슐" else "열쇠"}을(를) 커스텀아이템으로 옮기지 못했습니다 — 상자 파일에 그대로 둡니다")
            return
        }
        ItemRoles.assign(ref, role, ItemRoles.withLegacy(mapOf("box" to box.name), item))
        urb.logger.info("'${box.name}' 상자의 ${if (role == CAPSULE) "캡슐" else "열쇠"}을(를) 커스텀아이템 '${ref.id}' 로 옮겼습니다")
    }

    /**
     * 조건 화면에서 손에 든 것을 캡슐·열쇠로 — 커스텀아이템으로 만들어(이미 그렇다면 그대로) 역할을 붙이고, 이 상자의 같은 역할을 맡던
     * 다른 아이템에서는 뗀다(상자 하나에 캡슐 하나). 커스텀아이템이 없으면 false — 부르는 쪽이 예전처럼 상자에 적는다.
     */
    fun set(urb: Urb, box: RandomBox, role: String, hand: ItemStack?): Boolean {
        if (!ItemRoles.active) return false
        for (holder in ItemRoles.holders(role)) if (holder.values["box"] == box.name) ItemRoles.assign(holder.ref, role, null)
        if (hand != null && !hand.type.isAir) {
            val captured = urb.itemResolver.capture(hand)
            val ref = ItemRoles.adopt(hand, box.name + if (role == CAPSULE) "_캡슐" else "_열쇠") ?: return true
            ItemRoles.assign(ref, role, ItemRoles.withLegacy(mapOf("box" to box.name), captured))
        }
        sync(urb)
        return true
    }
}
