package com.inmc.urb.visual

import com.inmc.urb.Urb
import com.inmc.urb.box.SpawnedBox
import com.inmc.urb.util.BlockKey
import kr.inmc.core.util.Durations
import com.inmc.urb.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Particles and holograms for boxes that are standing in a loaded chunk.
 *
 * Driven from the one repeating task, once a second. Holograms are spawned as
 * **non-persistent** [TextDisplay] entities: they are never written to the region files, so a
 * crash cannot leave orphans lying around, and this class simply re-creates any that went away
 * with their chunk.
 */
class BoxVisuals(private val urb: Urb) {

    /** Box position -> its hologram entity. */
    private val holograms = ConcurrentHashMap<BlockKey, UUID>()

    /**
     * One pass over every placed box: emit particles and keep the hologram in sync.
     * Boxes in unloaded chunks are skipped - nothing is force-loaded to draw an effect.
     */
    fun tick() {
        val now = System.currentTimeMillis()
        val alive = HashSet<BlockKey>()

        for (spawned in urb.spawns.allSpawned()) {
            val box = urb.boxes.get(spawned.boxName) ?: continue
            val world = Bukkit.getWorld(spawned.key.world) ?: continue
            if (!world.isChunkLoaded(spawned.key.x shr 4, spawned.key.z shr 4)) continue

            val center = spawned.key.toLocation(world).add(0.5, 0.0, 0.5)

            if (box.particlePreset != ParticlePreset.NONE && world.players.isNotEmpty()) {
                runCatching { box.particlePreset.emit(world, center) }
                    .onFailure {
                        urb.logger.warning(
                            "파티클 '${box.particlePreset.name}' 재생 실패 - ${box.name} 상자의 파티클을 끕니다: ${it.message}"
                        )
                        box.particlePreset = ParticlePreset.NONE
                        urb.boxes.markDirty(box)
                    }
            }

            if (box.hologramEnabled) {
                alive.add(spawned.key)
                updateHologram(spawned, box.displayName, center, now)
            }
        }

        // Drop holograms whose box is gone, disabled, or whose chunk unloaded.
        for (key in holograms.keys.toList()) {
            if (key in alive) continue
            removeHologram(key)
        }
    }

    private fun updateHologram(spawned: SpawnedBox, displayName: String, center: Location, now: Long) {
        val world = center.world ?: return
        val existing = holograms[spawned.key]?.let { Bukkit.getEntity(it) as? TextDisplay }

        val remaining = spawned.remainingSeconds(now)
        val text = buildString {
            append(displayName)
            if (remaining >= 0) {
                append("\n")
                append("<gray>").append(Durations.formatShort(remaining)).append(" 남음</gray>")
            }
        }
        val component = Text.renderFlat(text)

        if (existing != null && existing.isValid) {
            existing.text(component)
            return
        }

        val display = world.spawn(center.clone().add(0.0, 1.3, 0.0), TextDisplay::class.java) { entity ->
            entity.text(component)
            entity.billboard = Display.Billboard.CENTER
            entity.isDefaultBackground = false
            entity.isSeeThrough = false
            entity.isPersistent = false          // never saved to disk - no orphans after a crash
            entity.setGravity(false)
            entity.isSilent = true
            entity.viewRange = 0.6f
        }
        holograms[spawned.key] = display.uniqueId
    }

    private fun removeHologram(key: BlockKey) {
        val id = holograms.remove(key) ?: return
        (Bukkit.getEntity(id))?.remove()
    }

    /** Called when a box is removed so its hologram goes at the same moment the block does. */
    fun onBoxRemoved(key: BlockKey) {
        removeHologram(key)
    }

    fun removeAll() {
        holograms.keys.toList().forEach { removeHologram(it) }
    }

    /**
     * Breadcrumb particles pointing a tracking player at their target.
     *
     * Drawn close to the player rather than along the whole route: a trail spanning hundreds of
     * blocks would be both invisible and enormously expensive.
     */
    fun drawTrackingTrail(player: Player, target: Location) {
        if (player.world.name != target.world?.name) return

        val from = player.location.clone().add(0.0, 1.0, 0.0)
        val direction = target.clone().subtract(from).toVector()
        if (direction.lengthSquared() < 1.0) return
        direction.normalize()

        for (step in 1..TRAIL_STEPS) {
            val point = from.clone().add(direction.clone().multiply(step.toDouble() * TRAIL_SPACING))
            player.spawnParticle(Particle.END_ROD, point, 1, 0.0, 0.0, 0.0, 0.0)
        }

        // A marker at the destination itself once the player is close enough to see it.
        val distance = player.location.distance(target).roundToInt()
        if (distance <= BEACON_RANGE) {
            for (height in 0..6) {
                player.spawnParticle(
                    Particle.HAPPY_VILLAGER,
                    target.clone().add(0.5, 0.5 + height * 0.5, 0.5),
                    1, 0.05, 0.0, 0.05, 0.0,
                )
            }
        }
    }

    /**
     * The flourish that plays as the loot lands.
     *
     * Drawn at the box rather than the opener where a position is known, so bystanders see the
     * same show. Every variant is cosmetic only - the lightning is [World.strikeLightningEffect]
     * and the firework is detonated in place, so neither can hurt anyone or set anything alight.
     */
    fun playFlair(player: Player, flair: RewardFlair, at: Location?) {
        // A flourish is decoration. It sits between handing the loot over and running the
        // reward commands, so an exception here used to swallow the commands, the announcement
        // and the broadcast - which is exactly what a bad particle argument did on 26.2.
        // Nothing visual is ever allowed to decide whether a reward completes.
        runCatching { emitFlair(player, flair, at) }
            .onFailure { urb.logger.warning("획득 연출 재생 실패 (${flair.name}): ${it.message}") }
    }

    private fun emitFlair(player: Player, flair: RewardFlair, at: Location?) {
        val origin = (at?.clone()?.add(0.5, 0.5, 0.5) ?: player.location.clone()).let {
            if (it.world == null) player.location.clone() else it
        }
        val world = origin.world ?: return

        when (flair) {
            RewardFlair.NONE -> Unit

            RewardFlair.TOTEM -> {
                player.playEffect(org.bukkit.EntityEffect.TOTEM_RESURRECT)
                world.spawnParticle(
                    Particle.TOTEM_OF_UNDYING,
                    player.location.clone().add(0.0, 1.0, 0.0),
                    60, 0.4, 0.6, 0.4, 0.35,
                )
                player.playSound(player.location, org.bukkit.Sound.ITEM_TOTEM_USE, 0.7f, 1.0f)
            }

            RewardFlair.FIREWORK -> spawnFirework(origin)

            RewardFlair.LIGHTNING -> {
                // Effect-only: no damage, no fire, no mob conversion.
                world.strikeLightningEffect(origin)
                world.spawnParticle(Particle.ELECTRIC_SPARK, origin.clone().add(0.0, 1.0, 0.0), 40, 0.5, 1.0, 0.5, 0.2)
            }

            RewardFlair.FANFARE -> {
                world.playSound(origin, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f)
                // A three-note arpeggio, spaced by one-shot tasks so no timer is added.
                FANFARE_PITCHES.forEachIndexed { index, pitch ->
                    Bukkit.getScheduler().runTaskLater(
                        urb.plugin,
                        Runnable { world.playSound(origin, org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, pitch) },
                        3L + index * 3L,
                    )
                }
                world.spawnParticle(Particle.NOTE, origin.clone().add(0.0, 1.2, 0.0), 12, 0.5, 0.4, 0.5, 1.0)
            }

            RewardFlair.BEAM -> {
                for (step in 0..24) {
                    world.spawnParticle(
                        Particle.END_ROD,
                        origin.clone().add(0.0, step * 0.5, 0.0),
                        2, 0.08, 0.0, 0.08, 0.0,
                    )
                }
                // Deliberately not Particle.FLASH: it requires a Color payload on this API and
                // the three-argument call throws "missing required data class org.bukkit.Color".
                // GLOW takes no data and reads the same at the base of the column.
                world.spawnParticle(Particle.GLOW, origin.clone().add(0.0, 1.0, 0.0), 20, 0.3, 0.5, 0.3, 0.05)
                world.playSound(origin, org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.6f)
            }
        }
    }

    /** Kept for the older call sites and tests; equivalent to [playFlair] with TOTEM. */
    fun playTotem(player: Player) = playFlair(player, RewardFlair.TOTEM, null)

    private fun spawnFirework(origin: Location) {
        val world = origin.world ?: return
        val firework = world.spawn(origin, org.bukkit.entity.Firework::class.java) { entity ->
            entity.isPersistent = false
            val meta = entity.fireworkMeta
            meta.addEffect(
                org.bukkit.FireworkEffect.builder()
                    .withColor(org.bukkit.Color.YELLOW, org.bukkit.Color.ORANGE, org.bukkit.Color.WHITE)
                    .withFade(org.bukkit.Color.AQUA)
                    .with(org.bukkit.FireworkEffect.Type.BURST)
                    .trail(true)
                    .flicker(true)
                    .build()
            )
            meta.power = 0
            entity.fireworkMeta = meta
        }
        // Detonating by hand skips the fuse and, unlike a natural explosion, harms nobody.
        firework.detonate()
    }

    /** Convenience for messages that want the box name inside a visual context. */
    fun placeholders(displayName: String): Ph = Ph.of().box(displayName)

    companion object {
        private const val TRAIL_STEPS = 12
        private const val TRAIL_SPACING = 1.2
        private const val BEACON_RANGE = 48
        private val FANFARE_PITCHES = floatArrayOf(1.0f, 1.26f, 1.5f, 2.0f)
    }
}
