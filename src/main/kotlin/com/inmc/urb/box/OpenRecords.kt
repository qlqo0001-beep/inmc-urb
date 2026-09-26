package com.inmc.urb.box

import com.inmc.urb.Urb
import kr.inmc.core.store.YamlFileStore
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player, per-box open history.
 *
 * Two limits ride on this, both for the "quietly hidden box you have to find" content: a
 * cooldown so one player cannot farm the same box type over and over, and a lifetime cap so a
 * permanent installation hands each player a fixed number of rewards and no more.
 *
 * Persisted, because a limit that resets on restart is not a limit.
 */
class OpenRecords(private val urb: Urb) :
    YamlFileStore(urb.io, listOf("data", "opens.yml"), what = "오픈 기록") {

    private data class Record(
        var lastOpenedAt: Long,
        var count: Int,
        /** When the current allowance period began; 0 for records written before periods existed. */
        var windowStart: Long = 0L,
    )

    private val records = ConcurrentHashMap<String, Record>()


    private fun key(playerId: UUID, boxName: String) = "$playerId|$boxName"

    /** Seconds left on this player's cooldown for the box, or null when they may open now. */
    fun cooldownRemaining(playerId: UUID, box: RandomBox): Long? {
        if (box.perPlayerCooldownSeconds <= 0L) return null
        val record = records[key(playerId, box.name)] ?: return null
        val elapsed = (System.currentTimeMillis() - record.lastOpenedAt) / 1000L
        if (elapsed >= box.perPlayerCooldownSeconds) return null
        return (box.perPlayerCooldownSeconds - elapsed).coerceAtLeast(1L)
    }

    fun openCount(playerId: UUID, boxName: String): Int =
        records[key(playerId, boxName)]?.count ?: 0

    /**
     * Opens the player has used in the period that is currently running.
     *
     * With no reset configured this is simply their lifetime total. With one, a record whose
     * window has already elapsed reads as zero - the allowance has refilled, and the record is
     * only rewritten when they actually open something again.
     */
    fun usedOpens(playerId: UUID, box: RandomBox): Int {
        val record = records[key(playerId, box.name)] ?: return 0
        if (isWindowExpired(record, box)) return 0
        return record.count
    }

    /** True when the player has used up their allowance for the current period. */
    fun isExhausted(playerId: UUID, box: RandomBox): Boolean {
        if (box.maxOpensPerPlayer <= 0) return false
        return usedOpens(playerId, box) >= box.maxOpensPerPlayer
    }

    fun remainingOpens(playerId: UUID, box: RandomBox): Int? {
        if (box.maxOpensPerPlayer <= 0) return null
        return (box.maxOpensPerPlayer - usedOpens(playerId, box)).coerceAtLeast(0)
    }

    /**
     * Seconds until this player's allowance refills, or null when it never does - either
     * because no reset is configured or because they have not started a period yet.
     */
    fun resetIn(playerId: UUID, box: RandomBox): Long? {
        if (box.openLimitResetSeconds <= 0L || box.maxOpensPerPlayer <= 0) return null
        val record = records[key(playerId, box.name)] ?: return null
        val start = record.periodStart()
        if (start <= 0L) return null
        val elapsed = (System.currentTimeMillis() - start) / 1000L
        if (elapsed >= box.openLimitResetSeconds) return null
        return (box.openLimitResetSeconds - elapsed).coerceAtLeast(1L)
    }

    private fun Record.periodStart(): Long = if (windowStart > 0L) windowStart else lastOpenedAt

    private fun isWindowExpired(record: Record, box: RandomBox): Boolean =
        isPeriodOver(record.periodStart(), box.openLimitResetSeconds, System.currentTimeMillis())

    /**
     * Records one open. Takes the box rather than just its name so the period can roll over
     * here - doing it lazily on read would let a stale count survive a config change.
     */
    fun record(playerId: UUID, box: RandomBox) {
        val now = System.currentTimeMillis()
        records.compute(key(playerId, box.name)) { _, existing ->
            when {
                existing == null -> Record(now, 1, now)
                isWindowExpired(existing, box) -> {
                    existing.lastOpenedAt = now
                    existing.windowStart = now
                    existing.count = 1
                    existing
                }

                else -> {
                    existing.lastOpenedAt = now
                    if (existing.windowStart <= 0L) existing.windowStart = now
                    existing.count += 1
                    existing
                }
            }
        }
        markDirty()
    }

    /** Admin escape hatch: wipe history for one box, or everything when [boxName] is null. */
    fun reset(boxName: String?): Int {
        val victims = if (boxName == null) records.keys.toList()
        else records.keys.filter { it.endsWith("|$boxName") }
        victims.forEach { records.remove(it) }
        if (victims.isNotEmpty()) markDirty()
        return victims.size
    }

    override fun read(config: YamlConfiguration) {
        records.clear()
        for (raw in config.getMapList("records")) {
            val id = raw["key"] as? String ?: continue
            val at = (raw["at"] as? Number)?.toLong() ?: 0L
            records[id] = Record(
                lastOpenedAt = at,
                count = (raw["count"] as? Number)?.toInt() ?: 0,
                // Files written before periods existed carry no start; the last open is
                // the closest honest answer and only matters once a reset is configured.
                windowStart = (raw["start"] as? Number)?.toLong() ?: at,
            )
        }
    }

    override fun write(config: YamlConfiguration) {
        config.set(
            "records",
            records.entries.map { (id, record) ->
                mapOf(
                    "key" to id,
                    "at" to record.lastOpenedAt,
                    "count" to record.count,
                    "start" to record.windowStart,
                )
            },
        )
    }

    companion object {
        /**
         * Whether an allowance period that opened at [start] has run out by [now].
         *
         * Pulled out as pure arithmetic so it can be tested without a server: getting it wrong
         * either locks a player out of a box forever or hands them unlimited opens, and neither
         * is visible until someone hits the cap in production.
         */
        fun isPeriodOver(start: Long, resetSeconds: Long, now: Long): Boolean {
            if (resetSeconds <= 0L) return false   // no reset configured - a lifetime cap
            if (start <= 0L) return false          // never opened, so no period is running
            return now - start >= resetSeconds * 1000L
        }
    }

}
