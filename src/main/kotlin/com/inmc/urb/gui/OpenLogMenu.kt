package com.inmc.urb.gui

import com.inmc.urb.Urb
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * The recent-opens log, newest first.
 *
 * Distinct from `/urb notice`, which only lists the rewards flagged for a server-wide
 * announcement. This lists *every* open with its full haul, which is what an admin needs when
 * a player asks why they did not get something.
 *
 * Bounded by `stats.log-size`; older entries fall off rather than growing a file forever.
 */
class OpenLogMenu(
    urb: Urb,
    /** null lists every box. */
    private val boxFilter: String? = null,
    private var page: Int = 0,
) : Menu(urb, 54, TITLE) {

    override fun draw() {
        clear()
        for (slot in CONTENT_SIZE until 54) set(slot, Icon.EDGE)

        val entries = urb.stats.recentLog()
            .let { all -> if (boxFilter == null) all else all.filter { it.boxName == boxFilter } }
        val pages = Paging.pageCount(entries.size, CONTENT_SIZE)
        page = page.coerceIn(0, pages - 1)

        val now = System.currentTimeMillis()
        Paging.slice(entries, page, CONTENT_SIZE).forEachIndexed { index, entry ->
            val lore = mutableListOf(
                "<gray>상자: <white>${entry.boxDisplay}</white></gray>",
                "<gray>시각: <white>${Durations.formatShort((now - entry.at) / 1000L)} 전</white></gray>",
            )
            if (entry.world != null && entry.x != null && entry.z != null) {
                lore.add("<gray>위치: <white>${entry.world} ${entry.x}, ${entry.z}</white></gray>")
            }
            lore.add("")
            if (entry.items.isEmpty()) {
                lore.add("<dark_gray>지급된 아이템 없음 (명령어 전용)</dark_gray>")
            } else {
                lore.add("<gray>획득 <white>${entry.items.size}종</white></gray>")
                entry.items.take(10).forEach { lore.add("<dark_gray>· $it</dark_gray>") }
                if (entry.items.size > 10) lore.add("<dark_gray>… 외 ${entry.items.size - 10}종</dark_gray>")
            }

            set(index, Icon.of(Material.PAPER, "<yellow>${entry.player}</yellow>", lore))
        }

        if (entries.isEmpty()) {
            set(
                22,
                Icon.of(
                    Material.BARRIER, "<gray>기록 없음</gray>",
                    "<dark_gray>아직 열린 상자가 없습니다.</dark_gray>",
                )
            )
        }

        set(Paging.SLOT_BACK, Icon.back()) { event ->
            val player = event.whoClicked as? Player ?: return@set
            val box = boxFilter?.let { urb.boxes.get(it) }
            if (box != null) StatsMenu(urb, box).open(player) else BoxListMenu(urb).open(player)
        }

        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> switchPage(event.whoClicked, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> switchPage(event.whoClicked, page + 1) }

        set(
            49,
            Icon.of(
                Material.BOOK, "<yellow>오픈 기록</yellow>",
                "<gray>표시 중: <white>${if (boxFilter == null) "전체 상자" else boxFilter}</white></gray>",
                "<gray>기록 <white>${entries.size}건</white>  (${page + 1}/${pages})</gray>",
                "",
                "<yellow>▶ 클릭: ${if (boxFilter == null) "이 화면은 이미 전체입니다" else "전체 상자 보기"}</yellow>",
            )
        ) { event ->
            if (boxFilter == null) return@set
            (event.whoClicked as? Player)?.let { OpenLogMenu(urb, null).open(it) }
        }

        set(53, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    private fun switchPage(who: org.bukkit.entity.HumanEntity, target: Int) {
        val player = who as? Player ?: return
        OpenLogMenu(urb, boxFilter, target).open(player)
    }

    companion object {
        private const val CONTENT_SIZE = 45
        private val TITLE = Text.renderFlat("<dark_gray>오픈 기록</dark_gray>")
    }
}
