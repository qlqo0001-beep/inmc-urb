package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.BoxRegistry
import com.inmc.urb.box.RandomBox
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Durations
import com.inmc.urb.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * `/urb` - the root admin window (spec §41).
 *
 * Every box is listed; left-click manages one, Q or F deletes it after a confirmation, and
 * the bottom row creates a new one or opens the notice log.
 */
class BoxListMenu(urb: Urb, private var page: Int = 0) : Menu(urb, SIZE, TITLE) {

    override fun draw() {
        clear()

        val boxes = urb.boxes.all()
        val pages = Paging.pageCount(boxes.size, PER_PAGE)
        page = page.coerceIn(0, pages - 1)

        val start = page * PER_PAGE
        boxes.drop(start).take(PER_PAGE).forEachIndexed { index, box ->
            set(index, boxIcon(box)) { event ->
                val player = event.whoClicked as? Player ?: return@set
                when (event.click) {
                    ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND ->
                        confirmDelete(player, box)

                    else -> BoxManageMenu(urb, box).open(player)
                }
            }
        }

        for (slot in PER_PAGE until SIZE) set(slot, Icon.EDGE)

        if (page > 0) {
            set(Paging.SLOT_PREV, Icon.prevPage()) { event ->
                page--
                refresh()
                (event.whoClicked as? Player)?.updateInventory()
            }
        }
        if (page < pages - 1) {
            set(Paging.SLOT_NEXT, Icon.nextPage()) { event ->
                page++
                refresh()
                (event.whoClicked as? Player)?.updateInventory()
            }
        }

        set(
            48,
            Icon.of(
                Material.WRITABLE_BOOK, "<yellow>📜 공지 확인창</yellow>",
                "<gray>당첨 공지 기록을 확인합니다.</gray>",
                "<dark_gray>기록 ${urb.notices.size}건</dark_gray>",
            )
        ) { event -> (event.whoClicked as? Player)?.let { NoticeMenu(urb).open(it) } }

        set(
            49,
            Icon.of(
                Material.NETHER_STAR, "<green>＋ 새 상자 만들기</green>",
                "<gray>클릭하면 채팅으로 상자 이름을 입력합니다.</gray>",
                "<dark_gray>영문/숫자/한글/_/- 만 사용 가능</dark_gray>",
            )
        ) { event -> (event.whoClicked as? Player)?.let { promptCreate(it) } }

        set(
            47,
            Icon.of(
                Material.WRITTEN_BOOK, "<yellow>오픈 기록</yellow>",
                "<gray>모든 상자의 최근 오픈을 시간순으로 봅니다.</gray>",
                "<dark_gray>누적 ${urb.stats.totalOpens()}회</dark_gray>",
            )
        ) { event -> (event.whoClicked as? Player)?.let { OpenLogMenu(urb).open(it) } }

        set(
            51,
            Icon.of(
                Material.GOLDEN_HELMET, "<yellow>오픈 랭킹</yellow>",
                "<gray>가장 많이 연 플레이어 순위입니다.</gray>",
                "<dark_gray>기록된 플레이어 ${urb.stats.trackedPlayers()}명</dark_gray>",
            )
        ) { event -> (event.whoClicked as? Player)?.let { RankingMenu(urb, it).open(it) } }

        set(50, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }

        set(
            53,
            Icon.of(
                Material.COMPASS, "<gold>어드민 메뉴로</gold>",
                "<gray>각 플러그인 설정 허브로 돌아갑니다.</gray>",
            )
        ) { event -> (event.whoClicked as? Player)?.performCommand("메뉴 어드민") }

        if (boxes.isEmpty()) {
            set(
                22,
                Icon.of(
                    Material.BARRIER, "<red>등록된 상자가 없습니다</red>",
                    "<gray>아래 '새 상자 만들기' 로 시작하세요.</gray>",
                )
            )
        }
    }

    private fun boxIcon(box: RandomBox): org.bukkit.inventory.ItemStack {
        val live = urb.spawns.spawnedCount(box.name)
        val queued = urb.spawns.pendingCount(box.name)
        val lore = mutableListOf(
            "<dark_gray>${box.name}</dark_gray>",
            "",
            "<gray>상태: </gray>${Icon.toggle(box.enabled)}",
            "<gray>블록: <white>${box.block.serialize()}</white></gray>",
            "<gray>보상: <white>${box.rewards.size}개</white> <dark_gray>(최대 ${box.minRolls}~${box.maxRolls}개 배출)</dark_gray></gray>",
            "<gray>생성 방식: <white>${if (box.spawnMode == com.inmc.urb.box.SpawnMode.RANDOM_AREA) "랜덤 영역" else "특정 좌표"}</white></gray>",
            "<gray>자동 생성: <white>${if (box.autoSpawnIntervalSeconds > 0) Durations.formatShort(box.autoSpawnIntervalSeconds) else "사용 안 함"}</white></gray>",
            "<gray>소멸 시간: <white>${if (box.despawnSeconds > 0) Durations.formatShort(box.despawnSeconds) else "영구"}</white></gray>",
            "<gray>현재 생성: <white>$live</white><dark_gray> (대기 $queued) / 최대 ${box.maxSpawnCount}</dark_gray></gray>",
            "",
            "<yellow>▶ 좌클릭: 상자 관리</yellow>",
            "<red>▶ Q 또는 F: 상자 삭제</red>",
        )
        return blockIcon(box, box.displayName, lore)
    }

    private fun confirmDelete(player: Player, box: RandomBox) {
        ConfirmMenu(
            urb,
            question = "<yellow>⚠ 확인이 필요합니다</yellow>",
            title = "<dark_red>상자 삭제</dark_red>",
            detail = listOf(
                "<red>'<white>${box.name}</white>' 상자를 삭제합니다.</red>",
                "<gray>설정 파일과 생성된 상자가 모두 사라집니다.</gray>",
            ),
            onConfirm = {
                urb.spawns.reset(box.name)
                urb.boxes.delete(box.name)
                urb.messages.send(player, "box-deleted", Ph.of().box(box.displayName))
                BoxListMenu(urb, page).open(player)
            },
            onCancel = { BoxListMenu(urb, page).open(player) },
        ).open(player)
    }

    private fun promptCreate(player: Player) {
        urb.prompts.request(
            player,
            listOf(
                "<yellow>만들 상자의 이름을 입력하세요.</yellow>",
                "<gray>예: <white>supply</white>, <white>보급상자</white></gray>",
            ),
            onCancel = { BoxListMenu(urb, page).open(player) },
        ) { input ->
            val name = input.trim()
            when {
                !BoxRegistry.isValidName(name) -> {
                    urb.messages.send(player, "box-invalid-name")
                    BoxListMenu(urb, page).open(player)
                }

                urb.boxes.exists(name) -> {
                    urb.messages.send(player, "box-exists", Ph.of().box(name))
                    BoxListMenu(urb, page).open(player)
                }

                else -> {
                    val box = urb.boxes.create(name, urb.config.defaults)
                    urb.messages.send(player, "box-created", Ph.of().box(name))
                    if (box != null) BoxManageMenu(urb, box).open(player)
                    else BoxListMenu(urb, page).open(player)
                }
            }
        }
    }

    companion object {
        private const val SIZE = 54
        private const val PER_PAGE = 45
        private val TITLE = Text.renderFlat("<dark_gray>랜덤박스 <gray>|</gray> 상자 목록</dark_gray>")
    }
}
