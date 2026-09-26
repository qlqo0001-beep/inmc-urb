package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.command.UrbCommand
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `/urb notice` - the announcement history (spec §101).
 *
 * Rewards flagged as 공지 are recorded here when they drop, alongside the broadcast and the
 * Discord webhook, so anyone who missed the chat line can still see what was won.
 */
class NoticeMenu(urb: Urb, private var page: Int = 0) : Menu(urb, SIZE, TITLE) {

    override fun draw() {
        clear()

        val entries = urb.notices.recent()
        val pages = Paging.pageCount(entries.size, PER_PAGE)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(entries, page, PER_PAGE).forEachIndexed { index, entry ->
            val icon = entry.item?.let { urb.itemResolver.icon(it) }
            set(
                index,
                Icon.annotate(
                    icon?.stack ?: org.bukkit.inventory.ItemStack(Material.CHEST),
                    name = "<gold>${entry.itemLabel}</gold>",
                    lore = listOf(
                        "<gray>획득자: <yellow>${entry.player}</yellow></gray>",
                        "<gray>상자: <white>${entry.boxDisplay}</white></gray>",
                        "<dark_gray>${format(entry.at)}</dark_gray>",
                    ) + icon?.notes().orEmpty(),
                )
            )
        }

        for (slot in PER_PAGE until SIZE) set(slot, Icon.EDGE)

        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> repage(event.whoClicked, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> repage(event.whoClicked, page + 1) }

        set(
            49,
            Icon.of(
                Material.WRITABLE_BOOK, "<yellow>공지 확인창</yellow>",
                "<gray>기록된 당첨: <white>${entries.size}건</white></gray>",
                "<dark_gray>보상 설정에서 '당첨 시 서버 공지' 를 켠</dark_gray>",
                "<dark_gray>아이템이 여기에 기록됩니다.</dark_gray>",
            )
        )

        set(50, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }

        set(
            48,
            Icon.of(Material.BARRIER, "<red>기록 비우기</red>", "<dark_gray>관리자 전용 · Shift+클릭</dark_gray>")
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (!player.hasPermission(UrbCommand.PERMISSION) || !event.isShiftClick) return@set
            urb.notices.clear()
            refresh()
            player.updateInventory()
        }

        if (entries.isEmpty()) {
            set(22, Icon.of(Material.BARRIER, "<gray>아직 공지된 당첨이 없습니다</gray>"))
        }
    }

    private fun repage(who: org.bukkit.entity.HumanEntity, target: Int) {
        val player = who as? Player ?: return
        NoticeMenu(urb, target).open(player)
    }

    private fun format(epochMillis: Long): String =
        FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    companion object {
        private const val SIZE = 54
        private const val PER_PAGE = 45
        private val TITLE = Text.renderFlat("<dark_gray>랜덤박스 <gray>|</gray> 공지 확인창</dark_gray>")
        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
    }
}
