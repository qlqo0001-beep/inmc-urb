package com.inmc.urb

import org.bukkit.Particle
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Particles the plugin spawns with no data argument must not require one.
 *
 * `World.spawnParticle(particle, location, count)` throws
 * `IllegalArgumentException: missing required data class ...` when the particle needs a payload.
 * That fires at the moment the effect plays - deep inside the reward hand-over, on a live
 * server, for one specific setting - so it is invisible until a player picks that setting.
 * `Particle.FLASH` is exactly that case on this API: it wants an `org.bukkit.Color`.
 *
 * Keep this list in step with [com.inmc.urb.visual.ParticlePreset] and
 * [com.inmc.urb.visual.BoxVisuals]. Particles that *are* given data (DUST with DustOptions)
 * belong in [WITH_DATA] instead.
 */
class ParticleDataTest {

    @Test
    fun `every particle spawned without data takes no data`() {
        for (particle in NO_DATA) {
            assertEquals(
                Void::class.java,
                particle.dataType,
                "${particle.name} 은(는) 데이터 인자를 요구하므로 인자 없이 호출하면 예외가 납니다",
            )
        }
    }

    @Test
    fun `particles we hand data to really want that data`() {
        for ((particle, expected) in WITH_DATA) {
            assertEquals(
                expected,
                particle.dataType,
                "${particle.name} 에 넘기는 데이터 타입이 API 와 다릅니다",
            )
        }
    }

    companion object {
        private val NO_DATA = listOf(
            // ParticlePreset
            Particle.HAPPY_VILLAGER,
            Particle.FLAME,
            Particle.SOUL,
            Particle.ENCHANT,
            Particle.PORTAL,
            Particle.END_ROD,
            Particle.HEART,
            Particle.NOTE,
            Particle.CLOUD,
            Particle.WAX_ON,
            Particle.GLOW,
            Particle.CRIT,
            Particle.ELECTRIC_SPARK,
            // BoxVisuals - flair and the tracking trail
            Particle.TOTEM_OF_UNDYING,
        )

        private val WITH_DATA = listOf(
            Particle.DUST to Particle.DustOptions::class.java,
        )
    }
}
