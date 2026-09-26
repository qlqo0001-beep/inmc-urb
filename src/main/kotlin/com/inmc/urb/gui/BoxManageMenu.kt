package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import com.inmc.urb.box.RollForecast
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Numbers
import com.inmc.urb.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Hub for one box (spec §43-87).
 *
 * The spec's items 2 and 3 - spawn placement and the spawn-related events - are merged into a
 * single "생성/스폰 설정" screen, which §88 explicitly allows.
 */
class BoxManageMenu(urb: Urb, private val box: RandomBox) : Menu(urb, 45, title(box)) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            10,
            Icon.of(
                Material.CHEST, "<yellow>① 아이템 설정</yellow>",
                "<gray>보상 아이템을 등록/제거하고 확률을 조정합니다.</gray>",
                "",
                "<gray>등록된 보상: <white>${box.rewards.size}개</white></gray>",
                "<gray>공지 대상: <white>${box.rewards.count { it.announce }}개</white></gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event -> open(event.whoClicked) { RewardListMenu(urb, box) } }

        set(
            12,
            Icon.of(
                Material.MAP, "<yellow>② 생성/스폰 설정</yellow>",
                "<gray>영역, 좌표, 월드, 주기, 소멸, 블록을 설정합니다.</gray>",
                "",
                "<gray>방식: <white>${box.spawnMode.label()}</white></gray>",
                "<gray>월드: <white>${box.world}</white></gray>",
                "<gray>자동 생성: <white>${if (box.autoSpawnIntervalSeconds > 0) Durations.formatShort(box.autoSpawnIntervalSeconds) else "사용 안 함"}</white></gray>",
                "<gray>소멸: <white>${if (box.despawnSeconds > 0) Durations.formatShort(box.despawnSeconds) else "영구"}</white></gray>",
                *box.autoSpawnProblem()?.let {
                    arrayOf("", "<red>⚠ 자동 생성 불가: $it</red>")
                } ?: emptyArray(),
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event -> open(event.whoClicked) { SpawnSettingsMenu(urb, box) } }

        set(
            14,
            Icon.of(
                Material.TRIPWIRE_HOOK, "<yellow>③ 오픈 조건</yellow>",
                "<gray>캡슐 아이템, 열쇠, 소모될 돈을 설정합니다.</gray>",
                "",
                "<gray>캡슐: <white>${box.capsuleItem?.label() ?: "없음"}</white></gray>",
                "<gray>열쇠: <white>${box.keyItem?.label() ?: "없음"}</white></gray>",
                "<gray>비용: <white>${if (box.moneyCost > 0) Numbers.money(box.moneyCost) + "원" else "없음"}</white></gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event -> open(event.whoClicked) { ConditionMenu(urb, box) } }

        set(
            16,
            Icon.of(Material.COMPARATOR, "<yellow>④ 내용물 개수</yellow>", rollCountLore())
        ) { event ->
            when {
                event.isShiftClick && event.isLeftClick -> box.minRolls += 1
                event.isShiftClick && event.isRightClick -> box.minRolls -= 1
                event.isLeftClick -> box.maxRolls += 1
                event.isRightClick -> box.maxRolls -= 1
            }
            save()
            refresh()
            (event.whoClicked as? Player)?.updateInventory()
        }

        set(
            28,
            Icon.of(
                Material.OAK_SIGN, "<yellow>⑤ 오픈 이벤트</yellow>",
                "<gray>등장/소멸/오픈 메시지, 실행 명령어, 오픈 방식.</gray>",
                "",
                "<gray>오픈 방식: <white>${box.openMode.label()}</white></gray>",
                "<gray>오픈 명령어: <white>${box.openCommands.size}개</white></gray>",
                "<gray>디스코드 알림: </gray>${Icon.toggle(box.discordAlert)}",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event -> open(event.whoClicked) { OpenEventMenu(urb, box) } }

        set(
            22,
            Icon.of(
                Material.ENCHANTED_BOOK, "<yellow>⑧ 보상 시뮬레이션</yellow>",
                "<gray>실제 추첨기를 원하는 횟수만큼 돌려</gray>",
                "<gray>개수 분포와 보상별 실제 출현률을 계산합니다.</gray>",
                "",
                "<gray>보상 <white>${box.rewards.size}종</white> · 배출 <white>${box.minRolls}~${box.maxRolls}개</white></gray>",
                "<dark_gray>설정 확률과 실제 결과는 최대 개수 제한 때문에</dark_gray>",
                "<dark_gray>다를 수 있습니다. 그 차이를 보여줍니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            SimulationLauncher(urb).prompt(player, box)
        }

        set(
            30,
            Icon.of(
                Material.NETHER_STAR, "<yellow>⑦ 연출 · 제한</yellow>",
                "<gray>파티클, 홀로그램, 추적/검색 허용, 1인 제한.</gray>",
                "",
                "<gray>파티클: <white>${box.particlePreset.label}</white></gray>",
                "<gray>홀로그램: </gray>${Icon.toggle(box.hologramEnabled)}",
                "<gray>검색/추적: </gray>${Icon.toggle(box.searchable)}<gray> / </gray>${Icon.toggle(box.trackable)}",
                "<gray>1인 제한: <white>${if (box.maxOpensPerPlayer > 0) "${box.maxOpensPerPlayer}회" else "없음"}</white></gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event -> open(event.whoClicked) { ExtrasMenu(urb, box) } }

        set(
            29,
            Icon.of(
                Icon.toggleMaterial(box.enabled), "<yellow>상자 활성화</yellow>",
                "<gray>현재: </gray>${Icon.toggle(box.enabled)}",
                "<dark_gray>꺼두면 자동 생성과 오픈이 모두 막힙니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            box.enabled = !box.enabled
            save()
            refresh()
            (event.whoClicked as? Player)?.updateInventory()
        }

        set(
            32,
            Icon.of(
                Material.NAME_TAG, "<yellow>표시 이름 / 설명</yellow>",
                "<gray>이름: <white>${box.displayName}</white></gray>",
                "<gray>설명: <white>${box.lore.size}줄</white></gray>",
                "",
                "<yellow>▶ 좌클릭: 이름 변경</yellow>",
                "<yellow>▶ 우클릭: 설명 편집</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) promptLore(player) else promptDisplayName(player)
        }

        set(
            34,
            Icon.of(
                Material.TNT, "<red>⑥ 상자 삭제</red>",
                "<gray>이 상자와 설정 파일을 삭제합니다.</gray>",
                "",
                "<red>▶ 클릭 (확인 창이 나타납니다)</red>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            ConfirmMenu(
                urb,
                question = "<yellow>⚠ 확인이 필요합니다</yellow>",
                title = "<dark_red>상자 삭제</dark_red>",
                detail = listOf(
                    "<red>'<white>${box.name}</white>' 상자를 삭제합니다.</red>",
                    "<gray>되돌릴 수 없습니다.</gray>",
                ),
                onConfirm = {
                    urb.spawns.reset(box.name)
                    urb.boxes.delete(box.name)
                    urb.messages.send(player, "box-deleted", Ph.of().box(box.displayName))
                    BoxListMenu(urb).open(player)
                },
                onCancel = { BoxManageMenu(urb, box).open(player) },
            ).open(player)
        }

        set(
            38,
            Icon.of(
                Material.ENDER_PEARL, "<aqua>지금 소환 (내 위치)</aqua>",
                "<gray>서 있는 곳에 바로 상자를 소환합니다.</gray>",
                "<dark_gray>최대 개수 제한을 무시합니다.</dark_gray>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            urb.spawns.spawnExact(box, player.location)
            redraw(player)
        }

        set(
            40,
            Icon.of(
                Material.FIREWORK_ROCKET, "<aqua>지금 소환 (설정대로)</aqua>",
                "<gray>이 상자의 생성 설정에 따라 소환을 시도합니다.</gray>",
                "<dark_gray>현재 ${urb.spawns.liveCount(box.name)} / ${box.maxSpawnCount}</dark_gray>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            urb.spawns.requestSpawn(box)
            redraw(player)
        }

        set(
            42,
            Icon.of(
                Material.HOPPER, "<aqua>생성된 상자 회수</aqua>",
                "<gray>이 상자로 생성된 것을 모두 제거합니다.</gray>",
                "<dark_gray>배치 ${urb.spawns.spawnedCount(box.name)} / 대기 ${urb.spawns.pendingCount(box.name)}</dark_gray>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            val removed = urb.spawns.reset(box.name)
            urb.messages.send(player, "reset-done", Ph.of().count(removed))
            refresh()
            player.updateInventory()
        }

        set(
            20,
            Icon.of(
                Material.BUNDLE, "<yellow>⑨ 보상 티어</yellow>",
                "<gray>보상을 여러 풀로 나누고 풀마다 개수를 정합니다.</gray>",
                "",
                "<gray>티어: <white>${box.tiers.size}개</white></gray>",
                "<gray>기본 풀: <white>${box.untieredRewards().size}종</white></gray>",
                if (box.usesTiers) "<gray>합산 최대: <white>${box.effectiveMaxRolls()}개</white></gray>"
                else "<dark_gray>티어를 만들면 '일반 2개 + 희귀 0~1개' 같은 구성이 가능합니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event -> open(event.whoClicked) { TierMenu(urb, box) } }

        set(
            24,
            Icon.of(
                if (box.schedule.enabled) Material.CLOCK else Material.GRAY_DYE,
                "<yellow>⑩ 생성 시간대</yellow>",
                "<gray>자동 생성이 허용되는 요일과 시간입니다.</gray>",
                "",
                "<gray>현재: <white>${box.schedule.describe()}</white></gray>",
                "<dark_gray>수동 소환과 캡슐은 제한을 받지 않습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event -> open(event.whoClicked) { ScheduleMenu(urb, box) } }

        set(
            31,
            Icon.of(
                Material.WRITTEN_BOOK, "<yellow>통계 · 오픈 기록</yellow>",
                "<gray>실제로 몇 번 열렸고 무엇이 나왔는지 봅니다.</gray>",
                "",
                "<gray>누적 오픈: <white>${urb.stats.opens(box.name)}회</white></gray>",
                "<dark_gray>설정 확률과 실제 출현률을 나란히 비교합니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event -> open(event.whoClicked) { StatsMenu(urb, box) } }

        set(
            33,
            Icon.of(
                Material.ITEM_FRAME, "<aqua>상자 복제</aqua>",
                "<gray>설정과 보상 목록을 그대로 새 상자로 만듭니다.</gray>",
                "<dark_gray>좌표·고정 지점·캡슐·열쇠까지 그대로 복사됩니다.</dark_gray>",
                "<dark_gray>복제 후 생성 위치와 캡슐을 반드시 확인하세요.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 새 이름 입력</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            promptCopy(player)
        }

        set(36, Icon.back()) { event -> open(event.whoClicked) { BoxListMenu(urb) } }
        set(44, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    private fun promptDisplayName(player: Player) {
        urb.prompts.request(
            player,
            listOf(
                "<yellow>표시 이름을 입력하세요. <gray>(MiniMessage 사용 가능)</gray></yellow>",
                "<gray>예: <white><rainbow>보급상자</rainbow></white></gray>",
            ),
            onCancel = { BoxManageMenu(urb, box).open(player) },
        ) { input ->
            box.displayName = input
            save()
            BoxManageMenu(urb, box).open(player)
        }
    }

    private fun promptCopy(player: Player) {
        urb.prompts.request(
            player,
            listOf(
                "<yellow>새 상자의 이름을 입력하세요.</yellow>",
                "<gray>영문/숫자/한글/_/- 만 사용할 수 있습니다.</gray>",
            ),
            onCancel = { BoxManageMenu(urb, box).open(player) },
        ) { input ->
            val name = input.trim()
            val clone = when {
                !com.inmc.urb.box.BoxRegistry.isValidName(name) -> {
                    urb.messages.send(player, "box-invalid-name")
                    null
                }

                urb.boxes.exists(name) -> {
                    urb.messages.send(player, "box-exists", Ph.of().box(name))
                    null
                }

                else -> urb.boxes.copy(box, name, urb.config.defaults)
            }
            if (clone != null) {
                urb.messages.send(player, "box-copied", Ph.of().box(box.displayName).raw("{새이름}", name))
                BoxManageMenu(urb, clone).open(player)
            } else {
                BoxManageMenu(urb, box).open(player)
            }
        }
    }

    private fun promptLore(player: Player) {
        urb.prompts.request(
            player,
            listOf(
                "<yellow>설명을 입력하세요. <gray>( | 로 줄을 나눕니다 )</gray></yellow>",
                "<gray>예: <white>희귀 보급품|하루 한 번</white></gray>",
                "<gray>'없음' 을 입력하면 설명을 비웁니다.</gray>",
            ),
            onCancel = { BoxManageMenu(urb, box).open(player) },
        ) { input ->
            box.lore = if (input == "없음") mutableListOf()
            else input.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            save()
            BoxManageMenu(urb, box).open(player)
        }
    }

    /**
     * Live forecast for the roll-count setting.
     *
     * Two things caused real confusion and are called out explicitly: a max above the number of
     * registered rewards is silently clamped (3 rewards can never yield 4 items), and with few
     * rewards at moderate chances the top of the range is simply rare - "max 3" with three 50%
     * rewards lands on 3 only one open in eight.
     */
    private fun rollCountLore(): List<String> {
        val pool = box.untieredRewards()
        val lore = mutableListOf(
            "<gray>한 번 열 때 나올 보상 개수 범위입니다.</gray>",
            "",
            "<gray>현재: <white>${box.minRolls} ~ ${box.maxRolls}개</white></gray>",
        )

        if (box.usesTiers) {
            // With tiers in play this range only governs the untiered pool, so saying "the box
            // yields 1~2" here would be wrong - the total is the sum across every pool.
            lore.add("<yellow>이 범위는 기본 풀(${pool.size}종)에만 적용됩니다.</yellow>")
            lore.add("<gray>티어를 포함한 합산 최대: <white>${box.effectiveMaxRolls()}개</white></gray>")
            lore.add("<dark_gray>⑨ 보상 티어에서 전체 분포를 확인하세요.</dark_gray>")
        }

        val count = pool.size
        if (count == 0) {
            lore.add(if (box.usesTiers) "<gray>기본 풀에 보상이 없습니다.</gray>" else "<red>등록된 보상이 없습니다.</red>")
            lore.add("")
            lore.add("<yellow>▶ 좌클릭: 최대 +1  /  우클릭: 최대 -1</yellow>")
            lore.add("<yellow>▶ Shift+좌클릭: 최소 +1  /  Shift+우클릭: 최소 -1</yellow>")
            return lore
        }

        val effectiveMax = RollForecast.effectiveMax(count, box.maxRolls)
        if (RollForecast.isMaxClamped(count, box.maxRolls)) {
            lore.add("<red>실질 최대: ${effectiveMax}개  ⚠ 등록된 보상이 ${count}종뿐입니다</red>")
        }

        val chances = pool.map { it.chance }
        val distribution = RollForecast.distribution(chances, box.minRolls, box.maxRolls)

        lore.add("")
        lore.add("<gray>예상 개수 분포 <dark_gray>(시뮬레이션 없이 계산)</dark_gray></gray>")
        for (size in distribution.indices) {
            val share = distribution[size] * 100.0
            if (share < 0.005) continue
            val bar = "|".repeat((share / 5).toInt().coerceAtMost(20))
            lore.add("<dark_gray>  ${size}개 <white>${Numbers.chance(share)}%</white> <dark_gray>$bar</dark_gray>")
        }

        val expected = RollForecast.expectedWinners(chances)
        val truncation = RollForecast.truncationRate(chances, box.maxRolls)
        lore.add("")
        lore.add("<gray>평균 당첨: <white>${Numbers.chance(expected)}종</white>  <dark_gray>(확률 합 ${Numbers.chance(expected * 100)}%)</dark_gray></gray>")

        when {
            truncation >= 70.0 -> {
                lore.add("<red>최대 개수로 잘림 ${Numbers.chance(truncation)}%</red>")
                lore.add("<gray>확률을 올려도 결과가 거의 안 바뀝니다.</gray>")
                lore.add("<gray>최대 개수를 올리거나 확률을 낮추세요.</gray>")
            }

            distribution.getOrElse(effectiveMax) { 0.0 } * 100.0 < 20.0 && effectiveMax > 1 -> {
                lore.add("<yellow>${effectiveMax}개가 나올 확률이 낮습니다</yellow>")
                lore.add("<gray>보상을 늘리거나 확률을 올려야 자주 보입니다.</gray>")
            }
        }

        lore.add("")
        lore.add("<yellow>▶ 좌클릭: 최대 +1  /  우클릭: 최대 -1</yellow>")
        lore.add("<yellow>▶ Shift+좌클릭: 최소 +1  /  Shift+우클릭: 최소 -1</yellow>")
        return lore
    }

    private fun save() = urb.boxes.markDirty(box)

    private fun redraw(player: Player) {
        refresh()
        player.updateInventory()
    }

    private inline fun open(who: org.bukkit.entity.HumanEntity, factory: () -> Menu) {
        (who as? Player)?.let { factory().open(it) }
    }

    companion object {
        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>상자 관리 <gray>|</gray> ${box.name}</dark_gray>")
    }
}
