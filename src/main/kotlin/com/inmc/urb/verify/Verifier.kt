package com.inmc.urb.verify

import com.inmc.urb.Urb
import com.inmc.urb.UrbPlugin
import com.inmc.urb.box.LootRoller
import com.inmc.urb.box.RandomBox
import com.inmc.urb.box.SpawnOutcome
import com.inmc.urb.gui.PreviewMenu
import com.inmc.urb.util.Ph
import kr.inmc.core.event.SignalCatalog
import org.bukkit.Material
import org.bukkit.entity.Player
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `/urb verify` — 서버 안에서 상자 정의·추첨기·조건·소환·홀로그램·진입점을 실제로 돌려 확인한다(드랍·상점 검증기와 같은 틀, 2026-10-08).
 *
 * - 소환은 검증하는 사람 머리 위 4칸(비어 있을 때만)에 잠깐 세웠다가 치운다 — 블록은 원래대로.
 * - 보상을 실제로 주는 길(`grant`·열기)은 돌리지 않는다. 추첨은 `LootRoller` 를 직접 굴려 개수 범위만 본다.
 * - 열쇠·돈 조건은 빈손·잔고 부족일 때만 거절을 확인한다(가진 사람은 건너뜀).
 */
class Verifier(private val urb: Urb) {

    data class Result(val name: String, val failure: String?) {
        val skipped: Boolean get() = failure?.startsWith(SKIP) == true
    }

    private class Check(val name: String, val run: (Stage) -> String?)

    fun run(player: Player) {
        val stage = Stage(urb, player)
        val results = CHECKS.map { check ->
            val failure = try {
                check.run(stage)
            } catch (t: Throwable) {
                "검증기 오류: " + t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            }
            Result(check.name, failure)
        }
        stage.tearDown()
        player.closeInventory()

        val failures = results.filter { it.failure != null && !it.skipped }
        val skips = results.filter { it.skipped }
        urb.messages.send(
            player, "verify-done",
            Ph.of().amount(results.size - failures.size - skips.size).count(failures.size)
                .item(if (skips.isEmpty()) "" else " · 건너뜀 ${skips.size}"),
        )
        for (f in failures) urb.messages.send(player, "verify-failure", Ph.of().item("${f.name} — ${f.failure}"))
        for (s in skips) urb.messages.send(player, "verify-skipped", Ph.of().item("${s.name} — ${s.failure!!.removePrefix(SKIP).trim()}"))

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = urb.io.file("verify", "urb-$stamp.txt")
        val text = buildString {
            appendLine("# inmc-urb 검증 - ${LocalDateTime.now()} - ${player.name}")
            for (r in results) {
                appendLine((if (r.failure == null) "PASS " else if (r.skipped) "SKIP " else "FAIL ") + r.name + (r.failure?.let { " — $it" } ?: ""))
            }
        }
        urb.io.asyncRun {
            file.parentFile.mkdirs()
            kr.inmc.core.util.AtomicFiles.write(file, text)
        }
        urb.messages.send(player, "verify-report", Ph.of().item("plugins/${urb.plugin.name}/verify/${file.name}"))
    }

    /** 검사들이 쓰는 무대. 소환한 상자는 끝에 반드시 치운다. */
    class Stage(val urb: Urb, val player: Player) {
        /** 소환 자리 — 머리 위 4칸. */
        val spot = player.location.block.getRelative(0, 4, 0)
        var spawnedAt: org.bukkit.Location? = null

        fun box(): RandomBox? = urb.boxes.all().firstOrNull { it.enabled && it.rewards.isNotEmpty() } ?: urb.boxes.all().firstOrNull { it.enabled }

        fun tearDown() {
            spawnedAt?.let { at -> urb.spawns.at(at)?.let { urb.spawns.remove(it, announce = false) } }
            spawnedAt = null
        }
    }

    companion object {
        const val SKIP = "건너뜀:"

        private fun ok(condition: Boolean, failure: String): String? = if (condition) null else failure

        private val CHECKS: List<Check> = listOf(
            Check("상자 정의 — 하나 이상 읽혔고 켜진 상자에 보상이 있다") { s ->
                val all = s.urb.boxes.all()
                ok(all.isNotEmpty(), "상자가 하나도 없습니다")
                    ?: all.filter { it.enabled && it.rewards.isEmpty() }.takeIf { it.isNotEmpty() }
                        ?.let { "보상이 없는 상자: " + it.joinToString(", ") { b -> b.name } }
            },
            Check("추첨기 — 뽑는 개수가 최소~최대(보상 수 한도) 안이다") { s ->
                val box = s.box()?.takeIf { it.rewards.isNotEmpty() } ?: return@Check "$SKIP 보상이 있는 상자가 없습니다"
                val rng = java.util.Random(1)
                val lo = minOf(box.minRolls, box.rewards.size)
                val hi = maxOf(lo, minOf(box.maxRolls, box.rewards.size))
                repeat(200) {
                    val picked = LootRoller.select(box.rewards, box.minRolls, box.maxRolls, rng)
                    if (picked.size !in lo..hi) return@Check "'${box.name}' 에서 ${picked.size}개 (${lo}~${hi} 여야)"
                    if (picked.size != picked.toSet().size) return@Check "'${box.name}' 에서 같은 보상이 두 번"
                }
                null
            },
            Check("조건 — 열쇠가 드는 상자는 빈손이면 거절") { s ->
                val box = s.urb.boxes.all().firstOrNull { it.enabled && it.keyItem != null } ?: return@Check "$SKIP 열쇠가 드는 상자가 없습니다"
                if (s.urb.itemMatcher.count(s.player, box.keyItem) > 0) return@Check "$SKIP 열쇠를 들고 있습니다(${box.name})"
                ok(!s.urb.opens.checkConditions(s.player, box), "'${box.name}' 이 열쇠 없이 열립니다")
            },
            Check("조건 — 돈이 드는 상자는 잔고가 모자라면 거절") { s ->
                val box = s.urb.boxes.all().firstOrNull { it.enabled && it.moneyCost > 0.0 } ?: return@Check "$SKIP 돈이 드는 상자가 없습니다"
                if (!s.urb.economy.isEnabled) return@Check "$SKIP 경제 플러그인이 없습니다"
                if (s.urb.economy.has(s.player, box.moneyCost, box.currency)) return@Check "$SKIP 잔고가 넉넉합니다(${box.name})"
                ok(!s.urb.opens.checkConditions(s.player, box), "'${box.name}' 이 돈 없이 열립니다")
            },
            Check("소환 → 서 있는 상자 → 치우기(블록 복구)") { s ->
                val box = s.box() ?: return@Check "$SKIP 켜진 상자가 없습니다"
                if (!s.spot.type.isAir) return@Check "$SKIP 머리 위 4칸(${s.spot.x}, ${s.spot.y}, ${s.spot.z})이 비어 있지 않습니다"
                val at = s.spot.location
                val outcome = s.urb.spawns.spawnExact(box, at)
                ok(outcome == SpawnOutcome.PLACED, "소환 결과 $outcome") ?: run {
                    s.spawnedAt = at
                    val standing = s.urb.spawns.at(at)
                    ok(standing != null, "소환했는데 그 자리에 상자가 없습니다") ?: run {
                        s.urb.spawns.remove(standing!!, announce = false)
                        s.spawnedAt = null
                        ok(s.urb.spawns.at(at) == null, "치웠는데 아직 등록돼 있습니다")
                            ?: ok(s.spot.type.isAir, "치운 뒤 블록이 ${s.spot.type} 입니다(공기여야)")
                    }
                }
            },
            Check("홀로그램 — 치운 뒤 떠 있는 것이 없다") { s ->
                val stray = s.urb.visuals.sweepStray()
                ok(stray == 0, "떠 있던 홀로그램 ${stray}개를 치웠습니다")
            },
            Check("신호 — SignalCatalog 에 urb 신호가 등록돼 있다") { s ->
                ok(SignalCatalog.sources().any { it.contains("urb", ignoreCase = true) }, "urb 신호가 없습니다: " + SignalCatalog.sources())
            },
            Check("grant 진입점 — 없는 상자는 false") { s ->
                val plugin = s.urb.plugin as? UrbPlugin ?: return@Check "$SKIP 플러그인 본체가 아닙니다"
                ok(!plugin.grant(s.player, "zz_verify_no_such_box"), "없는 상자가 true 를 줬습니다")
            },
            Check("열기 세션 — 검증하는 사람에게 열린 세션이 없다") { s ->
                ok(!s.urb.opens.hasSession(s.player.uniqueId), "열기 세션이 남아 있습니다")
            },
            Check("미리보기 화면이 열린다") { s ->
                val box = s.box() ?: return@Check "$SKIP 켜진 상자가 없습니다"
                PreviewMenu(s.urb, box, viewerIsAdmin = true).open(s.player)
                val opened = s.player.openInventory.topInventory.holder is PreviewMenu
                s.player.closeInventory()
                ok(opened, "미리보기 화면이 안 열렸습니다")
            },
        )
    }
}
