package com.inmc.urb.util

import kr.inmc.core.util.Numbers
import kr.inmc.core.util.TokenBag
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * Placeholder bag for a single message render.
 *
 * The spec (§96) names its placeholders in Korean - `{좌표}`, `{플레이어네임}`, `{상자이름}`,
 * `{열쇠이름}`, `{필요한돈}` - so those are the canonical spellings. English aliases and the
 * `{name}` token the previous plugin used resolve to the same values so existing message
 * strings keep working.
 */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES



    fun player(name: String): Ph = put(PLAYER, name)

    fun player(player: Player): Ph = put(PLAYER, player.name)

    fun box(displayName: String): Ph = put(BOX, displayName)

    fun key(name: String): Ph = put(KEY, name)

    fun money(amount: String): Ph = put(MONEY, amount)

    fun item(name: String): Ph = put(ITEM, name)

    fun time(text: String): Ph = put(TIME, text)

    fun amount(value: Int): Ph = put(AMOUNT, value.toString())

    fun chance(value: Double): Ph = put(CHANCE, Numbers.chance(value))

    fun balance(amount: String): Ph = put(BALANCE, amount)

    fun distance(blocks: Int): Ph = put(DISTANCE, blocks.toString())

    fun count(value: Int): Ph = put(COUNT, value.toString())

    /** Fills world/x/y/z and the combined `{좌표}` token. */
    fun location(location: Location): Ph =
        location(location.world?.name ?: "?", location.blockX, location.blockY, location.blockZ)

    /** Y is unknown while a spawn is still queued for an unloaded chunk - pass null for it. */
    fun location(world: String, x: Int, y: Int?, z: Int): Ph = apply {
        put(WORLD, world)
        put(X, x.toString())
        put(Y, y?.toString() ?: "?")
        put(Z, z.toString())
        put(LOCATION, if (y == null) "$world $x, ?, $z" else "$world $x, $y, $z")
    }

    fun copy(): Ph = copyValuesInto(Ph())



    companion object {
        const val PLAYER = "player"
        const val BOX = "box"
        const val KEY = "key"
        const val MONEY = "money"
        const val ITEM = "item"
        const val LOCATION = "location"
        const val WORLD = "world"
        const val X = "x"
        const val Y = "y"
        const val Z = "z"
        const val TIME = "time"
        const val AMOUNT = "amount"
        const val CHANCE = "chance"
        const val BALANCE = "balance"
        const val DISTANCE = "distance"
        const val COUNT = "count"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어네임}", "{플레이어}", "{player}"),
            // {name} was the previous plugin's token for the box display name
            BOX to listOf("{상자이름}", "{box}", "{name}"),
            KEY to listOf("{열쇠이름}", "{열쇠}", "{key}"),
            MONEY to listOf("{필요한돈}", "{돈}", "{money}"),
            ITEM to listOf("{아이템}", "{item}"),
            LOCATION to listOf("{좌표}", "{location}"),
            WORLD to listOf("{world}", "{월드}"),
            X to listOf("{x}"),
            Y to listOf("{y}"),
            Z to listOf("{z}"),
            TIME to listOf("{time}", "{시간}"),
            AMOUNT to listOf("{amount}", "{수량}"),
            CHANCE to listOf("{chance}", "{확률}"),
            BALANCE to listOf("{돈보유}", "{balance}", "{소지금}"),
            DISTANCE to listOf("{거리}", "{distance}"),
            COUNT to listOf("{개수}", "{count}"),
        )

        fun of(): Ph = Ph()
    }
}
