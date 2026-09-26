package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.OpenMode
import com.inmc.urb.box.RandomBox
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Messages and events around opening (spec §79-83): the spawn / despawn / open broadcasts,
 * the commands an open runs, the two open modes (§70-72), the opening animation timing and
 * whether players may see the chance table.
 */
class OpenEventMenu(urb: Urb, private val box: RandomBox) : Menu(urb, 54, title(box)) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        messageButton(
            10, Material.FIREWORK_ROCKET, "등장 메시지",
            box.spawnMessage, urb.config.defaults.spawnMessage, box.broadcastSpawn,
            { box.spawnMessage = it }, { box.broadcastSpawn = !box.broadcastSpawn },
        )
        messageButton(
            11, Material.SOUL_TORCH, "소멸 메시지",
            box.despawnMessage, urb.config.defaults.despawnMessage, box.broadcastDespawn,
            { box.despawnMessage = it }, { box.broadcastDespawn = !box.broadcastDespawn },
        )
        messageButton(
            12, Material.CHEST, "오픈 메시지",
            box.openMessage, urb.config.defaults.openMessage, box.broadcastOpen,
            { box.openMessage = it }, { box.broadcastOpen = !box.broadcastOpen },
        )

        // Two extra broadcast switches the map-spawn ones do not cover: an admin placing a box
        // by hand, and a capsule opened anywhere in the world.
        set(
            13,
            Icon.of(
                if (box.broadcastManualSpawn) Material.ENDER_EYE else Material.GRAY_DYE,
                "<yellow>지금 소환 메시지</yellow>",
                "<gray>현재: </gray>${Icon.toggle(box.broadcastManualSpawn)}",
                "<dark_gray>/urb spawnnow 와 GUI 의 '지금 소환' 으로</dark_gray>",
                "<dark_gray>만든 상자의 등장 메시지입니다.</dark_gray>",
                "<dark_gray>보통 관리 작업이라 기본은 꺼짐입니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            box.broadcastManualSpawn = !box.broadcastManualSpawn
            save(); redraw(event.whoClicked)
        }

        set(
            15,
            Icon.of(
                if (box.broadcastCapsuleOpen) Material.ENDER_CHEST else Material.GRAY_DYE,
                "<yellow>캡슐 오픈 메시지</yellow>",
                "<gray>현재: </gray>${Icon.toggle(box.broadcastCapsuleOpen)}",
                "<dark_gray>캡슐 아이템을 우클릭해 열었을 때의</dark_gray>",
                "<dark_gray>오픈 메시지를 따로 조정합니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            box.broadcastCapsuleOpen = !box.broadcastCapsuleOpen
            save(); redraw(event.whoClicked)
        }

        set(
            16,
            Icon.of(
                if (box.discordAlert) Material.BELL else Material.GRAY_DYE,
                "<yellow>디스코드 알림</yellow>",
                buildList {
                    add("<gray>현재: </gray>${Icon.toggle(box.discordAlert)}")
                    if (!urb.discord.isEnabled) add("<dark_gray>config.yml 의 discord.webhook-url 이 비어 있습니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭하여 전환</yellow>")
                },
            )
        ) { event ->
            box.discordAlert = !box.discordAlert
            save(); redraw(event.whoClicked)
        }

        set(
            19,
            Icon.of(
                when (box.openMode) {
                    OpenMode.DIRECT -> Material.HOPPER
                    OpenMode.GUI -> Material.CHEST
                    OpenMode.DROP -> Material.DROPPER
                },
                "<yellow>오픈 방식</yellow>",
                "<gray>현재: <white>${box.openMode.label()}</white></gray>",
                "",
                "<dark_gray>인벤토리 직행: 아이템이 바로 들어옵니다.</dark_gray>",
                "<dark_gray>상자 창: 창에서 직접 꺼냅니다.</dark_gray>",
                "<dark_gray>바닥에 쏟기: 상자가 있던 자리에 아이템이</dark_gray>",
                "<dark_gray>            떨어집니다. 주변 사람도 주울 수 있습니다.</dark_gray>",
                "",
                "<dark_gray>어느 방식이든 맵에 생성된 상자는 한 번 열면 사라집니다.</dark_gray>",
                "",
                "<yellow>▶ 좌클릭: 다음 방식  /  우클릭: 이전 방식</yellow>",
            )
        ) { event ->
            box.openMode = if (event.isRightClick) box.openMode.previous() else box.openMode.next()
            save(); redraw(event.whoClicked)
        }

        set(
            20,
            Icon.of(
                Material.CLOCK, "<yellow>여는 데 걸리는 시간</yellow>",
                "<gray>현재: <white>${box.openTimeSeconds}초</white></gray>",
                "<dark_gray>이 시간 동안 타이틀에 진행 표시가 뜨고,</dark_gray>",
                "<dark_gray>움직이면 취소됩니다. 0 이면 즉시 열립니다.</dark_gray>",
                "",
                "<yellow>▶ 좌클릭 +1 / 우클릭 -1</yellow>",
            )
        ) { event ->
            box.openTimeSeconds = (box.openTimeSeconds + if (event.isLeftClick) 1 else -1).coerceIn(0, 30)
            save(); redraw(event.whoClicked)
        }

        set(
            21,
            Icon.of(
                Material.ICE, "<yellow>재시도 대기 시간</yellow>",
                "<gray>현재: <white>${box.reopenCooldownSeconds}초</white></gray>",
                "<dark_gray>열기가 취소된 뒤 다시 시도하기까지의 시간입니다.</dark_gray>",
                "",
                "<yellow>▶ 좌클릭 +1 / 우클릭 -1 (Shift ±10)</yellow>",
            )
        ) { event ->
            val delta = (if (event.isShiftClick) 10 else 1) * (if (event.isLeftClick) 1 else -1)
            box.reopenCooldownSeconds = (box.reopenCooldownSeconds + delta).coerceIn(0, 3600)
            save(); redraw(event.whoClicked)
        }

        set(
            23,
            Icon.of(
                Material.COMMAND_BLOCK, "<yellow>오픈 시 실행 명령어</yellow>",
                buildList {
                    if (box.openCommands.isEmpty()) add("<gray>등록된 명령어가 없습니다.</gray>")
                    else box.openCommands.forEach { add("<dark_gray>/$it</dark_gray>") }
                    add("")
                    add("<dark_gray>보상과 무관하게 열 때마다 실행됩니다.</dark_gray>")
                    add("<yellow>▶ 클릭하여 편집</yellow>")
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            urb.prompts.request(
                player,
                listOf(
                    "<yellow>상자를 열 때 실행할 명령어를 입력하세요. <gray>( | 로 여러 개 )</gray></yellow>",
                    "<gray>예: <white>say {플레이어네임}님이 상자를 열었습니다</white></gray>",
                    "<gray>'없음' 을 입력하면 모두 지웁니다.</gray>",
                ),
                onCancel = { reopen(player) },
            ) { input ->
                box.openCommands = if (input == "없음") mutableListOf()
                else input.split('|').map { it.trim().removePrefix("/") }
                    .filter { it.isNotEmpty() }.toMutableList()
                save(); reopen(player)
            }
        }

        set(
            25,
            Icon.of(
                if (box.previewEnabled) Material.SPYGLASS else Material.GRAY_DYE,
                "<yellow>확률표 공개</yellow>",
                "<gray>현재: </gray>${Icon.toggle(box.previewEnabled)}",
                "<dark_gray>켜면 누구나 /urb info 로 이 상자의</dark_gray>",
                "<dark_gray>아이템과 확률을 볼 수 있습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            box.previewEnabled = !box.previewEnabled
            save(); redraw(event.whoClicked)
        }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { BoxManageMenu(urb, box).open(it) }
        }
        set(53, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    private fun messageButton(
        slot: Int,
        material: Material,
        label: String,
        current: String?,
        fallback: String,
        broadcast: Boolean,
        apply: (String?) -> Unit,
        toggleBroadcast: () -> Unit,
    ) {
        set(
            slot,
            Icon.of(
                material, "<yellow>$label</yellow>",
                buildList {
                    add("<gray>방송: </gray>${Icon.toggle(broadcast)}")
                    add("")
                    add(if (current == null) "<dark_gray>기본값 사용:</dark_gray>" else "<dark_gray>사용자 지정:</dark_gray>")
                    add("<white>${(current ?: fallback).take(60)}</white>")
                    add("")
                    add("<yellow>▶ 좌클릭: 메시지 편집</yellow>")
                    add("<yellow>▶ 우클릭: 방송 켜기/끄기</yellow>")
                    add("<red>▶ Shift+좌클릭: 기본값으로 되돌리기</red>")
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            when {
                event.isShiftClick && event.isLeftClick -> {
                    apply(null); save(); redraw(player)
                }

                event.isRightClick -> {
                    toggleBroadcast(); save(); redraw(player)
                }

                else -> urb.prompts.request(
                    player,
                    listOf(
                        "<yellow>${label}를 입력하세요. <gray>(MiniMessage 사용 가능)</gray></yellow>",
                        "<gray>사용 가능: <white>{상자이름} {플레이어네임} {좌표} {world} {x} {y} {z}</white></gray>",
                    ),
                    onCancel = { reopen(player) },
                ) { input ->
                    apply(input)
                    save(); reopen(player)
                }
            }
        }
    }

    private fun save() = urb.boxes.markDirty(box)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = OpenEventMenu(urb, box).open(player)

    companion object {
        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>오픈 이벤트 <gray>|</gray> ${box.name}</dark_gray>")
    }
}
