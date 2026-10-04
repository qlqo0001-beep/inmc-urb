package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import com.inmc.urb.visual.OpenAnimation
import com.inmc.urb.visual.ParticlePreset
import com.inmc.urb.visual.RewardFlair
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Presentation and access limits for one box: particles, hologram, totem flourish, whether the
 * box may be tracked or searched, the per-player cooldown and lifetime cap, and per-box Discord
 * webhook overrides.
 *
 * Every setting here falls back to config.yml when the box does not override it.
 */
class ExtrasMenu(urb: Urb, private val box: RandomBox) : Menu(urb, 54, title(box)) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        // --- visuals ------------------------------------------------------------
        set(
            10,
            Icon.of(
                box.particlePreset.icon,
                "<yellow>파티클</yellow>",
                "<gray>현재: <white>${box.particlePreset.label}</white></gray>",
                "<dark_gray>총 ${ParticlePreset.entries.size - 1}종 + 사용 안 함</dark_gray>",
                "",
                "<yellow>▶ 좌클릭: 다음 효과</yellow>",
                "<yellow>▶ 우클릭: 이전 효과</yellow>",
                "<red>▶ Shift+클릭: 끄기</red>",
            )
        ) { event ->
            box.particlePreset = when {
                event.isShiftClick -> ParticlePreset.NONE
                event.isRightClick -> box.particlePreset.previous()
                else -> box.particlePreset.next()
            }
            save(); redraw(event.whoClicked)
        }

        set(
            12,
            Icon.of(
                if (box.hologramEnabled) Material.ITEM_FRAME else Material.GRAY_DYE,
                "<yellow>홀로그램</yellow>",
                "<gray>현재: </gray>${Icon.toggle(box.hologramEnabled)}",
                "<dark_gray>상자 위에 이름과 남은 시간을 띄웁니다.</dark_gray>",
                "<dark_gray>서버에 저장되지 않는 표시용 엔티티입니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            box.hologramEnabled = !box.hologramEnabled
            save(); redraw(event.whoClicked)
        }

        set(
            14,
            Icon.of(
                box.rewardFlair.icon,
                "<yellow>획득 연출</yellow>",
                "<gray>현재: <white>${box.rewardFlair.label}</white></gray>",
                "<dark_gray>보상이 손에 들어오는 순간 재생됩니다.</dark_gray>",
                "<dark_gray>${flairHint(box.rewardFlair)}</dark_gray>",
                "<dark_gray>총 ${RewardFlair.entries.size - 1}종 + 없음</dark_gray>",
                "",
                "<yellow>▶ 좌클릭: 다음 연출</yellow>",
                "<yellow>▶ 우클릭: 이전 연출</yellow>",
                "<red>▶ Shift+클릭: 끄기</red>",
            )
        ) { event ->
            box.rewardFlair = when {
                event.isShiftClick -> RewardFlair.NONE
                event.isRightClick -> box.rewardFlair.previous()
                else -> box.rewardFlair.next()
            }
            save(); redraw(event.whoClicked)
            (event.whoClicked as? Player)?.let { urb.visuals.playFlair(it, box.rewardFlair, null) }
        }

        set(
            16,
            Icon.of(
                box.openAnimation.icon,
                "<yellow>오픈 연출</yellow>",
                buildList {
                    add("<gray>현재: <white>${box.openAnimation.label}</white></gray>")
                    addAll(box.openAnimation.description)
                    add("")
                    add("<dark_gray>추첨은 연출 전에 이미 끝나 있습니다.</dark_gray>")
                    add("<dark_gray>중간에 창을 닫아도 보상은 그대로 지급됩니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 좌클릭: 다음 연출</yellow>")
                    add("<yellow>▶ 우클릭: 이전 연출</yellow>")
                    add("<red>▶ Shift+클릭: 끄기</red>")
                },
            )
        ) { event ->
            box.openAnimation = when {
                event.isShiftClick -> OpenAnimation.NONE
                event.isRightClick -> box.openAnimation.previous()
                else -> box.openAnimation.next()
            }
            save(); redraw(event.whoClicked)
        }

        // --- discovery ----------------------------------------------------------
        set(
            19,
            Icon.of(
                if (box.searchable) Material.MAP else Material.GRAY_DYE,
                "<yellow>검색 허용</yellow>",
                "<gray>현재: </gray>${Icon.toggle(box.searchable)}",
                "<dark_gray>끄면 /urb search 목록에 나오지 않습니다.</dark_gray>",
                "<dark_gray>몰래 숨겨두는 상자에 사용하세요.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            box.searchable = !box.searchable
            save(); redraw(event.whoClicked)
        }

        set(
            21,
            Icon.of(
                if (box.trackable) Material.COMPASS else Material.GRAY_DYE,
                "<yellow>추적 허용</yellow>",
                "<gray>현재: </gray>${Icon.toggle(box.trackable)}",
                "<dark_gray>끄면 나침반 추적과 안내 파티클이 막힙니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            )
        ) { event ->
            box.trackable = !box.trackable
            save(); redraw(event.whoClicked)
        }

        // --- per-player limits --------------------------------------------------
        set(
            23,
            Icon.of(
                Material.CLOCK, "<yellow>1인 재오픈 대기시간</yellow>",
                "<gray>현재: <white>${if (box.perPlayerCooldownSeconds > 0) Durations.format(box.perPlayerCooldownSeconds) else "제한 없음"}</white></gray>",
                "<dark_gray>같은 유저가 이 상자를 다시 열기까지의 시간입니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 입력 (0 = 제한 없음)</yellow>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            urb.prompts.request(
                player,
                listOf(
                    "<yellow>1인 재오픈 대기시간을 입력하세요.</yellow>",
                    "<gray>형식: <white>1d 1h 1m 1s</white>, 0 이면 제한 없음</gray>",
                ),
                onCancel = { reopen(player) },
            ) { input ->
                box.perPlayerCooldownSeconds = Durations.parse(input, 0L).coerceAtLeast(0L)
                save(); reopen(player)
            }
        }

        set(
            25,
            Icon.of(
                Material.BARRIER, "<yellow>1인 최대 오픈 횟수</yellow>",
                "<gray>현재: <white>${if (box.maxOpensPerPlayer > 0) "${box.maxOpensPerPlayer}회" else "제한 없음"}</white></gray>",
                if (box.openLimitResetSeconds > 0L)
                    "<gray>초기화: <white>${Durations.format(box.openLimitResetSeconds)}</white>마다</gray>"
                else "<dark_gray>초기화 없음 - 평생 기준입니다.</dark_gray>",
                "<dark_gray>고정 설치 상자를 반복해서 먹는 것을 막습니다.</dark_gray>",
                "<dark_gray>고정 좌표 상자는 위치마다 따로 셉니다.</dark_gray>",
                "",
                "<yellow>▶ 좌클릭 +1 / 우클릭 -1 (Shift ±10)</yellow>",
                "<red>▶ Shift+우클릭으로 0 까지 내리면 제한 해제</red>",
            )
        ) { event ->
            val delta = (if (event.isShiftClick) 10 else 1) * (if (event.isLeftClick) 1 else -1)
            box.maxOpensPerPlayer = (box.maxOpensPerPlayer + delta).coerceIn(0, 10_000)
            save(); redraw(event.whoClicked)
        }

        set(
            27,
            Icon.of(
                if (box.openLimitResetSeconds > 0L) Material.RECOVERY_COMPASS else Material.GRAY_DYE,
                "<yellow>오픈 횟수 초기화 주기</yellow>",
                buildList {
                    add(
                        if (box.openLimitResetSeconds > 0L)
                            "<gray>현재: <white>${Durations.format(box.openLimitResetSeconds)}</white>마다 초기화</gray>"
                        else "<gray>현재: <white>초기화 없음 (평생 제한)</white></gray>"
                    )
                    add("")
                    add("<gray>위 '1인 최대 오픈 횟수' 가 다시 채워지는 주기입니다.</gray>")
                    add("<dark_gray>0 이면 초기화하지 않습니다. (기본값)</dark_gray>")
                    add("<dark_gray>예: 1d 로 두면 하루에 ${if (box.maxOpensPerPlayer > 0) box.maxOpensPerPlayer else 0}번까지</dark_gray>")
                    add("")
                    add("<dark_gray>주기는 서버 시계가 아니라 유저가 그 상자를</dark_gray>")
                    add("<dark_gray>처음 연 시각부터 셉니다.</dark_gray>")
                    if (box.maxOpensPerPlayer <= 0) {
                        add("")
                        add("<yellow>⚠ 최대 오픈 횟수가 0이라 지금은 효과가 없습니다.</yellow>")
                    }
                    add("")
                    add("<yellow>▶ 클릭하여 입력 (0 = 초기화 없음)</yellow>")
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            urb.prompts.request(
                player,
                listOf(
                    "<yellow>오픈 횟수 초기화 주기를 입력하세요.</yellow>",
                    "<gray>형식: <white>1d 1h 1m 1s</white>, 0 이면 초기화하지 않습니다.</gray>",
                ),
                onCancel = { reopen(player) },
            ) { input ->
                box.openLimitResetSeconds = Durations.parse(input, 0L).coerceAtLeast(0L)
                save(); reopen(player)
            }
        }

        set(
            28,
            Icon.of(
                Material.WRITABLE_BOOK, "<yellow>오픈 기록 초기화</yellow>",
                "<gray>이 상자의 1인 제한 기록을 모두 지웁니다.</gray>",
                "<dark_gray>대기시간과 최대 횟수가 전원 리셋됩니다.</dark_gray>",
                "",
                "<red>▶ Shift+클릭</red>",
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (!event.isShiftClick) return@set
            val cleared = urb.openRecords.reset(box.name)
            player.sendMessage(Text.render("<yellow>오픈 기록 ${cleared}건을 초기화했습니다.</yellow>", null, player))
            redraw(player)
        }

        // --- discord overrides --------------------------------------------------
        webhookButton(
            30, "스폰 알림 웹훅", box.discordSpawnWebhook,
            urb.config.discord.spawnUrl(null),
        ) { box.discordSpawnWebhook = it }

        webhookButton(
            32, "당첨 공지 웹훅", box.discordRewardWebhook,
            urb.config.discord.rewardUrl(null),
        ) { box.discordRewardWebhook = it }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { BoxManageMenu(urb, box).open(it) }
        }
        set(53, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    private fun webhookButton(
        slot: Int,
        label: String,
        current: String?,
        fallback: String,
        apply: (String?) -> Unit,
    ) {
        set(
            slot,
            Icon.of(
                if (current != null) Material.PAPER else Material.MAP,
                "<yellow>$label</yellow>",
                buildList {
                    if (current != null) {
                        add("<gray>이 상자 전용 주소를 사용합니다.</gray>")
                        add("<dark_gray>${maskUrl(current)}</dark_gray>")
                    } else {
                        add("<gray>config.yml 값을 따릅니다.</gray>")
                        add("<dark_gray>${if (fallback.isBlank()) "설정되지 않음" else maskUrl(fallback)}</dark_gray>")
                    }
                    add("")
                    add("<yellow>▶ 좌클릭: 주소 입력</yellow>")
                    add("<red>▶ 우클릭: config 값으로 되돌리기</red>")
                },
            )
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                apply(null)
                save(); redraw(player)
                return@set
            }
            urb.prompts.request(
                player,
                listOf(
                    "<yellow>${label} 주소를 입력하세요.</yellow>",
                    "<gray>디스코드 채널 설정 → 연동 → 웹후크에서 복사한 URL</gray>",
                    "<gray>'없음' 을 입력하면 config 값을 따릅니다.</gray>",
                ),
                onCancel = { reopen(player) },
            ) { input ->
                apply(if (input == "없음" || input.isBlank()) null else input.trim())
                save(); reopen(player)
            }
        }
    }

    private fun flairHint(flair: RewardFlair): String = when (flair) {
        RewardFlair.NONE -> "연출 없음"
        RewardFlair.TOTEM -> "화면 전체가 번쩍입니다."
        RewardFlair.FIREWORK -> "상자 자리에서 폭죽이 터집니다. (피해 없음)"
        RewardFlair.LIGHTNING -> "번개 모양만 내려칩니다. (피해·화재 없음)"
        RewardFlair.FANFARE -> "종소리 아르페지오가 울립니다."
        RewardFlair.BEAM -> "하늘로 빛기둥이 솟습니다."
    }

    /** Webhook URLs contain a secret token; never show it in full inside a GUI. */
    private fun maskUrl(url: String): String =
        if (url.length <= 40) url else url.take(40) + "..."

    private fun save() = urb.boxes.markDirty(box)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = ExtrasMenu(urb, box).open(player)

    companion object {
        private fun title(box: RandomBox) =
            Text.renderFlat("<dark_gray>연출 · 제한 <gray>|</gray> ${box.name}</dark_gray>")
    }
}
