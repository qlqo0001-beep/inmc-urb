package com.inmc.urb.visual

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Named boss bars, one set per player.
 *
 * The plugin used to write its live readouts - tracking distance, simulation progress - to the
 * action bar. That surface is owned by whichever HUD plugin the server runs (BetterHud and
 * friends redraw it every tick), so our text was overwritten as fast as it was sent. A boss bar
 * is ours alone, stacks cleanly with other plugins' bars, and carries a progress fraction the
 * action bar could only fake with characters.
 *
 * Bars are addressed by a short string id so the same player can hold several at once, and are
 * removed on quit - a bar left behind is visible until relog.
 */
class BossBars {

    private val bars = ConcurrentHashMap<UUID, MutableMap<String, BossBar>>()

    /** Creates or updates one bar. Safe to call every tick; nothing is re-sent unchanged. */
    fun show(
        player: Player,
        id: String,
        name: Component,
        progress: Float,
        color: BossBar.Color = BossBar.Color.YELLOW,
        overlay: BossBar.Overlay = BossBar.Overlay.PROGRESS,
    ) {
        val owned = bars.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val clamped = progress.coerceIn(0f, 1f)
        val existing = owned[id]

        if (existing != null) {
            existing.name(name)
            existing.progress(clamped)
            if (existing.color() != color) existing.color(color)
            if (existing.overlay() != overlay) existing.overlay(overlay)
            return
        }

        val bar = BossBar.bossBar(name, clamped, color, overlay)
        owned[id] = bar
        player.showBossBar(bar)
    }

    fun hide(playerId: UUID, id: String) {
        val owned = bars[playerId] ?: return
        val bar = owned.remove(id) ?: return
        Bukkit.getPlayer(playerId)?.hideBossBar(bar)
        if (owned.isEmpty()) bars.remove(playerId)
    }

    fun hide(player: Player, id: String) {
        val owned = bars[player.uniqueId] ?: return
        val bar = owned.remove(id) ?: return
        player.hideBossBar(bar)
        if (owned.isEmpty()) bars.remove(player.uniqueId)
    }

    /** Called on quit: a bar the player still holds would survive into their next session. */
    fun hideAll(playerId: UUID) {
        val owned = bars.remove(playerId) ?: return
        val player = Bukkit.getPlayer(playerId) ?: return
        owned.values.forEach { player.hideBossBar(it) }
    }

    fun clear() {
        for (playerId in bars.keys.toList()) hideAll(playerId)
        bars.clear()
    }

    companion object {
        const val TRACK = "track"
        const val SIMULATION = "simulation"
        const val OPEN = "open"
    }
}
