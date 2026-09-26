package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import com.inmc.urb.box.Reward
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StorageMode
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Per-reward settings: the decimal chance, the amount range, how the item is stored, whether
 * winning it announces, and any commands it runs (spec §47, §83, §101).
 */
class RewardDetailMenu(
    urb: Urb,
    private val box: RandomBox,
    private val reward: Reward,
    private val returnPage: Int,
) : Menu(urb, 45, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(4, previewIcon())

        // --- chance -------------------------------------------------------------
        chanceButton(19, 0.01)
        chanceButton(20, 0.1)
        chanceButton(21, 1.0)
        chanceButton(22, 10.0)

        set(
            23,
            Icon.of(
                Material.WRITABLE_BOOK, "<yellow>확률 직접 입력</yellow>",
                "<gray>현재: <yellow>${Numbers.chance(reward.chance)}%</yellow></gray>",
                "<dark_gray>0.01 ~ 100 사이, 소수점 두 자리</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            urb.prompts.requestDouble(
                player,
                listOf(
                    "<yellow>확률을 입력하세요. <gray>(0.01 ~ 100)</gray></yellow>",
                    "<gray>예: <white>12.34</white></gray>",
                ),
                min = Numbers.MIN_CHANCE,
                max = Numbers.MAX_CHANCE,
                onCancel = { reopen(player) },
            ) { value ->
                reward.chance = value
                save()
                reopen(player)
            }
        }

        // --- amounts ------------------------------------------------------------
        set(
            29,
            Icon.of(
                Material.IRON_NUGGET, "<yellow>최소 수량</yellow>",
                "<gray>현재: <white>${reward.minAmount}개</white></gray>",
                "",
                "<yellow>▶ 좌클릭 +1  /  우클릭 -1</yellow>",
                "<yellow>▶ Shift 로 ±10</yellow>",
            )
        ) { event ->
            reward.minAmount += Editors.step(event, 1)
            save(); redraw(event.whoClicked)
        }

        set(
            30,
            Icon.of(
                Material.GOLD_NUGGET, "<yellow>최대 수량</yellow>",
                "<gray>현재: <white>${reward.maxAmount}개</white></gray>",
                "",
                "<yellow>▶ 좌클릭 +1  /  우클릭 -1</yellow>",
                "<yellow>▶ Shift 로 ±10</yellow>",
            )
        ) { event ->
            reward.maxAmount += Editors.step(event, 1)
            save(); redraw(event.whoClicked)
        }

        // --- reference source ---------------------------------------------------
        val candidates = urb.itemResolver.candidates(reward.item)
        set(
            28,
            Icon.of(
                if (candidates.size > 1) Material.SPYGLASS else Material.GRAY_DYE,
                "<yellow>참조 플러그인</yellow>",
                buildList {
                    add("<gray>현재: <white>${refLabel(reward.item.ref)}</white></gray>")
                    add("<dark_gray>${reward.item.ref.serialize()}</dark_gray>")
                    add("")
                    when {
                        candidates.isEmpty() -> {
                            add("<gray>이 아이템은 후보를 다시 계산할 수 없습니다.</gray>")
                            add("<dark_gray>스냅샷이 없어 원본을 되살릴 수 없습니다.</dark_gray>")
                        }

                        candidates.size == 1 -> {
                            add("<gray>이 아이템을 알아본 플러그인이 하나뿐입니다.</gray>")
                            add("<dark_gray>선택할 다른 후보가 없습니다.</dark_gray>")
                        }

                        else -> {
                            add("<gray>이 아이템을 <white>${candidates.size}개</white> 플러그인이 알아봅니다.</gray>")
                            add("<dark_gray>어느 쪽 정의로 지급할지 고르세요.</dark_gray>")
                            add("")
                            candidates.forEach { candidate ->
                                val marker =
                                    if (candidate == reward.item.ref) "<green>▶</green>" else "<dark_gray>·</dark_gray>"
                                add("$marker <dark_gray>${refLabel(candidate)} - ${candidate.serialize()}</dark_gray>")
                            }
                            add("")
                            add("<red>⚠ 바꾸면 그 플러그인의 정의로만 지급됩니다.</red>")
                            add("<dark_gray>이름·스탯·등급이 사라질 수 있으니, 아이템을</dark_gray>")
                            add("<dark_gray>실제로 정의한 쪽을 고르세요.</dark_gray>")
                            add("")
                            add("<yellow>▶ 좌클릭: 다음 후보  /  우클릭: 이전 후보</yellow>")
                        }
                    }
                },
            )
        ) { event ->
            if (candidates.size <= 1) return@set
            val index = candidates.indexOf(reward.item.ref)
            val next = if (event.isRightClick) {
                candidates[(index - 1 + candidates.size) % candidates.size]
            } else {
                candidates[(index + 1) % candidates.size]
            }
            reward.item = reward.item.copy(ref = next)
            save(); redraw(event.whoClicked)
        }

        // --- storage mode -------------------------------------------------------
        val reference = reward.item.mode == StorageMode.REFERENCE
        val canReference = reward.item.ref != ItemRef.None
        set(
            32,
            Icon.of(
                if (reference) Material.RECOVERY_COMPASS else Material.BUNDLE,
                if (reference) "<green>저장 방식: 참조 (자동 갱신)</green>" else "<yellow>저장 방식: 스냅샷 (고정)</yellow>",
                buildList {
                    add("<dark_gray>${reward.item.ref.serialize()}</dark_gray>")
                    add("")
                    if (reference) {
                        add("<gray>지급할 때마다 원본 정의대로 새로 만듭니다.</gray>")
                        add("<gray>MMOItems 등에서 아이템을 수정하면 그대로 반영되고,</gray>")
                        add("<gray>새로 발급한 아이템과도 정상적으로 겹칩니다.</gray>")
                    } else {
                        add("<gray>등록 당시의 아이템을 그대로 고정해 지급합니다.</gray>")
                        add("<gray>원본 플러그인이 사라져도 동작합니다.</gray>")
                    }
                    add("")
                    if (canReference) add("<yellow>▶ 클릭하여 전환</yellow>")
                    else add("<red>이 아이템은 참조로 표현할 수 없습니다.</red>")
                },
            )
        ) { event ->
            if (!canReference) return@set
            reward.item = reward.item.withMode(reward.item.mode.toggle())
            save(); redraw(event.whoClicked)
        }

        // --- announce -----------------------------------------------------------
        set(
            33,
            Icon.of(
                if (reward.announce) Material.BELL else Material.GRAY_DYE,
                "<yellow>당첨 시 서버 공지</yellow>",
                "<gray>현재: </gray>${Icon.toggle(reward.announce)}",
                "<dark_gray>켜면 브로드캐스트 + 공지 확인창 기록 +</dark_gray>",
                "<dark_gray>디스코드 알림이 함께 나갑니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            reward.announce = !reward.announce
            save(); redraw(event.whoClicked)
        }

        // --- commands -----------------------------------------------------------
        set(
            34,
            Icon.of(
                Material.COMMAND_BLOCK, "<yellow>실행 명령어</yellow>",
                buildList {
                    if (reward.commands.isEmpty()) add("<gray>등록된 명령어가 없습니다.</gray>")
                    else reward.commands.forEach { add("<dark_gray>/$it</dark_gray>") }
                    add("")
                    add("<gray>아이템 지급: </gray>${Icon.toggle(reward.giveItem)}")
                    add("")
                    add("<yellow>▶ 좌클릭: 명령어 편집</yellow>")
                    add("<yellow>▶ 우클릭: 아이템 지급 여부 전환</yellow>")
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                reward.giveItem = !reward.giveItem
                save(); redraw(player)
                return@set
            }
            urb.prompts.request(
                player,
                listOf(
                    "<yellow>실행할 명령어를 입력하세요. <gray>( | 로 여러 개 )</gray></yellow>",
                    "<gray>예: <white>give {플레이어네임} diamond 1|say {플레이어네임} 축하합니다</white></gray>",
                    "<gray>'없음' 을 입력하면 모두 지웁니다.</gray>",
                ),
                onCancel = { reopen(player) },
            ) { input ->
                reward.commands = if (input == "없음") mutableListOf()
                else input.split('|').map { it.trim().removePrefix("/") }
                    .filter { it.isNotEmpty() }.toMutableList()
                save()
                reopen(player)
            }
        }

        // --- tier ---------------------------------------------------------------
        set(
            31,
            Icon.of(
                if (reward.tier != null) Material.BUNDLE else Material.CHEST,
                "<yellow>소속 티어</yellow>",
                buildList {
                    add("<gray>현재: <white>${reward.tier ?: "기본 풀"}</white></gray>")
                    if (box.tiers.isEmpty()) {
                        add("")
                        add("<dark_gray>이 상자에는 아직 티어가 없습니다.</dark_gray>")
                        add("<dark_gray>상자 관리 → 보상 티어에서 먼저 만드세요.</dark_gray>")
                    } else {
                        add("<gray>티어마다 별도의 배출 개수로 뽑힙니다.</gray>")
                        add("")
                        box.tiers.forEach { (name, tier) ->
                            val marker = if (name == reward.tier) "<green>▶</green>" else "<dark_gray>·</dark_gray>"
                            add("$marker <dark_gray>$name (${tier.minRolls}~${tier.maxRolls}개)</dark_gray>")
                        }
                        add("")
                        add("<yellow>▶ 좌클릭: 다음 티어</yellow>")
                        add("<red>▶ 우클릭: 기본 풀로</red>")
                    }
                },
            )
        ) { event ->
            if (box.tiers.isEmpty()) return@set
            reward.tier = if (event.isRightClick) null else nextTier(reward.tier)
            save(); redraw(event.whoClicked)
        }

        set(36, Icon.back()) { event -> reopenList(event.whoClicked) }

        set(
            44,
            Icon.of(
                Material.TNT, "<red>이 보상 삭제</red>",
                "<gray>목록에서 제거합니다.</gray>",
                "",
                "<red>▶ Shift+클릭</red>",
            )
        ) { event ->
            if (!event.isShiftClick) return@set
            box.rewards.remove(reward)
            save()
            reopenList(event.whoClicked)
        }
    }

    /** Which plugin a reference belongs to, in the admin's own vocabulary. */
    private fun refLabel(ref: ItemRef): String = when (ref) {
        is ItemRef.MMOItems -> "MMOItems"
        is ItemRef.Namespaced -> when (ref.namespace) {
            "itemsadder" -> "ItemsAdder"
            "nexo" -> "Nexo"
            "oraxen" -> "Oraxen"
            "ecoitems" -> "EcoItems"
            else -> ref.namespace
        }

        is ItemRef.Vanilla -> "바닐라"
        is ItemRef.None -> "스냅샷 전용"
    }

    /** Cycles 기본 풀 -> tier 1 -> tier 2 -> ... -> 기본 풀. */
    private fun nextTier(current: String?): String? {
        val names = box.tiers.keys.toList()
        if (names.isEmpty()) return null
        val index = names.indexOf(current)
        if (index < 0) return names.first()
        return names.getOrNull(index + 1)
    }

    private fun chanceButton(slot: Int, delta: Double) {
        set(
            slot,
            Icon.of(
                Material.LIGHT_BLUE_DYE, "<aqua>확률 ±${Numbers.chance(delta)}%</aqua>",
                "<gray>현재: <yellow>${Numbers.chance(reward.chance)}%</yellow></gray>",
                "",
                "<yellow>▶ 좌클릭: +${Numbers.chance(delta)}</yellow>",
                "<yellow>▶ 우클릭: -${Numbers.chance(delta)}</yellow>",
            )
        ) { event ->
            reward.chance += if (event.isLeftClick) delta else -delta
            save(); redraw(event.whoClicked)
        }
    }

    private fun previewIcon(): org.bukkit.inventory.ItemStack {
        val icon = urb.itemResolver.icon(reward.item)
        return Icon.annotate(
            icon.stack,
            lore = listOf(
                "<gray>확률: <yellow>${Numbers.chance(reward.chance)}%</yellow></gray>",
                "<gray>수량: <white>${reward.minAmount} ~ ${reward.maxAmount}개</white></gray>",
                "<gray>티어: <white>${reward.tier ?: "기본 풀"}</white></gray>",
                "<dark_gray>${reward.item.ref.serialize()}</dark_gray>",
            ) + icon.notes(),
        )
    }


    private fun save() = urb.boxes.markDirty(box)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = RewardDetailMenu(urb, box, reward, returnPage).open(player)

    private fun reopenList(who: org.bukkit.entity.HumanEntity) {
        (who as? Player)?.let { RewardListMenu(urb, box, returnPage).open(it) }
    }

    companion object {
        private val TITLE = Text.renderFlat("<dark_gray>보상 상세 설정</dark_gray>")
    }
}
