package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.AreaShape
import com.inmc.urb.box.ListMode
import com.inmc.urb.box.RandomBox
import com.inmc.urb.box.SpawnArea
import com.inmc.urb.box.SpawnMode
import kr.inmc.core.item.BlockRef
import com.inmc.urb.util.BlockKey
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Spawn placement and schedule - the spec's sections 2 and 3 merged into one screen (§88).
 *
 * Covers the two placement modes (§55-56), the world, the airdrop interval and despawn time,
 * the per-box count cap, the block the box appears as (§53), the cave toggle, and the
 * optional WorldGuard/Lands filters.
 */
class SpawnSettingsMenu(urb: Urb, private val box: RandomBox) : Menu(urb, 54, title(box)) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val random = box.spawnMode == SpawnMode.RANDOM_AREA

        set(
            10,
            Icon.of(
                when (box.spawnMode) {
                    SpawnMode.RANDOM_AREA -> Material.GRASS_BLOCK
                    SpawnMode.FIXED_POINTS -> Material.TARGET
                    SpawnMode.PERMANENT -> Material.BEDROCK
                },
                "<yellow>생성 방식</yellow>",
                buildList {
                    add("<gray>현재: <white>${box.spawnMode.label()}</white></gray>")
                    // The commonest way a box goes quiet is sitting in a mode whose target was
                    // never filled in, so it is called out on the very button that switches mode.
                    box.autoSpawnProblem()?.let { add("<red>⚠ 자동 생성 불가: $it</red>") }
                    add("")
                    add("<dark_gray>랜덤 영역: 지정한 영역 안 아무 곳</dark_gray>")
                    add("<dark_gray>특정 좌표: 등록된 좌표 중 하나</dark_gray>")
                    add("<dark_gray>고정 설치: 등록된 모든 좌표에 영구 설치.</dark_gray>")
                    add("<dark_gray>          소멸하지 않고, 열쇠나 돈이 있어야만 열립니다.</dark_gray>")
                    if (box.isPermanent && !box.permanentHasCost()) {
                        add("")
                        add("<red>⚠ 열쇠나 소모 금액이 없어 열 수 없습니다.</red>")
                        add("<red>   '오픈 조건' 에서 하나 이상 설정하세요.</red>")
                    }
                    add("")
                    add("<yellow>▶ 좌클릭: 다음 방식  /  우클릭: 이전 방식</yellow>")
                },
            )
        ) { event ->
            // Three-way cycle, so forward-only would mean two clicks to undo one misclick -
            // which is how a box ends up sitting in 특정 좌표 with no coordinates registered.
            box.spawnMode = if (event.isRightClick) box.spawnMode.previous() else box.spawnMode.next()
            save(); redraw(event.whoClicked)
        }

        set(
            11,
            Icon.of(
                Material.COMPASS, "<yellow>월드</yellow>",
                "<gray>현재: <white>${box.world}</white></gray>",
                "<dark_gray>${if (Bukkit.getWorld(box.world) == null) "⚠ 이 월드를 찾을 수 없습니다" else "정상"}</dark_gray>",
                "",
                "<yellow>▶ 클릭: 지금 서 있는 월드로 설정</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            box.world = player.world.name
            save(); redraw(player)
        }

        val circle = box.area.shape == AreaShape.CIRCLE
        set(
            12,
            Icon.of(
                if (circle) Material.COMPASS else Material.MAP,
                "<yellow>랜덤 영역 (${box.area.shape.label()})</yellow>",
                if (circle) {
                    listOf(
                        "<gray>중심: <white>${box.area.centerX}, ${box.area.centerZ}</white></gray>",
                        "<gray>반지름: <white>${box.area.radius}</white></gray>",
                        "",
                        "<yellow>▶ 좌클릭: 현재 위치를 중심으로</yellow>",
                        "<yellow>▶ 우클릭: 반지름 입력</yellow>",
                        "<yellow>▶ Shift+클릭: 사각형으로 전환</yellow>",
                    )
                } else {
                    listOf(
                        "<gray>코너 1: <white>${box.area.x1}, ${box.area.z1}</white></gray>",
                        "<gray>코너 2: <white>${box.area.x2}, ${box.area.z2}</white></gray>",
                        "<gray>크기: <white>${box.area.describe()}</white></gray>",
                        "",
                        "<yellow>▶ 좌클릭: 현재 위치를 코너 1 로</yellow>",
                        "<yellow>▶ 우클릭: 현재 위치를 코너 2 로</yellow>",
                        "<yellow>▶ Shift+좌클릭: 좌표 직접 입력</yellow>",
                        "<yellow>▶ Shift+우클릭: 원형으로 전환</yellow>",
                    )
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            when {
                event.isShiftClick && (circle || event.isRightClick) -> {
                    box.area = box.area.copy(shape = box.area.shape.toggle())
                    save(); redraw(player)
                }

                event.isShiftClick -> promptArea(player)

                circle && event.isLeftClick -> {
                    box.area = box.area.copy(
                        centerX = player.location.blockX,
                        centerZ = player.location.blockZ,
                    )
                    save(); redraw(player)
                }

                circle -> promptRadius(player)

                event.isLeftClick -> {
                    box.area = box.area.copy(x1 = player.location.blockX, z1 = player.location.blockZ)
                    save(); redraw(player)
                }

                else -> {
                    box.area = box.area.copy(x2 = player.location.blockX, z2 = player.location.blockZ)
                    save(); redraw(player)
                }
            }
        }

        set(
            13,
            Icon.of(
                Material.LODESTONE, "<yellow>특정 좌표 목록</yellow>",
                buildList {
                    if (box.fixedPoints.isEmpty()) {
                        add("<gray>등록된 좌표가 없습니다.</gray>")
                    } else {
                        box.fixedPoints.take(8).forEach { add("<dark_gray>${it.world} ${it.x}, ${it.y}, ${it.z}</dark_gray>") }
                        if (box.fixedPoints.size > 8) add("<dark_gray>... 외 ${box.fixedPoints.size - 8}개</dark_gray>")
                    }
                    add("")
                    add("<yellow>▶ 좌클릭: 현재 위치 추가</yellow>")
                    add("<yellow>▶ 우클릭: 가장 최근 좌표 제거</yellow>")
                    add("<red>▶ Shift+우클릭: 전체 비우기</red>")
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            when {
                event.isShiftClick && event.isRightClick -> box.fixedPoints.clear()
                event.isRightClick -> box.fixedPoints.removeLastOrNull()
                event.isLeftClick -> box.fixedPoints.add(BlockKey.of(player.location))
            }
            save(); redraw(player)
        }

        // Registered from the hand rather than picked from a list of every material: building
        // that list called Material.isBlock() ~1500 times, which drags in Bukkit's legacy
        // conversion tables and froze the server for over ten seconds. Holding the block also
        // happens to be the only way to name an ItemsAdder custom block at all.
        set(
            14,
            blockIcon(
                box, "<yellow>상자 블록</yellow>",
                buildList {
                    add("<gray>현재: <white>${box.block.serialize()}</white></gray>")
                    add("")
                    add("<dark_gray>돌로 지정하면 돌처럼 보이지만</dark_gray>")
                    add("<dark_gray>클릭하면 상자 창이 열립니다.</dark_gray>")
                    add("<dark_gray>커스텀아이템 블록(inmc:…)·ItemsAdder 커스텀 블록도</dark_gray>")
                    add("<dark_gray>손에 들고 누르면 그 모양이 됩니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 좌클릭: 손에 든 블록으로 설정</yellow>")
                    add("<red>▶ 우클릭: 기본값(상자)으로 되돌리기</red>")
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                box.block = BlockRef.DEFAULT
                save(); redraw(player)
                return@set
            }
            val hand = player.inventory.itemInMainHand
            if (hand.type.isAir) {
                player.sendMessage(Text.render("<red>손에 블록을 든 뒤 클릭하세요.</red>", null, player))
                return@set
            }
            val ref = BlockRef.fromItem(hand, urb.customItems)
            if (ref == null) {
                player.sendMessage(
                    Text.render(
                        "<red>'${hand.type.name}' 은(는) 설치할 수 있는 블록이 아닙니다.</red>",
                        null, player,
                    )
                )
                return@set
            }
            box.block = ref
            save(); redraw(player)
        }

        set(
            19,
            Icon.of(
                Material.CLOCK, "<yellow>자동 생성 주기</yellow>",
                "<gray>현재: <white>${if (box.autoSpawnIntervalSeconds > 0) Durations.format(box.autoSpawnIntervalSeconds) else "사용 안 함"}</white></gray>",
                "<dark_gray>예: 1d 1h 1m 1s</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 입력 (0 = 사용 안 함)</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            promptDuration(player, "자동 생성 주기", box.autoSpawnIntervalSeconds) {
                box.autoSpawnIntervalSeconds = it
                box.nextSpawnAt = 0L
            }
        }

        set(
            20,
            Icon.of(
                Material.SOUL_TORCH, "<yellow>소멸 시간</yellow>",
                "<gray>현재: <white>${if (box.despawnSeconds > 0) Durations.format(box.despawnSeconds) else "영구"}</white></gray>",
                "<dark_gray>생성된 상자가 사라지기까지의 시간입니다.</dark_gray>",
                "<dark_gray>서버를 재시작해도 이어서 계산됩니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 입력 (0 = 영구)</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            promptDuration(player, "소멸 시간", box.despawnSeconds) { box.despawnSeconds = it }
        }

        set(
            21,
            Icon.of(
                Material.BARREL, "<yellow>최대 생성 개수</yellow>",
                "<gray>현재: <white>${box.maxSpawnCount}개</white></gray>",
                "<gray>지금: <white>${urb.spawns.spawnedCount(box.name)}</white> <dark_gray>(대기 ${urb.spawns.pendingCount(box.name)})</dark_gray></gray>",
                "<dark_gray>상자별로 따로 적용됩니다.</dark_gray>",
                "",
                "<yellow>▶ 좌클릭 +1 / 우클릭 -1 (Shift ±10)</yellow>",
            )
        ) { event ->
            box.maxSpawnCount = (box.maxSpawnCount + Editors.step(event, 1)).coerceAtLeast(0)
            save(); redraw(event.whoClicked)
        }

        set(
            22,
            Icon.of(
                Material.REPEATER, "<yellow>주기당 생성 개수</yellow>",
                "<gray>현재: <white>${box.spawnAmountPerCycle}개</white></gray>",
                "<dark_gray>자동 생성이 한 번 돌 때 시도하는 개수입니다.</dark_gray>",
                "",
                "<yellow>▶ 좌클릭 +1 / 우클릭 -1</yellow>",
            )
        ) { event ->
            box.spawnAmountPerCycle = (box.spawnAmountPerCycle + (if (event.isLeftClick) 1 else -1)).coerceIn(1, 64)
            save(); redraw(event.whoClicked)
        }

        set(
            28,
            Icon.of(
                if (box.allowCaveSpawn) Material.DEEPSLATE else Material.GRASS_BLOCK,
                "<yellow>동굴 스폰</yellow>",
                "<gray>현재: </gray>${Icon.toggle(box.allowCaveSpawn)}",
                "<dark_gray>꺼짐: 지표면에만 생성됩니다.</dark_gray>",
                "<dark_gray>켜짐: 지표면을 먼저 시도하고, 실패하면</dark_gray>",
                "<dark_gray>       Y 범위 안의 동굴 공간을 찾습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            box.allowCaveSpawn = !box.allowCaveSpawn
            save(); redraw(event.whoClicked)
        }

        set(
            29,
            Icon.of(
                Material.LADDER, "<yellow>Y 범위</yellow>",
                "<gray>최소: <white>${box.minY?.toString() ?: "월드 하한"}</white></gray>",
                "<gray>최대: <white>${box.maxY?.toString() ?: "월드 상한"}</white></gray>",
                "",
                "<yellow>▶ 좌클릭: 최소 입력</yellow>",
                "<yellow>▶ 우클릭: 최대 입력</yellow>",
                "<red>▶ Shift+클릭: 범위 초기화</red>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isShiftClick) {
                box.minY = null
                box.maxY = null
                save(); redraw(player)
                return@set
            }
            val isMin = event.isLeftClick
            urb.prompts.requestInt(
                player,
                listOf("<yellow>${if (isMin) "최소" else "최대"} Y 값을 입력하세요.</yellow>"),
                min = -256, max = 512,
                onCancel = { reopen(player) },
            ) { value ->
                if (isMin) box.minY = value else box.maxY = value
                save(); reopen(player)
            }
        }

        set(
            31,
            Icon.of(
                Material.OAK_FENCE, "<yellow>WorldGuard 지역 필터</yellow>",
                buildList {
                    add("<gray>모드: <white>${if (box.regionMode == ListMode.WHITELIST) "화이트리스트" else "블랙리스트"}</white></gray>")
                    if (box.regionList.isEmpty()) add("<gray>목록 비어 있음 (필터 사용 안 함)</gray>")
                    else box.regionList.take(6).forEach { add("<dark_gray>- $it</dark_gray>") }
                    add("")
                    add("<yellow>▶ 좌클릭: 목록 편집</yellow>")
                    add("<yellow>▶ 우클릭: 모드 전환</yellow>")
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                box.regionMode = box.regionMode.toggle()
                save(); redraw(player)
                return@set
            }
            promptList(player, "WorldGuard 지역", box.regionList) { box.regionList = it }
        }

        set(
            32,
            Icon.of(
                Material.IRON_DOOR, "<yellow>Lands 지역 필터</yellow>",
                buildList {
                    add("<gray>모드: <white>${if (box.landMode == ListMode.WHITELIST) "화이트리스트" else "블랙리스트"}</white></gray>")
                    if (box.landList.isEmpty()) add("<gray>목록 비어 있음 (필터 사용 안 함)</gray>")
                    else box.landList.take(6).forEach { add("<dark_gray>- $it</dark_gray>") }
                    add("")
                    add("<yellow>▶ 좌클릭: 목록 편집</yellow>")
                    add("<yellow>▶ 우클릭: 모드 전환</yellow>")
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                box.landMode = box.landMode.toggle()
                save(); redraw(player)
                return@set
            }
            promptList(player, "Lands 지역", box.landList) { box.landList = it }
        }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { BoxManageMenu(urb, box).open(it) }
        }
        set(53, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    // --- prompts ---------------------------------------------------------------

    private fun promptDuration(player: Player, label: String, current: Long, apply: (Long) -> Unit) {
        urb.prompts.request(
            player,
            listOf(
                "<yellow>${label}을(를) 입력하세요.</yellow>",
                "<gray>형식: <white>1d 1h 1m 1s</white> <dark_gray>(현재 ${Durations.format(current)})</dark_gray></gray>",
                "<gray>0 을 입력하면 사용하지 않습니다.</gray>",
            ),
            onCancel = { reopen(player) },
        ) { input ->
            apply(Durations.parse(input, current).coerceAtLeast(0L))
            save(); reopen(player)
        }
    }

    private fun promptRadius(player: Player) {
        urb.prompts.requestInt(
            player,
            listOf(
                "<yellow>원형 영역의 반지름을 입력하세요. <gray>(블록 단위)</gray></yellow>",
                "<gray>현재: <white>${box.area.radius}</white></gray>",
            ),
            min = 0, max = 100_000,
            onCancel = { reopen(player) },
        ) { value ->
            box.area = box.area.copy(radius = value)
            save(); reopen(player)
        }
    }

    private fun promptArea(player: Player) {
        urb.prompts.request(
            player,
            listOf(
                "<yellow>영역을 입력하세요.</yellow>",
                "<gray>형식: <white>x1 z1 x2 z2</white></gray>",
                "<gray>예: <white>-1000 -1000 1000 1000</white></gray>",
            ),
            onCancel = { reopen(player) },
        ) { input ->
            val parts = input.split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }
            if (parts.size < 4) {
                urb.messages.send(player, "prompt-invalid-number")
            } else {
                box.area = box.area.copy(
                    shape = AreaShape.RECTANGLE,
                    x1 = parts[0], z1 = parts[1], x2 = parts[2], z2 = parts[3],
                )
                save()
            }
            reopen(player)
        }
    }

    private fun promptList(player: Player, label: String, current: List<String>, apply: (MutableList<String>) -> Unit) {
        urb.prompts.request(
            player,
            listOf(
                "<yellow>${label} 이름을 입력하세요. <gray>( | 로 여러 개 )</gray></yellow>",
                "<gray>현재: <white>${current.joinToString(", ").ifEmpty { "없음" }}</white></gray>",
                "<gray>'없음' 을 입력하면 비웁니다.</gray>",
            ),
            onCancel = { reopen(player) },
        ) { input ->
            apply(
                if (input == "없음") mutableListOf()
                else input.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            )
            save(); reopen(player)
        }
    }


    private fun save() = urb.boxes.markDirty(box)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = SpawnSettingsMenu(urb, box).open(player)

    companion object {
        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>생성 설정 <gray>|</gray> ${box.name}</dark_gray>")
    }
}
