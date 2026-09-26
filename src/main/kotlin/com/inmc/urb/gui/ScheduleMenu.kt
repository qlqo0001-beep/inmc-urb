package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.BoxSchedule
import com.inmc.urb.box.RandomBox
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * When a box is allowed to airdrop on its own.
 *
 * Only the *automatic* schedule is gated. `/urb spawn`, `/urb spawnnow` and capsules keep
 * working outside the window, because an admin placing a box by hand has already decided; a
 * schedule that blocked those would just look broken.
 *
 * The interval timer keeps running while the window is shut, so a box whose window opens
 * mid-cycle drops once at the next tick rather than firing a whole backlog at once.
 */
class ScheduleMenu(urb: Urb, private val box: RandomBox) : Menu(urb, 45, title(box)) {

    private val schedule: BoxSchedule get() = box.schedule

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val now = ZonedDateTime.now()
        val open = schedule.allows(now)

        set(
            13,
            Icon.of(
                if (schedule.enabled) Material.CLOCK else Material.GRAY_DYE,
                "<yellow>시간대 제한</yellow>",
                "<gray>현재: </gray>${Icon.toggle(schedule.enabled)}",
                "<gray>설정: <white>${schedule.describe()}</white></gray>",
                "",
                if (!schedule.enabled) "<dark_gray>제한 없음 - 언제든 자동 생성됩니다.</dark_gray>"
                else if (open) "<green>지금은 생성 가능 시간대입니다.</green>"
                else "<red>지금은 생성이 멈춰 있습니다.</red>",
                "",
                "<dark_gray>수동 소환과 캡슐은 이 설정과 무관하게 동작합니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            schedule.enabled = !schedule.enabled
            save(); redraw(event.whoClicked)
        }

        // --- days ---------------------------------------------------------------
        DayOfWeek.entries.forEachIndexed { index, day ->
            val active = day in schedule.days
            set(
                DAY_SLOTS[index],
                Icon.of(
                    if (active) Material.LIME_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE,
                    if (active) "<green>${BoxSchedule.koreanDay(day)}요일</green>"
                    else "<red>${BoxSchedule.koreanDay(day)}요일</red>",
                    "<gray>현재: </gray>${Icon.toggle(active)}",
                    "",
                    "<yellow>▶ 클릭하여 전환</yellow>",
                )
            ) { event ->
                schedule.toggleDay(day)
                save(); redraw(event.whoClicked)
            }
        }

        set(
            29,
            Icon.of(
                Material.SUNFLOWER, "<yellow>시작 시각</yellow>",
                "<gray>현재: <white>${format(schedule.from.hour, schedule.from.minute)}</white></gray>",
                "",
                "<yellow>▶ 클릭하여 입력 <gray>(HH:mm)</gray></yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            promptTime(player, "시작 시각") { schedule.from = it }
        }

        set(
            31,
            Icon.of(
                Material.CLOCK, "<yellow>종료 시각</yellow>",
                "<gray>현재: <white>${format(schedule.to.hour, schedule.to.minute)}</white></gray>",
                "<dark_gray>종료가 시작보다 이르면 자정을 넘겨 이어집니다.</dark_gray>",
                "<dark_gray>예: 22:00 ~ 02:00</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 입력 <gray>(HH:mm)</gray></yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            promptTime(player, "종료 시각") { schedule.to = it }
        }

        set(
            33,
            Icon.of(
                Material.COMPASS, "<yellow>다음 자동 생성</yellow>",
                nextSpawnLore(now),
            )
        )

        set(
            11,
            Icon.of(
                Material.PAPER, "<gray>빠른 설정</gray>",
                "<gray>자주 쓰는 요일 조합입니다.</gray>",
                "",
                "<yellow>▶ 좌클릭: 매일</yellow>",
                "<yellow>▶ 우클릭: 주말만</yellow>",
                "<yellow>▶ Shift+좌클릭: 평일만</yellow>",
            )
        ) { event ->
            schedule.days = when {
                event.isShiftClick -> mutableSetOf(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                )

                event.isRightClick -> mutableSetOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                else -> DayOfWeek.entries.toMutableSet()
            }
            save(); redraw(event.whoClicked)
        }

        set(36, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { BoxManageMenu(urb, box).open(it) }
        }
        set(44, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    private fun nextSpawnLore(now: ZonedDateTime): List<String> {
        val lore = mutableListOf<String>()
        if (box.autoSpawnIntervalSeconds <= 0L) {
            lore.add("<gray>자동 생성이 꺼져 있습니다.</gray>")
            lore.add("<dark_gray>② 생성/스폰 설정에서 주기를 지정하세요.</dark_gray>")
            return lore
        }
        lore.add("<gray>주기: <white>${Durations.formatShort(box.autoSpawnIntervalSeconds)}</white></gray>")
        val remaining = urb.spawns.secondsUntilAutoSpawn(box)
        if (remaining != null) {
            lore.add("<gray>다음 시도까지: <white>${Durations.formatShort(remaining)}</white></gray>")
        }
        if (!box.enabled) {
            lore.add("<red>상자가 비활성화되어 있습니다.</red>")
        } else if (!schedule.allows(now)) {
            lore.add("<red>시간대 밖이라 다음 시도는 건너뜁니다.</red>")
        }
        return lore
    }

    private fun promptTime(player: Player, label: String, apply: (java.time.LocalTime) -> Unit) {
        urb.prompts.request(
            player,
            listOf(
                "<yellow>${label}을(를) 입력하세요.</yellow>",
                "<gray>형식: <white>HH:mm</white>  예: <white>21:30</white></gray>",
            ),
            onCancel = { reopen(player) },
        ) { input ->
            val parsed = BoxSchedule.parseTime(input, java.time.LocalTime.MIDNIGHT)
            apply(parsed)
            save()
            reopen(player)
        }
    }

    private fun format(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

    private fun save() = urb.boxes.markDirty(box)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = ScheduleMenu(urb, box).open(player)

    companion object {
        /** Monday first, matching DayOfWeek's own order. */
        private val DAY_SLOTS = intArrayOf(19, 20, 21, 22, 23, 24, 25)

        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>생성 시간대 <gray>|</gray> ${box.name}</dark_gray>")
    }
}
