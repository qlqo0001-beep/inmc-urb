package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.BoxSighting
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import kotlin.math.roundToInt

/**
 * `/urb search` - where the boxes are right now (spec §103).
 *
 * Queued spawns are listed alongside placed ones: their chunk has not loaded yet, so the
 * exact Y is unknown, but the announced X/Z is exactly what a player needs to travel there.
 * Clicking an entry points the player's compass at it.
 */
class TrackMenu(
    urb: Urb,
    private val viewer: Player,
    private val sightings: List<BoxSighting>,
    private var page: Int = 0,
) : Menu(urb, SIZE, TITLE) {

    override fun draw() {
        clear()

        val pages = Paging.pageCount(sightings.size, PER_PAGE)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(sightings, page, PER_PAGE).forEachIndexed { index, sighting ->
            set(index, sightingIcon(sighting)) { event ->
                val player = event.whoClicked as? Player ?: return@set
                urb.tracking.track(player, sighting)
                player.closeInventory()
            }
        }

        for (slot in PER_PAGE until SIZE) set(slot, Icon.EDGE)

        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> repage(event.whoClicked, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> repage(event.whoClicked, page + 1) }

        set(
            48,
            Icon.of(
                Material.BARRIER, "<red>추적 중지</red>",
                "<gray>나침반 추적을 해제합니다.</gray>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            urb.tracking.clear(player)
            player.closeInventory()
        }

        set(
            49,
            Icon.of(
                Material.MAP, "<yellow>검색 결과</yellow>",
                "<gray>배치됨: <white>${sightings.count { !it.queued }}개</white></gray>",
                "<gray>대기 중: <white>${sightings.count { it.queued }}개</white></gray>",
                "",
                "<dark_gray>대기 중인 상자는 해당 좌표 근처로 이동하면</dark_gray>",
                "<dark_gray>그 자리에 나타납니다.</dark_gray>",
            )
        )

        set(50, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    private fun sightingIcon(sighting: BoxSighting): org.bukkit.inventory.ItemStack {
        val box = urb.boxes.get(sighting.boxName)
        val distance = if (viewer.world.name != sighting.world) {
            null
        } else {
            val dx = viewer.location.blockX - sighting.x
            val dz = viewer.location.blockZ - sighting.z
            Math.sqrt((dx.toDouble() * dx) + (dz.toDouble() * dz)).roundToInt()
        }

        val lore = mutableListOf(
            "<gray>월드: <white>${sighting.world}</white></gray>",
            "<gray>좌표: <white>${sighting.x}, ${sighting.y?.toString() ?: "?"}, ${sighting.z}</white></gray>",
        )
        distance?.let { lore.add("<gray>거리: <white>${it}m</white></gray>") }
        lore.add(
            if (sighting.remainingSeconds < 0) "<gray>남은 시간: <white>제한 없음</white></gray>"
            else "<gray>남은 시간: <white>${Durations.formatShort(sighting.remainingSeconds)}</white></gray>"
        )
        if (sighting.queued) lore.add("<dark_gray>아직 청크가 로드되지 않았습니다</dark_gray>")
        lore.add("")
        lore.add("<yellow>▶ 클릭하여 나침반으로 추적</yellow>")

        if (!sighting.queued && box != null) return blockIcon(box, sighting.displayName, lore)
        return Icon.of(if (sighting.queued) Material.MAP else Material.CHEST, sighting.displayName, lore)
    }

    private fun repage(who: org.bukkit.entity.HumanEntity, target: Int) {
        val player = who as? Player ?: return
        TrackMenu(urb, viewer, sightings, target).open(player)
    }

    companion object {
        private const val SIZE = 54
        private const val PER_PAGE = 45
        private val TITLE = Text.renderFlat("<dark_gray>랜덤박스 <gray>|</gray> 위치 검색</dark_gray>")
    }
}
