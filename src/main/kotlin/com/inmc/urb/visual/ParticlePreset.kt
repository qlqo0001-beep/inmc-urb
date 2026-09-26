package com.inmc.urb.visual

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World

/**
 * Ready-made particle looks for a spawned box.
 *
 * Each preset emits one complete shape per call, and the ticker calls it once a second. That
 * keeps the plugin inside its "one repeating task" budget, so every preset is built from
 * particles that live long enough to read as continuous rather than as a once-a-second blink.
 */
enum class ParticlePreset(val label: String, val icon: Material) {

    NONE("사용 안 함", Material.GRAY_DYE) {
        override fun emit(world: World, center: Location) = Unit
    },

    SPARKLE("반짝임", Material.GLOWSTONE_DUST) {
        override fun emit(world: World, center: Location) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(0.0, 0.8, 0.0), 8, 0.35, 0.3, 0.35, 0.0)
        }
    },

    FLAME_RING("불꽃 고리", Material.BLAZE_POWDER) {
        override fun emit(world: World, center: Location) {
            ring(world, center, Particle.FLAME, radius = 0.65, y = 0.25, points = 12)
        }
    },

    SOUL_RISE("영혼 상승", Material.SOUL_SAND) {
        override fun emit(world: World, center: Location) {
            world.spawnParticle(Particle.SOUL, center.clone().add(0.0, 0.4, 0.0), 6, 0.25, 0.1, 0.25, 0.04)
        }
    },

    ENCHANT_SWIRL("마법 소용돌이", Material.ENCHANTING_TABLE) {
        override fun emit(world: World, center: Location) {
            world.spawnParticle(Particle.ENCHANT, center.clone().add(0.0, 1.4, 0.0), 24, 0.4, 0.5, 0.4, 0.6)
        }
    },

    PORTAL_VORTEX("차원문 소용돌이", Material.ENDER_PEARL) {
        override fun emit(world: World, center: Location) {
            world.spawnParticle(Particle.PORTAL, center.clone().add(0.0, 0.9, 0.0), 28, 0.4, 0.5, 0.4, 0.7)
        }
    },

    END_ROD_BEAM("빛기둥", Material.END_ROD) {
        override fun emit(world: World, center: Location) {
            for (step in 0..6) {
                world.spawnParticle(
                    Particle.END_ROD,
                    center.clone().add(0.0, 0.3 + step * 0.35, 0.0),
                    1, 0.03, 0.0, 0.03, 0.0,
                )
            }
        }
    },

    HEART_FLOAT("하트", Material.POPPY) {
        override fun emit(world: World, center: Location) {
            world.spawnParticle(Particle.HEART, center.clone().add(0.0, 1.1, 0.0), 3, 0.3, 0.2, 0.3, 0.0)
        }
    },

    NOTE_POP("음표", Material.NOTE_BLOCK) {
        override fun emit(world: World, center: Location) {
            repeat(4) {
                // `extra` picks the note colour, 0..1 across the scale.
                world.spawnParticle(
                    Particle.NOTE,
                    center.clone().add(0.0, 1.0, 0.0),
                    0, Math.random(), 0.0, 0.0, 1.0,
                )
            }
        }
    },

    CLOUD_HALO("구름 고리", Material.WHITE_WOOL) {
        override fun emit(world: World, center: Location) {
            ring(world, center, Particle.CLOUD, radius = 0.75, y = 1.3, points = 10)
        }
    },

    GOLD_DUST("황금 가루", Material.GOLD_INGOT) {
        override fun emit(world: World, center: Location) {
            val options = Particle.DustOptions(Color.fromRGB(255, 196, 55), 1.1f)
            world.spawnParticle(
                Particle.DUST, center.clone().add(0.0, 0.9, 0.0),
                14, 0.35, 0.4, 0.35, 0.0, options,
            )
        }
    },

    // --- the ones below are shapes rather than clouds ------------------------

    SPIRAL("나선", Material.NAUTILUS_SHELL) {
        override fun emit(world: World, center: Location) {
            // Two opposing helices, a staple of crate plugins' idle effect.
            for (step in 0 until 24) {
                val t = step / 24.0
                val angle = t * 4.0 * Math.PI
                val y = 0.1 + t * 1.8
                world.spawnParticle(
                    Particle.END_ROD,
                    center.clone().add(Math.cos(angle) * 0.55, y, Math.sin(angle) * 0.55),
                    1, 0.0, 0.0, 0.0, 0.0,
                )
                world.spawnParticle(
                    Particle.WAX_ON,
                    center.clone().add(-Math.cos(angle) * 0.55, y, -Math.sin(angle) * 0.55),
                    1, 0.0, 0.0, 0.0, 0.0,
                )
            }
        }
    },

    FOUNTAIN("분수", Material.HEART_OF_THE_SEA) {
        override fun emit(world: World, center: Location) {
            world.spawnParticle(
                Particle.GLOW, center.clone().add(0.0, 0.4, 0.0),
                18, 0.08, 0.05, 0.08, 0.45,
            )
        }
    },

    CRIT_BURST("치명타", Material.IRON_SWORD) {
        override fun emit(world: World, center: Location) {
            world.spawnParticle(Particle.CRIT, center.clone().add(0.0, 0.9, 0.0), 20, 0.4, 0.4, 0.4, 0.35)
        }
    },

    SPARK_RING("전기 고리", Material.LIGHTNING_ROD) {
        override fun emit(world: World, center: Location) {
            ring(world, center, Particle.ELECTRIC_SPARK, radius = 0.8, y = 0.6, points = 16)
            ring(world, center, Particle.ELECTRIC_SPARK, radius = 0.45, y = 1.3, points = 10)
        }
    },

    RAINBOW("무지개", Material.PINK_PETALS) {
        override fun emit(world: World, center: Location) {
            // Hue sweeps with height, so the column reads as a gradient.
            for (step in 0 until 12) {
                val hue = step / 12.0f
                val rgb = java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f)
                val options = Particle.DustOptions(
                    Color.fromRGB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF),
                    1.0f,
                )
                val angle = step / 12.0 * 2.0 * Math.PI
                world.spawnParticle(
                    Particle.DUST,
                    center.clone().add(Math.cos(angle) * 0.6, 0.3 + step * 0.12, Math.sin(angle) * 0.6),
                    1, 0.0, 0.0, 0.0, 0.0, options,
                )
            }
        }
    };

    /** Draws the preset once, centred on the block the box occupies. */
    abstract fun emit(world: World, center: Location)

    fun next(): ParticlePreset = entries[(ordinal + 1) % entries.size]

    fun previous(): ParticlePreset = entries[(ordinal - 1 + entries.size) % entries.size]

    protected fun ring(world: World, center: Location, particle: Particle, radius: Double, y: Double, points: Int) {
        for (i in 0 until points) {
            val angle = 2.0 * Math.PI * i / points
            world.spawnParticle(
                particle,
                center.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius),
                1, 0.0, 0.0, 0.0, 0.0,
            )
        }
    }

    companion object {
        fun parse(raw: String?): ParticlePreset =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}
