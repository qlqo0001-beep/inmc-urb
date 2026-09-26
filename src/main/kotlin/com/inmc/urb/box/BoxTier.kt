package com.inmc.urb.box

import org.bukkit.configuration.ConfigurationSection

/**
 * A named subset of a box's rewards that rolls with its own count range.
 *
 * Without tiers every reward competes for the same slots, so "always two commons plus a shot
 * at one rare" cannot be expressed - raising the rare's chance just makes it crowd out the
 * commons. A tier draws from its own pool with its own min/max, and the results are merged.
 *
 * Rewards left untiered keep rolling against the box-level range, so a box that never defines
 * a tier behaves exactly as before.
 */
class BoxTier(
    val name: String,
    minRolls: Int = 1,
    maxRolls: Int = 1,
    var enabled: Boolean = true,
) {

    var minRolls: Int = minRolls.coerceIn(0, 54)
        set(value) {
            field = value.coerceIn(0, 54)
            if (field > maxRolls) maxRolls = field
        }

    var maxRolls: Int = maxRolls.coerceIn(0, 54)
        set(value) {
            field = value.coerceIn(0, 54)
            if (field < minRolls) minRolls = field
        }

    fun save(section: ConfigurationSection) {
        section.set("enabled", enabled)
        section.set("min-rolls", minRolls)
        section.set("max-rolls", maxRolls)
    }

    companion object {
        fun load(name: String, section: ConfigurationSection): BoxTier = BoxTier(
            name = name,
            minRolls = section.getInt("min-rolls", 1),
            maxRolls = section.getInt("max-rolls", 1),
            enabled = section.getBoolean("enabled", true),
        )
    }
}

/**
 * One pool of rewards rolled together. A box produces one group for its untiered rewards plus
 * one per enabled tier; the selections are independent and the results concatenated.
 */
class RollGroup(
    /** null for the box's default pool. */
    val tier: String?,
    val entries: List<Reward>,
    val minRolls: Int,
    val maxRolls: Int,
)

/**
 * Splits a reward list into the pools that will be rolled.
 *
 * Kept as a free function rather than a [RandomBox] method because the simulator works on a
 * *snapshot* of the rewards - it must build the same grouping from copies, not from the live box.
 *
 * A reward naming a tier that no longer exists falls back into the default pool rather than
 * disappearing: deleting a tier must never silently delete loot.
 */
fun buildRollGroups(
    rewards: List<Reward>,
    tiers: Map<String, BoxTier>,
    minRolls: Int,
    maxRolls: Int,
): List<RollGroup> {
    if (tiers.isEmpty()) {
        return if (rewards.isEmpty()) emptyList()
        else listOf(RollGroup(null, rewards, minRolls, maxRolls))
    }

    val groups = ArrayList<RollGroup>(tiers.size + 1)

    val untiered = rewards.filter { it.tier == null || it.tier !in tiers }
    if (untiered.isNotEmpty()) groups.add(RollGroup(null, untiered, minRolls, maxRolls))

    for ((name, tier) in tiers) {
        if (!tier.enabled || tier.maxRolls <= 0) continue
        val members = rewards.filter { it.tier == name }
        if (members.isNotEmpty()) groups.add(RollGroup(name, members, tier.minRolls, tier.maxRolls))
    }
    return groups
}
