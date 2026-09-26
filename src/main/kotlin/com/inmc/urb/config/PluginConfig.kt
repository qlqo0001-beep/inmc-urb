package com.inmc.urb.config

import kr.inmc.core.item.BlockRef
import com.inmc.urb.util.BlockKey
import kr.inmc.core.util.Durations
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration

/** Defaults a newly created box inherits, and the fallbacks a box's own file may omit. */
data class BoxDefaults(
    val world: String,
    /** 디폴트 상자 생성 좌표값 - seeds a new box's fixed point and area. */
    val location: BlockKey?,
    val block: kr.inmc.core.item.BlockRef,
    val autoSpawnIntervalSeconds: Long,
    val despawnSeconds: Long,
    val maxSpawnCount: Int,
    val itemChance: Double,
    val openTimeSeconds: Int,
    val reopenCooldownSeconds: Int,
    val minRolls: Int,
    val maxRolls: Int,
    val spawnMessage: String,
    val despawnMessage: String,
    val openMessage: String,
    /** `/urb spawnnow` is an admin action - silent unless the server wants the noise. */
    val broadcastManualSpawn: Boolean,
    val broadcastCapsuleOpen: Boolean,
    val particlePreset: com.inmc.urb.visual.ParticlePreset,
    val hologramEnabled: Boolean,
    val totemEffect: Boolean,
    val openAnimation: com.inmc.urb.visual.OpenAnimation,
    val rewardFlair: com.inmc.urb.visual.RewardFlair,
)

/** When a placed box's despawn countdown starts. */
enum class DespawnTimerStart {
    /** Countdown begins when the block is actually placed - a long-queued box still gets its full life. */
    ON_PLACE,

    /** Countdown begins at announcement, queued or not. */
    ON_ANNOUNCE;

    companion object {
        fun parse(raw: String?): DespawnTimerStart =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ON_PLACE
    }
}

/**
 * Discord webhooks.
 *
 * Spawn alerts and rare-reward alerts get their own URL so they can go to different channels -
 * a spawn ping is for the treasure-hunter role, a jackpot announcement is usually for everyone.
 * Either falls back to [webhookUrl] when left blank, and a box may override both again.
 */
data class DiscordSettings(
    val webhookUrl: String,
    val username: String,
    val avatarUrl: String,
    val spawnEnabled: Boolean,
    val spawnWebhookUrl: String,
    val spawnMessage: String,
    val rewardEnabled: Boolean,
    val rewardWebhookUrl: String,
    val rewardMessage: String,
    val despawnEnabled: Boolean,
    val despawnMessage: String,
) {
    private fun resolve(specific: String): String =
        specific.takeIf { it.startsWith("https://") } ?: webhookUrl

    fun spawnUrl(override: String?): String =
        override?.takeIf { it.startsWith("https://") } ?: resolve(spawnWebhookUrl)

    fun rewardUrl(override: String?): String =
        override?.takeIf { it.startsWith("https://") } ?: resolve(rewardWebhookUrl)

    fun despawnUrl(override: String?): String = spawnUrl(override)

    val isConfigured: Boolean
        get() = webhookUrl.startsWith("https://") ||
            spawnWebhookUrl.startsWith("https://") ||
            rewardWebhookUrl.startsWith("https://")
}

/** Parsed view of `config.yml`. Rebuilt wholesale on reload; never mutated in place. */
class PluginConfig private constructor(
    val defaults: BoxDefaults,
    val pendingMaxAgeSeconds: Long,
    val pendingLimit: Int,
    val despawnTimerStart: DespawnTimerStart,
    val loadedChunkAttempts: Int,
    val requirePlayersOnline: Boolean,
    val cleanupAllOnShutdown: Boolean,
    val searchCooldownSeconds: Long,
    val searchShowPending: Boolean,
    val noticeHistorySize: Int,
    val statsLogSize: Int,
    val statsRankingSize: Int,
    val simulationMaxIterations: Long,
    /** Appends a clickable "추적하기" button to every spawn broadcast. */
    val spawnTrackButton: Boolean,
    val discord: DiscordSettings,
) {

    companion object {

        fun from(config: YamlConfiguration): PluginConfig {
            val world = config.getString("defaults.world") ?: "world"

            val location = config.getConfigurationSection("defaults.spawn-location")?.let { section ->
                if (!section.getBoolean("enabled", false)) {
                    null
                } else {
                    BlockKey(
                        section.getString("world") ?: world,
                        section.getInt("x", 0),
                        section.getInt("y", 64),
                        section.getInt("z", 0),
                    )
                }
            }

            val defaults = BoxDefaults(
                world = world,
                location = location,
                block = kr.inmc.core.item.BlockRef.parse(config.getString("defaults.block")),
                autoSpawnIntervalSeconds = Durations.parse(config.getString("defaults.auto-spawn-interval"), 3600L),
                despawnSeconds = Durations.parse(config.getString("defaults.despawn-after"), 600L),
                maxSpawnCount = config.getInt("defaults.max-spawn-count", 3).coerceAtLeast(0),
                itemChance = config.getDouble("defaults.item-chance", 50.0).coerceIn(0.01, 100.0),
                openTimeSeconds = config.getInt("defaults.open-time", 3).coerceIn(0, 60),
                reopenCooldownSeconds = config.getInt("defaults.reopen-cooldown", 10).coerceAtLeast(0),
                minRolls = config.getInt("defaults.min-rolls", 1).coerceIn(1, 54),
                maxRolls = config.getInt("defaults.max-rolls", 2).coerceIn(1, 54),
                spawnMessage = config.getString("defaults.messages.spawn")
                    ?: "<white>{world}월드에 <yellow>{상자이름}</yellow> 상자가 생성되었습니다! 위치: <gray>{x}, {z}</gray></white>",
                despawnMessage = config.getString("defaults.messages.despawn")
                    ?: "<gray>{world}월드의 {상자이름} 상자가 시간이 되어 소멸하였습니다! 위치: {x}, {z}</gray>",
                openMessage = config.getString("defaults.messages.open")
                    ?: "<white><yellow>{플레이어네임}</yellow>님이 <yellow>{상자이름}</yellow> 상자를 열었습니다! 위치: <gray>{좌표}</gray></white>",
                broadcastManualSpawn = config.getBoolean("defaults.messages.broadcast-manual-spawn", false),
                broadcastCapsuleOpen = config.getBoolean("defaults.messages.broadcast-capsule-open", true),
                particlePreset = com.inmc.urb.visual.ParticlePreset.parse(config.getString("defaults.visual.particle")),
                hologramEnabled = config.getBoolean("defaults.visual.hologram", false),
                totemEffect = config.getBoolean("defaults.visual.totem-effect", false),
                openAnimation = com.inmc.urb.visual.OpenAnimation.parse(config.getString("defaults.visual.animation")),
                rewardFlair = com.inmc.urb.visual.RewardFlair.parse(config.getString("defaults.visual.flair")),
            )

            val discord = DiscordSettings(
                webhookUrl = config.getString("discord.webhook-url")?.trim() ?: "",
                username = config.getString("discord.username") ?: "랜덤박스",
                avatarUrl = config.getString("discord.avatar-url")?.trim() ?: "",
                spawnEnabled = config.getBoolean("discord.spawn.enabled", true),
                spawnWebhookUrl = config.getString("discord.spawn.webhook-url")?.trim() ?: "",
                spawnMessage = config.getString("discord.spawn.message")
                    ?: "📦 **{상자이름}** 상자가 {world} `{x}, {z}` 에 등장했습니다!",
                rewardEnabled = config.getBoolean("discord.reward.enabled", true),
                rewardWebhookUrl = config.getString("discord.reward.webhook-url")?.trim() ?: "",
                rewardMessage = config.getString("discord.reward.message")
                    ?: "🎉 **{플레이어네임}** 님이 **{상자이름}** 에서 **{아이템}** 을(를) 획득했습니다!",
                despawnEnabled = config.getBoolean("discord.despawn.enabled", false),
                despawnMessage = config.getString("discord.despawn.message")
                    ?: "⌛ **{상자이름}** 상자가 {world} `{x}, {z}` 에서 소멸했습니다.",
            )

            return PluginConfig(
                defaults = defaults,
                pendingMaxAgeSeconds = Durations.parse(config.getString("spawn.pending-max-age"), 172800L),
                pendingLimit = config.getInt("spawn.pending-limit", 500).coerceAtLeast(0),
                despawnTimerStart = DespawnTimerStart.parse(config.getString("spawn.despawn-timer-start")),
                loadedChunkAttempts = config.getInt("spawn.loaded-chunk-attempts", 24).coerceIn(1, 512),
                requirePlayersOnline = config.getBoolean("spawn.require-players-online", true),
                cleanupAllOnShutdown = config.getBoolean("shutdown.cleanup-all", false),
                searchCooldownSeconds = Durations.parse(config.getString("search.cooldown"), 300L),
                searchShowPending = config.getBoolean("search.show-pending", true),
                noticeHistorySize = config.getInt("notice.history-size", 54).coerceIn(9, 200),
                statsLogSize = config.getInt("stats.log-size", 100).coerceIn(9, 1_000),
                statsRankingSize = config.getInt("stats.ranking-size", 45).coerceIn(3, 45),
                simulationMaxIterations = config.getLong("simulation.max-iterations", 10_000_000L)
                    .coerceIn(1_000L, 1_000_000_000L),
                spawnTrackButton = config.getBoolean("spawn.track-button", true),
                discord = discord,
            )
        }
    }
}
