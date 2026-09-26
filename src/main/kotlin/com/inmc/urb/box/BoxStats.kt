package com.inmc.urb.box

import com.inmc.urb.Urb
import kr.inmc.core.CorePlugin
import kr.inmc.core.store.Profile
import kr.inmc.core.store.YamlFileStore
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production statistics: what a box has actually handed out, to whom, and how often.
 *
 * The simulator answers "what *should* happen" from the configured chances. This answers "what
 * *did* happen" from live play, and the two are meant to be read together - a reward whose
 * observed rate sits far from its configured chance is either mis-tuned or being clamped by the
 * max-rolls ceiling, and only real numbers reveal which.
 *
 * Three things are tracked and all three are persisted, because a statistic that resets on
 * restart is not a statistic:
 *
 *  - per box: total opens, plus hits and total amount per reward
 *  - per player: total opens, which is what the ranking screen sorts on
 *  - a bounded log of recent opens, so an admin can see the last N in order
 */
class BoxStats(private val urb: Urb) :
    YamlFileStore(urb.io, listOf("data", "stats.yml"), what = "통계") {

    class RewardCount {
        var hits: Long = 0
        var amount: Long = 0
    }

    class BoxRecord {
        var opens: Long = 0
        var lastOpenAt: Long = 0
        val rewards: MutableMap<String, RewardCount> = LinkedHashMap()
    }

    class PlayerRecord(val id: UUID) {

        /**
         * 옛 `stats.yml` 에 남아 있던 이름. **읽기 폴백 전용이며 더 이상 기록하지 않는다.**
         *
         * core 의 `profile` 이 유일한 출처다. 다만 core 가 나간 뒤 아직 한 번도 재접속하지
         * 않은 사람은 `profile` 에 없어서, 그 사람들의 랭킹에 UUID 가 뜨지 않도록 파일에서
         * 읽은 값을 남겨둔다. 전원이 한 번씩 접속하고 나면 이 필드는 의미가 없어진다.
         */
        var storedName: String = "?"

        var opens: Long = 0
        var lastOpenAt: Long = 0

        /**
         * 화면에 나가는 이름. 4세대에는 이 플러그인과 숫자야구가 uuid→이름 캐시를 각자
         * 들고 있었고, 이제 core 의 `profile` 한 곳이 출처다.
         */
        val name: String
            get() = Profile.nameOf(CorePlugin.get().players, id) ?: storedName
    }

    data class LogEntry(
        val at: Long,
        val player: String,
        val boxName: String,
        val boxDisplay: String,
        val items: List<String>,
        val world: String?,
        val x: Int?,
        val z: Int?,
    )

    private val boxes = ConcurrentHashMap<String, BoxRecord>()
    private val players = ConcurrentHashMap<UUID, PlayerRecord>()
    private val log = ArrayDeque<LogEntry>()


    // --- recording -------------------------------------------------------------

    /**
     * One completed open. [rolled] pairs each selected reward with the amount actually rolled,
     * so the "average per open" figures reflect the amount range and not just the hit count.
     */
    fun record(player: Player, box: RandomBox, rolled: List<Pair<Reward, Int>>, at: Location?) {
        val now = System.currentTimeMillis()

        val record = boxes.computeIfAbsent(box.name) { BoxRecord() }
        synchronized(record) {
            record.opens++
            record.lastOpenAt = now
            for ((reward, amount) in rolled) {
                val counter = record.rewards.getOrPut(reward.id) { RewardCount() }
                counter.hits++
                if (reward.giveItem) counter.amount += amount
            }
        }

        val profile = players.computeIfAbsent(player.uniqueId) { PlayerRecord(it) }
        synchronized(profile) {
            // 이름은 core 의 profile 이 갖는다. 여기서 또 적지 않는다 --
            // storedName 은 core 가 아직 못 본 플레이어를 위한 읽기 폴백으로만 남는다.
            profile.opens++
            profile.lastOpenAt = now
        }

        val entry = LogEntry(
            at = now,
            player = player.name,
            boxName = box.name,
            boxDisplay = box.displayName,
            items = rolled.map { (reward, amount) ->
                if (amount > 1) "${reward.label()} x$amount" else reward.label()
            },
            world = at?.world?.name,
            x = at?.blockX,
            z = at?.blockZ,
        )
        synchronized(log) {
            log.addFirst(entry)
            while (log.size > urb.config.statsLogSize) log.removeLast()
        }

        markDirty()
    }

    // --- reads -----------------------------------------------------------------

    fun opens(boxName: String): Long = boxes[boxName]?.opens ?: 0L

    fun lastOpenAt(boxName: String): Long = boxes[boxName]?.lastOpenAt ?: 0L

    fun totalOpens(): Long = boxes.values.sumOf { it.opens }

    fun hits(boxName: String, rewardId: String): Long =
        boxes[boxName]?.rewards?.get(rewardId)?.hits ?: 0L

    fun amount(boxName: String, rewardId: String): Long =
        boxes[boxName]?.rewards?.get(rewardId)?.amount ?: 0L

    /** Observed appearance rate as a percentage, or null when the box has never been opened. */
    fun observedRate(boxName: String, rewardId: String): Double? {
        val record = boxes[boxName] ?: return null
        if (record.opens <= 0L) return null
        return (record.rewards[rewardId]?.hits ?: 0L) * 100.0 / record.opens
    }

    fun playerOpens(id: UUID): Long = players[id]?.opens ?: 0L

    /** Highest opener first. */
    fun ranking(limit: Int): List<PlayerRecord> =
        players.values.sortedWith(compareByDescending<PlayerRecord> { it.opens }.thenBy { it.lastOpenAt })
            .take(limit.coerceAtLeast(0))

    /** 1-based position in the ranking, or null when the player has never opened a box. */
    fun rankOf(id: UUID): Int? {
        val mine = players[id]?.opens ?: return null
        if (mine <= 0L) return null
        return players.values.count { it.opens > mine } + 1
    }

    fun recentLog(limit: Int = Int.MAX_VALUE): List<LogEntry> = synchronized(log) { log.take(limit) }

    fun trackedPlayers(): Int = players.size

    // --- admin -----------------------------------------------------------------

    /** Wipes one box's counters, or everything when [boxName] is null. Returns opens discarded. */
    fun reset(boxName: String?): Long {
        val discarded: Long
        if (boxName == null) {
            discarded = boxes.values.sumOf { it.opens }
            boxes.clear()
            players.clear()
            synchronized(log) { log.clear() }
        } else {
            discarded = boxes.remove(boxName)?.opens ?: 0L
            synchronized(log) { log.removeIf { it.boxName == boxName } }
        }
        markDirty()
        return discarded
    }

    // --- persistence -----------------------------------------------------------

    override fun read(config: YamlConfiguration) {
        boxes.clear()
        players.clear()

        config.getConfigurationSection("boxes")?.let { section ->
            for (name in section.getKeys(false)) {
                val entry = section.getConfigurationSection(name) ?: continue
                val record = BoxRecord()
                record.opens = entry.getLong("opens", 0L)
                record.lastOpenAt = entry.getLong("last", 0L)
                entry.getConfigurationSection("rewards")?.let { rewards ->
                    for (id in rewards.getKeys(false)) {
                        val counter = RewardCount()
                        counter.hits = rewards.getLong("$id.hits", 0L)
                        counter.amount = rewards.getLong("$id.amount", 0L)
                        record.rewards[id] = counter
                    }
                }
                boxes[name] = record
            }
        }

        config.getConfigurationSection("players")?.let { section ->
            for (raw in section.getKeys(false)) {
                val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: continue
                val profile = PlayerRecord(id)
                profile.storedName = section.getString("$raw.name") ?: "?"
                profile.opens = section.getLong("$raw.opens", 0L)
                profile.lastOpenAt = section.getLong("$raw.last", 0L)
                players[id] = profile
            }
        }

        synchronized(log) {
            log.clear()
            for (raw in config.getMapList("log")) {
                @Suppress("UNCHECKED_CAST")
                log.add(
                    LogEntry(
                        at = (raw["at"] as? Number)?.toLong() ?: 0L,
                        player = raw["player"] as? String ?: "?",
                        boxName = raw["box"] as? String ?: "?",
                        boxDisplay = raw["box-display"] as? String ?: raw["box"] as? String ?: "?",
                        items = (raw["items"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                        world = raw["world"] as? String,
                        x = (raw["x"] as? Number)?.toInt(),
                        z = (raw["z"] as? Number)?.toInt(),
                    )
                )
            }
        }

    }




    /** Runs on the calling (main) thread; only the file write is handed off. */
    override fun write(config: YamlConfiguration) {
        for ((name, record) in boxes) {
            synchronized(record) {
                config.set("boxes.$name.opens", record.opens)
                config.set("boxes.$name.last", record.lastOpenAt)
                for ((id, counter) in record.rewards) {
                    config.set("boxes.$name.rewards.$id.hits", counter.hits)
                    config.set("boxes.$name.rewards.$id.amount", counter.amount)
                }
            }
        }
        for ((id, profile) in players) {
            synchronized(profile) {
                config.set("players.$id.opens", profile.opens)
                config.set("players.$id.last", profile.lastOpenAt)
            }
        }
        config.set("log", recentLog().map { entry ->
            linkedMapOf(
                "at" to entry.at,
                "player" to entry.player,
                "box" to entry.boxName,
                "box-display" to entry.boxDisplay,
                "items" to entry.items,
                "world" to entry.world,
                "x" to entry.x,
                "z" to entry.z,
            )
        })
    }
}
