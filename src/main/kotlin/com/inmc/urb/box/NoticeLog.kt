package com.inmc.urb.box

import com.inmc.urb.Urb
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.util.ArrayDeque

/**
 * History of rare-reward announcements (spec §101).
 *
 * The spec asks for a Discord ping *and* an in-game notice window; this is the latter, and
 * it doubles as the record of what Discord was told. Kept as a bounded deque in memory and
 * mirrored to `data/notice.yml` so it survives a restart.
 */
class NoticeLog(private val urb: Urb) {

    data class Entry(
        val at: Long,
        val player: String,
        val boxName: String,
        val boxDisplay: String,
        val itemLabel: String,
        val item: StoredItem?,
    )

    private val entries = ArrayDeque<Entry>()

    @Volatile
    private var dirty = false

    val size: Int get() = synchronized(entries) { entries.size }

    fun record(playerName: String, box: RandomBox, reward: Reward) {
        val entry = Entry(
            at = System.currentTimeMillis(),
            player = playerName,
            boxName = box.name,
            boxDisplay = box.displayName,
            itemLabel = reward.label(),
            item = reward.item,
        )
        synchronized(entries) {
            entries.addFirst(entry)
            while (entries.size > urb.config.noticeHistorySize) entries.removeLast()
        }
        dirty = true
    }

    /** Newest first. */
    fun recent(limit: Int = Int.MAX_VALUE): List<Entry> =
        synchronized(entries) { entries.take(limit) }

    fun clear() {
        synchronized(entries) { entries.clear() }
        dirty = true
    }

    fun load(then: () -> Unit = {}) {
        urb.io.async({
            val file = urb.io.file("data", "notice.yml")
            if (file.exists()) urb.io.load(file) else YamlConfiguration()
        }) { config ->
            synchronized(entries) {
                entries.clear()
                for (raw in config.getMapList("entries")) {
                    val ref = ItemRef.parse(raw["item"] as? String)
                    val material = (raw["material"] as? String)?.let { Material.matchMaterial(it) }
                        ?: (ref as? ItemRef.Vanilla)?.material
                        ?: Material.CHEST
                    entries.add(
                        Entry(
                            at = (raw["at"] as? Number)?.toLong() ?: 0L,
                            player = raw["player"] as? String ?: "?",
                            boxName = raw["box"] as? String ?: "?",
                            boxDisplay = raw["box-display"] as? String ?: raw["box"] as? String ?: "?",
                            itemLabel = raw["label"] as? String ?: "?",
                            item = StoredItem(ref = ref, material = material),
                        )
                    )
                }
            }
            dirty = false
            then()
        }
    }

    fun flush() {
        if (!dirty) return
        dirty = false
        val snapshot = recent()
        val config = YamlConfiguration()
        config.set("entries", snapshot.map { entry ->
            mapOf(
                "at" to entry.at,
                "player" to entry.player,
                "box" to entry.boxName,
                "box-display" to entry.boxDisplay,
                "label" to entry.itemLabel,
                "item" to (entry.item?.ref?.serialize() ?: "snapshot"),
                "material" to (entry.item?.material?.name ?: Material.CHEST.name),
            )
        })
        val text = config.saveToString()
        urb.io.asyncRun {
            val file = urb.io.file("data", "notice.yml")
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        }
    }
}
