package com.inmc.urb.box

import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.Numbers
import org.bukkit.configuration.ConfigurationSection
import java.util.UUID

/**
 * One entry in a box's loot table.
 *
 * A reward can hand out an item, run commands, or both (spec §83 - "아이템 대신 명령어가
 * 작동하는 방식"). Command-only rewards keep their [item] as the icon shown in the admin GUI
 * and the `/urb info` chance table, but set [giveItem] to false.
 */
class Reward(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    var item: StoredItem,
    chance: Double = 50.0,
    minAmount: Int = 1,
    maxAmount: Int = 1,
    /** Broadcast + Discord notice when this reward is rolled (spec §101). */
    var announce: Boolean = false,
    var commands: MutableList<String> = mutableListOf(),
    var giveItem: Boolean = true,
    /** Name of the pool this reward rolls in; null uses the box-level range. */
    var tier: String? = null,
) : Weighted {

    override var chance: Double = Numbers.clampChance(chance)
        set(value) {
            field = Numbers.clampChance(value)
        }

    var minAmount: Int = minAmount.coerceAtLeast(1)
        set(value) {
            field = value.coerceIn(1, 64)
            if (field > maxAmount) maxAmount = field
        }

    var maxAmount: Int = maxAmount.coerceAtLeast(minAmount)
        set(value) {
            field = value.coerceIn(1, 64)
            if (field < minAmount) minAmount = field
        }

    fun label(): String = item.label()

    fun copyOf(): Reward = Reward(
        id = id,
        item = item,
        chance = chance,
        minAmount = minAmount,
        maxAmount = maxAmount,
        announce = announce,
        commands = commands.toMutableList(),
        giveItem = giveItem,
        tier = tier,
    )

    fun save(section: ConfigurationSection) {
        section.set("id", id)
        item.save(section)
        section.set("chance", chance)
        section.set("min-amount", minAmount)
        section.set("max-amount", maxAmount)
        if (announce) section.set("announce", true)
        if (!giveItem) section.set("give-item", false)
        if (commands.isNotEmpty()) section.set("commands", commands)
        section.set("tier", tier)
    }

    companion object {
        fun load(section: ConfigurationSection, defaultChance: Double): Reward? {
            val item = StoredItem.load(section) ?: return null
            return Reward(
                id = section.getString("id") ?: UUID.randomUUID().toString().substring(0, 8),
                item = item,
                chance = section.getDouble("chance", defaultChance),
                minAmount = section.getInt("min-amount", 1),
                maxAmount = section.getInt("max-amount", section.getInt("min-amount", 1)),
                announce = section.getBoolean("announce", false),
                commands = section.getStringList("commands").toMutableList(),
                giveItem = section.getBoolean("give-item", true),
                tier = section.getString("tier")?.takeIf { it.isNotBlank() },
            )
        }
    }
}
