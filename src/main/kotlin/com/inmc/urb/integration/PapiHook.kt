package com.inmc.urb.integration

import com.inmc.urb.Urb
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import kotlin.math.roundToInt

/**
 * PlaceholderAPI, both directions.
 *
 * Inbound: `%papi_...%` inside any of our configured messages gets resolved before
 * MiniMessage parsing. Outbound: an expansion exposing box counts and the nearest sighting so
 * scoreboards and holograms can show them.
 *
 * PlaceholderAPI classes are compile-only, so nothing here may be touched unless the plugin
 * is actually present - [setup] is the only guard.
 */
class PapiHook(private val urb: Urb) {

    private var expansion: UrbExpansion? = null

    val isEnabled: Boolean get() = expansion != null

    fun setup() {
        teardown()
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            urb.logger.info("PlaceholderAPI 미설치 - %papi_...% 는 그대로 출력됩니다")
            return
        }
        try {
            Text.papiResolver = { player, text -> PlaceholderAPI.setPlaceholders(player, text) }
            expansion = UrbExpansion(urb).also { it.register() }
            urb.logger.info("PlaceholderAPI 연동 활성화 (%urb_...%)")
        } catch (t: Throwable) {
            urb.logger.warning("PlaceholderAPI 연동 실패: ${t.message}")
            Text.papiResolver = null
            expansion = null
        }
    }

    fun teardown() {
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
        Text.papiResolver = null
    }
}

/** `%urb_...%` placeholders. Separate class so it is only loaded when PlaceholderAPI exists. */
private class UrbExpansion(private val urb: Urb) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "urb"

    override fun getAuthor(): String = "INMC"

    override fun getVersion(): String = urb.plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val online = player as? Player ?: (player?.uniqueId?.let { Bukkit.getPlayer(it) })

        return when {
            params.equals("spawned_total", true) -> urb.spawns.totalSpawned().toString()

            params.equals("pending_total", true) -> urb.spawns.totalPending().toString()

            params.equals("box_count", true) -> urb.boxes.size.toString()

            params.startsWith("spawned_", true) -> {
                val boxName = params.substring("spawned_".length)
                urb.spawns.liveCount(boxName).toString()
            }

            // --- statistics -----------------------------------------------------
            params.equals("opens_total", true) -> urb.stats.totalOpens().toString()

            params.startsWith("opens_", true) ->
                urb.stats.opens(resolveName(params.substring("opens_".length))).toString()

            params.equals("my_opens", true) ->
                (online?.let { urb.stats.playerOpens(it.uniqueId) } ?: 0L).toString()

            params.equals("my_rank", true) ->
                online?.let { urb.stats.rankOf(it.uniqueId)?.toString() } ?: "-"

            // Lifetime allowance left for *this* player on that box; "-" when uncapped.
            // 고정 좌표 상자는 위치마다 따로 세므로 합계를 보여준다.
            params.startsWith("remaining_", true) -> {
                val box = urb.boxes.get(resolveName(params.substring("remaining_".length)))
                if (box == null || online == null) "-"
                else urb.openRecords.remainingOpensTotal(online.uniqueId, box)?.toString() ?: "-"
            }

            params.startsWith("top_name_", true) ->
                topEntry(params.substring("top_name_".length))?.name ?: "-"

            params.startsWith("top_opens_", true) ->
                topEntry(params.substring("top_opens_".length))?.opens?.toString() ?: "0"

            // --- schedule -------------------------------------------------------
            params.startsWith("next_", true) -> {
                val box = urb.boxes.get(resolveName(params.substring("next_".length)))
                val remaining = box?.let { urb.spawns.secondsUntilAutoSpawn(it) }
                if (remaining == null) "-" else Durations.formatShort(remaining)
            }

            params.startsWith("schedule_", true) ->
                urb.boxes.get(resolveName(params.substring("schedule_".length)))?.schedule?.describe() ?: "-"

            params.equals("search_cooldown", true) -> {
                val remaining = online?.let { urb.tracking.searchCooldown(it) } ?: 0L
                Durations.formatShort(remaining)
            }

            params.equals("nearest", true) -> {
                val sighting = nearest(online) ?: return "-"
                "${sighting.displayName} (${sighting.world} ${sighting.x}, ${sighting.z})"
            }

            params.equals("nearest_box", true) -> nearest(online)?.displayName ?: "-"

            params.equals("nearest_distance", true) -> {
                val location = online?.location ?: return "-"
                val sighting = nearest(online) ?: return "-"
                if (sighting.world != location.world.name) return "-"
                val dx = location.blockX - sighting.x
                val dz = location.blockZ - sighting.z
                Math.sqrt((dx.toDouble() * dx) + (dz.toDouble() * dz)).roundToInt().toString()
            }

            else -> null
        }
    }

    private fun nearest(player: Player?): com.inmc.urb.box.BoxSighting? =
        urb.spawns.sightings(urb.config.searchShowPending, player?.location).firstOrNull()

    /**
     * PlaceholderAPI strips nothing, so a box called "보급 상자" arrives verbatim while a
     * scoreboard author is far more likely to have typed the underscore form. Both resolve.
     */
    private fun resolveName(raw: String): String =
        if (urb.boxes.exists(raw)) raw else raw.replace('_', ' ')

    private fun topEntry(raw: String): com.inmc.urb.box.BoxStats.PlayerRecord? {
        val rank = raw.toIntOrNull() ?: return null
        if (rank < 1) return null
        return urb.stats.ranking(rank).getOrNull(rank - 1)
    }
}
