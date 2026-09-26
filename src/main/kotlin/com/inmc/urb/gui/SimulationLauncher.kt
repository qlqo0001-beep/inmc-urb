package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import com.inmc.urb.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.entity.Player

/**
 * Asks for an iteration count, runs the simulation, then opens the results.
 *
 * Free-form entry rather than preset buttons, so an admin can dial the precision they need:
 * a 0.01% reward only shows a stable rate once the run is large enough to hit it a few
 * hundred times.
 */
class SimulationLauncher(private val urb: Urb) {

    fun prompt(player: Player, box: RandomBox) {
        if (box.rewards.isEmpty()) {
            urb.messages.send(player, "box-no-rewards", Ph.of().box(box.displayName))
            return
        }
        if (urb.simulations.isRunning) {
            urb.messages.send(player, "simulate-busy", Ph.of().box(urb.simulations.runningFor() ?: "?"))
            return
        }

        val max = urb.config.simulationMaxIterations
        val rarest = box.rewards.minOf { it.chance }

        urb.prompts.request(
            player,
            listOf(
                "<yellow>시행 횟수를 입력하세요. <gray>(최대 ${String.format("%,d", max)})</gray></yellow>",
                "<gray>이 상자의 최저 확률은 <white>${kr.inmc.core.util.Numbers.chance(rarest)}%</white> 입니다.</gray>",
                "<gray>그 아이템이 <white>${String.format("%,d", suggestedFor(rarest))}</white>회쯤이면 안정적인 수치가 나옵니다.</gray>",
                "<dark_gray>같은 시드로 비교하려면 '횟수 시드' 형식으로 입력하세요. 예: 100000 42</dark_gray>",
            ),
            onCancel = { BoxManageMenu(urb, box).open(player) },
        ) { input ->
            val parts = input.trim().split(Regex("\\s+"))
            val iterations = parts.getOrNull(0)?.replace(",", "")?.toLongOrNull()
            if (iterations == null || iterations <= 0) {
                urb.messages.send(player, "prompt-invalid-number")
                prompt(player, box)
                return@request
            }
            val seed = parts.getOrNull(1)?.toLongOrNull() ?: System.nanoTime()
            run(player, box, iterations.coerceAtMost(max), seed)
        }
    }

    private fun run(player: Player, box: RandomBox, iterations: Long, seed: Long) {
        val started = urb.simulations.start(player, box, iterations, seed) { result ->
            if (!player.isOnline) return@start
            urb.messages.send(
                player,
                if (result.cancelled) "simulate-cancelled" else "simulate-done",
                Ph.of().box(result.displayName)
                    .count(result.iterations.toInt().coerceAtLeast(0))
                    .time("${result.elapsedMillis}ms"),
            )
            SimulationMenu(urb, box, result).open(player)
        }

        if (!started) {
            urb.messages.send(player, "simulate-busy", Ph.of().box(urb.simulations.runningFor() ?: "?"))
            return
        }

        player.closeInventory()
        player.sendMessage(
            Text.render(
                urb.messages.raw(kr.inmc.core.config.MessageCatalog.PREFIX) +
                    urb.messages.raw("simulate-started"),
                Ph.of().box(box.displayName).count(iterations.toInt().coerceAtLeast(0)),
                player,
            )
        )
    }

    /**
     * Iterations needed for the rarest reward to be hit ~500 times, which puts its observed
     * rate within a few percent of the truth. Clamped so the hint stays sensible.
     */
    private fun suggestedFor(rarestChance: Double): Long {
        if (rarestChance <= 0.0) return 100_000L
        val needed = (500.0 / (rarestChance / 100.0)).toLong()
        return needed.coerceIn(10_000L, 10_000_000L)
    }
}
