package com.inmc.urb.scheduler

import com.inmc.urb.Urb
import kr.inmc.core.scheduler.TickerBase
import org.bukkit.Bukkit

/**
 * The plugin's one and only repeating task, running at 1 Hz.
 *
 * Everything periodic lives here rather than in its own scheduled job: open-progress titles,
 * despawn sweeps, the auto-airdrop schedule, queue expiry, tracking action bars and the
 * debounced disk/Discord flushes. Movement cancellation is event-driven and file writes are
 * one-shot async jobs, so neither adds a second repeating task.
 */
class Ticker(private val urb: Urb) : TickerBase(urb.plugin) {

    override val periodTicks = PERIOD_TICKS

    override fun ready(): Boolean = urb.ready

    override fun tick(now: Long) {
        step("open-progress") { urb.opens.tick() }
        step("despawn") { urb.spawns.tick(now) }
        step("auto-spawn") { urb.spawns.tickAutoSpawn(now) }
        step("prompts") { urb.prompts.tick(now) }
        step("tracking") { urb.tracking.tick() }
        step("visuals") { urb.visuals.tick() }
        step("simulation") { urb.simulations.tickProgress() }
        step("cooldowns") {
            urb.opens.purgeCooldowns(now)
            urb.tracking.purge(now)
        }
        step("flush") {
            urb.boxes.flushDirty()
            urb.spawns.flushState()
            urb.notices.flush()
            urb.openRecords.flush()
            urb.stats.flush()
        }
        step("discord") {
            // Deliberately the server's async pool rather than the config I/O worker: a
            // webhook can hang for its full 10s timeout, and that must never delay a box save.
            urb.discord.drainOne()?.let { work ->
                Bukkit.getScheduler().runTaskAsynchronously(urb.plugin, Runnable { work() })
            }
        }
    }

    companion object {
        const val PERIOD_TICKS = 20L
    }
}
