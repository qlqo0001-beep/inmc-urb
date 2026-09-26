package com.inmc.urb.box

import com.inmc.urb.Urb
import com.inmc.urb.util.Ph
import com.inmc.urb.visual.BossBars
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Random
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Admin-only loot table simulator.
 *
 * Runs the **same** [LootRoller] the real open path uses - not a re-implementation - so the
 * numbers cannot drift away from actual behaviour. That is possible because the roller has no
 * Bukkit dependency, which also lets the whole run happen off the main thread.
 *
 * Two things are settled before the worker starts, because they need the main thread:
 * a snapshot of the reward table (so editing a chance mid-run cannot skew the result) and a
 * resolvability check for every reward.
 */
class SimulationEngine(private val urb: Urb) {

    class Run(
        val boxName: String,
        val playerId: UUID,
        val total: Long,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        val done = AtomicLong()

        @Volatile
        var cancelled = false
    }

    @Volatile
    private var active: Run? = null

    /** Last finished result per player, so the GUI can page without re-running. */
    private val lastResults = HashMap<UUID, SimulationResult>()

    val isRunning: Boolean get() = active != null

    fun runningFor(): String? = active?.boxName

    fun lastResult(playerId: UUID): SimulationResult? = lastResults[playerId]

    /**
     * Drops a player's kept result on quit.
     *
     * A result holds the whole snapshotted reward table, snapshot bytes included, so keeping
     * one per admin who ever ran a simulation is a slow leak with nothing to reclaim it.
     */
    fun forget(playerId: UUID) {
        lastResults.remove(playerId)
    }

    fun cancel() {
        active?.cancelled = true
    }

    /**
     * Starts a run. [then] is invoked on the main thread with the finished result.
     * Returns false when another run is already in progress.
     */
    fun start(
        player: Player,
        box: RandomBox,
        iterations: Long,
        seed: Long,
        then: (SimulationResult) -> Unit,
    ): Boolean {
        if (active != null) return false
        if (box.rewards.isEmpty()) return false

        // Snapshot on the main thread: the admin may keep editing while this runs.
        val rewards = box.rewards.map { it.copyOf() }
        val resolvable = rewards.map { urb.itemResolver.icon(it.item).resolved }
        val minRolls = box.minRolls
        val maxRolls = box.maxRolls
        val boxName = box.name
        val displayName = box.displayName
        // Tiers are copied too - the groups must be rebuilt from the snapshot, not the live box.
        val tiers: Map<String, BoxTier> = box.tiers.mapValues { (name, tier) ->
            BoxTier(name, tier.minRolls, tier.maxRolls, tier.enabled)
        }

        val run = Run(boxName, player.uniqueId, iterations)
        active = run

        // The server's async pool, not the config I/O worker: a ten-million iteration run takes
        // seconds, and that worker is what box saves and state flushes queue behind.
        Bukkit.getScheduler().runTaskAsynchronously(urb.plugin, Runnable {
            val result = try {
                simulate(run, boxName, displayName, rewards, resolvable, tiers, minRolls, maxRolls, seed)
            } catch (t: Throwable) {
                urb.logger.log(java.util.logging.Level.SEVERE, "시뮬레이션 실패 ($boxName)", t)
                null
            } finally {
                active = null
            }
            // A run that outlives the plugin has nowhere to land - scheduling onto a disabled
            // plugin throws, and the throw would surface on the worker thread as noise.
            if (!urb.plugin.isEnabled) return@Runnable
            Bukkit.getScheduler().runTask(urb.plugin, Runnable {
                hideProgress(run.playerId)
                if (result == null) return@Runnable
                lastResults[run.playerId] = result
                then(result)
            })
        })
        return true
    }

    private fun simulate(
        run: Run,
        boxName: String,
        displayName: String,
        rewards: List<Reward>,
        resolvable: List<Boolean>,
        tiers: Map<String, BoxTier>,
        minRolls: Int,
        maxRolls: Int,
        seed: Long,
    ): SimulationResult {
        val started = System.currentTimeMillis()
        val rng = Random(seed)

        val indexOf = HashMap<String, Int>(rewards.size * 2)
        rewards.forEachIndexed { index, reward -> indexOf[reward.id] = index }

        val hits = LongArray(rewards.size)
        val amounts = LongArray(rewards.size)
        val distribution = LongArray(rewards.size + 1)

        var truncated = 0L
        var floored = 0L
        var announceOpens = 0L
        var announceEvents = 0L
        var commandRuns = 0L

        // The same grouping the real open path builds, rebuilt from the snapshot.
        val groups = buildRollGroups(rewards, tiers, minRolls, maxRolls)
        val ceilings = groups.map { it.maxRolls.coerceIn(1, it.entries.size) }
        val floors = groups.mapIndexed { index, group -> group.minRolls.coerceIn(0, ceilings[index]) }
        val effectiveMax = ceilings.sum()

        var completed = 0L
        while (completed < run.total && !run.cancelled) {
            var size = 0
            var truncatedHere = false
            var flooredHere = false
            var announcedHere = false

            for ((groupIndex, group) in groups.withIndex()) {
                val selection =
                    LootRoller.selectDetailed(group.entries, group.minRolls, group.maxRolls, rng)

                if (selection.rawWinners > ceilings[groupIndex]) truncatedHere = true
                if (selection.rawWinners < floors[groupIndex]) flooredHere = true
                size += selection.selected.size

                for (reward in selection.selected) {
                    val index = indexOf[reward.id] ?: continue
                    hits[index]++

                    // Called for every selected reward even when the item is not given, so the
                    // random sequence matches BoxOpenService.deliver exactly.
                    val amount = LootRoller.rollAmount(reward.minAmount, reward.maxAmount, rng)
                    if (reward.giveItem) amounts[index] += amount

                    commandRuns += reward.commands.size
                    if (reward.announce) {
                        announceEvents++
                        announcedHere = true
                    }
                }
            }

            if (truncatedHere) truncated++
            if (flooredHere) floored++
            if (announcedHere) announceOpens++
            if (size < distribution.size) distribution[size]++

            completed++
            // Cheap progress publication; the ticker reads this on the main thread.
            if (completed and PROGRESS_MASK == 0L) run.done.set(completed)
        }
        run.done.set(completed)

        return SimulationResult(
            boxName = boxName,
            displayName = displayName,
            iterations = completed,
            seed = seed,
            minRolls = minRolls,
            maxRolls = maxRolls,
            countDistribution = distribution,
            rewards = rewards.mapIndexed { index, reward ->
                RewardStat(reward, hits[index], amounts[index], resolvable.getOrElse(index) { true })
            },
            truncatedOpens = truncated,
            flooredOpens = floored,
            announceOpens = announceOpens,
            announceEvents = announceEvents,
            commandRuns = commandRuns,
            elapsedMillis = System.currentTimeMillis() - started,
            cancelled = run.cancelled,
            pools = groups.mapIndexed { index, group ->
                val label = group.tier ?: TierForecast.DEFAULT_LABEL
                "$label ${floors[index]}~${ceilings[index]}개 (${group.entries.size}종)"
            },
            effectiveMax = effectiveMax,
        )
    }

    /**
     * Progress readout, driven from the single repeating ticker rather than its own task.
     *
     * Rendered as a boss bar rather than an action bar: HUD plugins redraw the action bar every
     * tick and would erase this, and a boss bar carries the fraction natively.
     */
    fun tickProgress() {
        val run = active ?: return
        val player = Bukkit.getPlayer(run.playerId) ?: return
        val done = run.done.get()
        val fraction = if (run.total <= 0) 0.0 else done.toDouble() / run.total
        val percent = (fraction * 100).toInt()
        urb.bossBars.show(
            player,
            BossBars.SIMULATION,
            urb.messages.component(
                "simulate-progress",
                Ph.of().box(run.boxName).count(percent).amount(done.toInt().coerceAtLeast(0)),
                player,
            ),
            fraction.toFloat(),
            net.kyori.adventure.bossbar.BossBar.Color.YELLOW,
        )
    }

    private fun hideProgress(playerId: UUID) {
        urb.bossBars.hide(playerId, BossBars.SIMULATION)
    }

    companion object {
        /** Publish progress every 65,536 iterations - often enough to look live, cheap enough to ignore. */
        private const val PROGRESS_MASK = 0xFFFFL
    }
}
