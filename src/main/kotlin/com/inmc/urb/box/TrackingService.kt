package com.inmc.urb.box

import com.inmc.urb.Urb
import com.inmc.urb.util.Ph
import com.inmc.urb.visual.BossBars
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * `/urb search` and `/urb track` (spec §103).
 *
 * Searching is rate limited by `search.cooldown` so a supply drop is something players have
 * to travel to rather than re-query every second. Once a player picks a target their compass
 * points at it and an action bar shows the live distance.
 */
class TrackingService(private val urb: Urb) {

    private class Target(
        val boxName: String,
        val displayName: String,
        val world: String,
        val x: Int,
        val z: Int,
        /** Known block Y for a box already placed; null while it is still queued. */
        val y: Int?,
    ) {
        /**
         * Distance at the moment the journey could first be measured; 0 until then.
         *
         * Not necessarily the moment tracking started. A player who clicks the broadcast's
         * track button from another world has no measurable distance yet, and freezing 0 in
         * would leave the progress bar empty for the whole trip - the span would collapse to
         * the arrival radius. So it is captured lazily, the first tick the worlds match.
         */
        var startDistance: Int = 0

        /** The compass only means anything in the box's own world, so it waits for that too. */
        var compassSet: Boolean = false
    }

    private val targets = ConcurrentHashMap<UUID, Target>()
    private val searchedAt = ConcurrentHashMap<UUID, Long>()

    /** Remaining cooldown in seconds, or null when the player may search now. */
    fun searchCooldown(player: Player): Long? {
        val cooldown = urb.config.searchCooldownSeconds
        if (cooldown <= 0L) return null
        if (player.hasPermission(com.inmc.urb.command.UrbCommand.PERMISSION)) return null
        val last = searchedAt[player.uniqueId] ?: return null
        val elapsed = (System.currentTimeMillis() - last) / 1000L
        return if (elapsed >= cooldown) null else (cooldown - elapsed).coerceAtLeast(1L)
    }

    fun markSearched(player: Player) {
        searchedAt[player.uniqueId] = System.currentTimeMillis()
    }

    fun track(player: Player, sighting: BoxSighting) {
        val box = urb.boxes.get(sighting.boxName)
        if (box != null && !box.trackable) {
            urb.messages.send(player, "track-untrackable")
            return
        }
        val target = Target(
            boxName = sighting.boxName,
            displayName = sighting.displayName,
            world = sighting.world,
            x = sighting.x,
            z = sighting.z,
            y = sighting.y,
        )
        targets[player.uniqueId] = target
        aimCompass(player, target)

        urb.messages.send(
            player,
            if (player.world.name == sighting.world) "track-set" else "track-set-other-world",
            Ph.of().box(sighting.displayName).raw(Ph.WORLD, sighting.world),
        )
    }

    /**
     * Points the compass at the target, but only once the player is in its world.
     *
     * A cross-world compass target is meaningless - the client just reads the raw coordinates -
     * so a player who starts tracking from the nether would watch their needle swing at nothing
     * until they came back. Nothing is sent until the worlds agree.
     */
    private fun aimCompass(player: Player, target: Target) {
        if (target.compassSet || player.world.name != target.world) return
        val world = Bukkit.getWorld(target.world) ?: return
        val y = aimY(world, target, player)
        player.compassTarget = Location(world, target.x.toDouble(), y.toDouble(), target.z.toDouble())
        target.compassSet = true
    }

    /**
     * Height to aim at, **without ever forcing a chunk to load.**
     *
     * `World.getHighestBlockYAt` generates or loads the chunk it is asked about. Calling it on a
     * target thousands of blocks away - which the tick loop did once a second, per tracking
     * player - drags that chunk into memory just to place a particle nobody can see from there.
     * The whole spawn pipeline goes out of its way to avoid that, so this must too.
     *
     * A placed box already knows its own Y. Otherwise the surface is only consulted once the
     * chunk happens to be loaded, and until then the player's own height is used - which is
     * exactly right for a trail drawn beside the player anyway.
     */
    private fun aimY(world: org.bukkit.World, target: Target, player: Player): Int {
        target.y?.let { return it + 1 }
        if (!world.isChunkLoaded(target.x shr 4, target.z shr 4)) return player.location.blockY
        return world.getHighestBlockYAt(target.x, target.z) + 1
    }

    fun clear(player: Player) {
        if (targets.remove(player.uniqueId) != null) {
            urb.bossBars.hide(player, BossBars.TRACK)
            urb.messages.send(player, "track-cleared")
        }
    }

    /**
     * 그 자리의 상자가 사라졌다(누가 열었다 · 시간이 다 됐다 · 지웠다) — 그 상자를 쫓던 사람의 추적을 멈춘다(테섭 2026-10-02).
     * 연 사람 자신은 조용히 멈추고, 다른 사람에게는 [opener] 가 있으면 "누가 열었다", 없으면 "사라졌다".
     */
    fun boxGone(world: String, x: Int, z: Int, opener: Player?) {
        if (targets.isEmpty()) return
        for ((playerId, target) in targets.entries.toList()) {
            if (target.world != world || target.x != x || target.z != z) continue
            targets.remove(playerId)
            urb.bossBars.hide(playerId, BossBars.TRACK)
            if (playerId == opener?.uniqueId) continue
            val player = Bukkit.getPlayer(playerId) ?: continue
            if (opener != null) urb.messages.send(player, "track-opened", Ph.of().box(target.displayName).player(opener))
            else urb.messages.send(player, "track-gone")
        }
    }

    /** Called on quit so a stale bar cannot follow the player into their next session. */
    fun forget(playerId: UUID) {
        targets.remove(playerId)
    }

    fun isTracking(playerId: UUID): Boolean = targets.containsKey(playerId)

    /**
     * Click-to-track straight from a spawn broadcast.
     *
     * The broadcast carries the coordinates, so this looks the sighting back up rather than
     * trusting them: a box that already despawned must say so instead of sending the player on
     * a walk to an empty field.
     */
    fun trackAt(player: Player, world: String, x: Int, z: Int) {
        val sighting = urb.spawns.sightings(includePending = true, from = null)
            .firstOrNull { it.world == world && it.x == x && it.z == z }
        if (sighting == null) {
            urb.messages.send(player, "track-gone")
            return
        }
        track(player, sighting)
    }

    /** Ticker hook: refreshes the boss bar for every tracking player. */
    fun tick() {
        if (targets.isEmpty()) return
        for ((playerId, target) in targets.entries.toList()) {
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !player.isOnline) {
                targets.remove(playerId)
                urb.bossBars.hide(playerId, BossBars.TRACK)
                continue
            }
            if (player.world.name != target.world) {
                // Wrong world: keep the target, but stop claiming a distance we cannot measure.
                urb.bossBars.show(
                    player, BossBars.TRACK,
                    urb.messages.component(
                        "track-bossbar-away",
                        Ph.of().box(target.displayName).raw(Ph.WORLD, target.world),
                        player,
                    ),
                    0f, BossBar.Color.WHITE,
                )
                continue
            }

            // Same world at last: this is where the journey is measured and the needle aimed.
            aimCompass(player, target)

            val dx = player.location.blockX - target.x
            val dz = player.location.blockZ - target.z
            val distance = Math.sqrt((dx.toDouble() * dx) + (dz.toDouble() * dz)).roundToInt()
            if (target.startDistance <= 0) target.startDistance = distance

            if (distance <= ARRIVAL_RADIUS) {
                targets.remove(playerId)
                urb.bossBars.hide(player, BossBars.TRACK)
                urb.messages.send(player, "track-arrived")
                continue
            }

            // Progress is "how much of the journey is behind you", so the bar fills as the
            // player closes in. A target that starts adjacent has nothing to fill, hence the
            // floor on the denominator.
            val span = target.startDistance.coerceAtLeast(ARRIVAL_RADIUS + 1)
            val progress = ((span - distance).toFloat() / span).coerceIn(0f, 1f)
            urb.bossBars.show(
                player, BossBars.TRACK,
                urb.messages.component(
                    "track-bossbar",
                    Ph.of().box(target.displayName)
                        .distance(distance)
                        .location(target.world, target.x, null, target.z),
                    player,
                ),
                progress,
                when {
                    distance <= 64 -> BossBar.Color.GREEN
                    distance <= 256 -> BossBar.Color.YELLOW
                    else -> BossBar.Color.BLUE
                },
            )

            // Breadcrumb particles, shown only to the tracking player.
            Bukkit.getWorld(target.world)?.let { world ->
                val destination = org.bukkit.Location(
                    world, target.x.toDouble(), aimY(world, target, player).toDouble(), target.z.toDouble(),
                )
                urb.visuals.drawTrackingTrail(player, destination)
            }
        }
    }

    fun purge(now: Long) {
        val cooldown = urb.config.searchCooldownSeconds * 1000L
        if (cooldown <= 0L) {
            searchedAt.clear()
            return
        }
        searchedAt.entries.removeIf { now - it.value > cooldown }
    }

    companion object {
        private const val ARRIVAL_RADIUS = 8
    }
}
