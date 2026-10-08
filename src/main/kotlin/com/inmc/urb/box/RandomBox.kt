package com.inmc.urb.box

import com.inmc.urb.config.BoxDefaults
import kr.inmc.core.item.BlockRef
import kr.inmc.core.item.StoredItem
import com.inmc.urb.util.BlockKey
import kr.inmc.core.util.Durations
import com.inmc.urb.visual.OpenAnimation
import com.inmc.urb.visual.ParticlePreset
import com.inmc.urb.visual.RewardFlair
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.util.Random

/** How the loot reaches the player once the box finishes opening (spec §70-72). */
enum class OpenMode {
    /** Items go straight into the inventory. */
    DIRECT,

    /** A chest-style window opens; a map-spawned box vanishes after one open either way. */
    GUI,

    /** Items scatter on the ground where the box stood - everyone nearby can grab them. */
    DROP;

    fun next(): OpenMode = entries[(ordinal + 1) % entries.size]

    fun previous(): OpenMode = entries[(ordinal - 1 + entries.size) % entries.size]

    /** Korean label used across the admin menus. */
    fun label(): String = when (this) {
        DIRECT -> "인벤토리 직행"
        GUI -> "상자 창 열기"
        DROP -> "바닥에 쏟기"
    }

    companion object {
        fun parse(raw: String?): OpenMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: GUI
    }
}

/** Where a box may appear (spec §55-56). */
enum class SpawnMode {
    /** Uniformly random position inside an area. */
    RANDOM_AREA,

    /** One of a list of hand-picked coordinates, respawned on the usual schedule. */
    FIXED_POINTS,

    /**
     * Permanent installation at every configured coordinate.
     *
     * These never despawn and never respawn - they are furniture. Opening one always costs a
     * key or money, so a box with neither configured simply cannot be opened; that is what
     * stops a permanent box from becoming an infinite item faucet.
     */
    PERMANENT;

    fun next(): SpawnMode = entries[(ordinal + 1) % entries.size]

    fun previous(): SpawnMode = entries[(ordinal - 1 + entries.size) % entries.size]

    fun label(): String = when (this) {
        RANDOM_AREA -> "랜덤 영역"
        FIXED_POINTS -> "특정 좌표"
        PERMANENT -> "고정 설치"
    }

    companion object {
        fun parse(raw: String?): SpawnMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: RANDOM_AREA
    }
}

enum class ListMode {
    WHITELIST, BLACKLIST;

    fun toggle(): ListMode = if (this == WHITELIST) BLACKLIST else WHITELIST

    companion object {
        fun parse(raw: String?): ListMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: WHITELIST
    }
}

enum class AreaShape {
    /** Axis-aligned box between two corners. */
    RECTANGLE,

    /** Centre point plus a radius. */
    CIRCLE;

    fun toggle(): AreaShape = if (this == RECTANGLE) CIRCLE else RECTANGLE

    fun label(): String = if (this == RECTANGLE) "사각형" else "원형"

    companion object {
        fun parse(raw: String?): AreaShape =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: RECTANGLE
    }
}

/**
 * The XZ region a random-area box may appear in.
 *
 * Sampling lives here rather than in the spawn service so it can be unit tested without a
 * server - the circle case in particular is easy to get subtly wrong.
 */
data class SpawnArea(
    val shape: AreaShape = AreaShape.RECTANGLE,
    // rectangle corners
    val x1: Int = 0,
    val z1: Int = 0,
    val x2: Int = 0,
    val z2: Int = 0,
    // circle
    val centerX: Int = 0,
    val centerZ: Int = 0,
    val radius: Int = 0,
) {
    val minX get() = minOf(x1, x2)
    val maxX get() = maxOf(x1, x2)
    val minZ get() = minOf(z1, z2)
    val maxZ get() = maxOf(z1, z2)

    val isConfigured: Boolean
        get() = when (shape) {
            AreaShape.RECTANGLE -> x1 != x2 || z1 != z2
            AreaShape.CIRCLE -> radius > 0
        }

    /**
     * One random XZ position inside the area, or null when nothing is configured.
     *
     * The circle uses `r = radius * sqrt(u)` rather than `r = radius * u`: sampling the radius
     * uniformly would bunch points around the centre, because a ring's area grows with r.
     */
    fun randomPoint(rng: Random): IntArray? {
        if (!isConfigured) return null
        return when (shape) {
            AreaShape.RECTANGLE -> intArrayOf(
                minX + rng.nextInt(maxX - minX + 1),
                minZ + rng.nextInt(maxZ - minZ + 1),
            )

            AreaShape.CIRCLE -> {
                val angle = rng.nextDouble() * 2.0 * Math.PI
                val r = radius * Math.sqrt(rng.nextDouble())
                intArrayOf(
                    centerX + Math.round(r * Math.cos(angle)).toInt(),
                    centerZ + Math.round(r * Math.sin(angle)).toInt(),
                )
            }
        }
    }

    /** Human readable summary for the settings menu. */
    fun describe(): String = when (shape) {
        AreaShape.RECTANGLE -> "${maxX - minX + 1} x ${maxZ - minZ + 1}"
        AreaShape.CIRCLE -> "중심 $centerX, $centerZ / 반지름 $radius"
    }
}

/**
 * A box definition, persisted to `boxes/<name>.yml` (spec §94).
 *
 * Mutable on purpose: every field here is edited live from the admin GUI, and
 * [com.inmc.urb.box.BoxRegistry] flushes the object back to disk asynchronously.
 */
class RandomBox(val name: String) {

    var enabled: Boolean = true
    var displayName: String = name
    var lore: MutableList<String> = mutableListOf()
    /** What the box looks like in the world. Vanilla material or a custom-block id. */
    var block: BlockRef = BlockRef.DEFAULT

    /** Convenience for menus and fallbacks that only need something drawable. */
    val blockMaterial: Material get() = block.iconMaterial

    // --- opening -----------------------------------------------------------
    var openMode: OpenMode = OpenMode.GUI
    var openTimeSeconds: Int = 3
    var reopenCooldownSeconds: Int = 10

    /** Guaranteed floor - spec §65 insists at least one item always drops. */
    var minRolls: Int = 1
        set(value) {
            field = value.coerceIn(1, 54)
            if (field > maxRolls) maxRolls = field
        }

    /** Ceiling on how many rewards a single open may yield (spec §64-68). */
    var maxRolls: Int = 2
        set(value) {
            field = value.coerceIn(1, 54)
            if (field < minRolls) minRolls = field
        }

    /** Whether `/urb info` may show this box's chance table to players. */
    var previewEnabled: Boolean = true

    // --- open conditions (spec §74-77) -------------------------------------
    var keyItem: StoredItem? = null
    var capsuleItem: StoredItem? = null

    /** 커스텀아이템으로 옮기기 전의 열쇠·캡슐 — 이미 나가 있는 옛 아이템도 알아보게([UrbRoles]). 파일에 안 적는다. */
    var keyLegacy: StoredItem? = null
    var capsuleLegacy: StoredItem? = null
    var moneyCost: Double = 0.0
    /** 소모될 돈의 화폐 id — 비우면 기본 화폐(2026-10-08). */
    var currency: String = ""

    // --- spawning ----------------------------------------------------------
    var spawnMode: SpawnMode = SpawnMode.RANDOM_AREA
    var world: String = "world"
    var area: SpawnArea = SpawnArea()
    var fixedPoints: MutableList<BlockKey> = mutableListOf()

    /** Spec §61 forbids cave spawns by default; admins can opt in per box. */
    var allowCaveSpawn: Boolean = false
    var minY: Int? = null
    var maxY: Int? = null

    /** Seconds between automatic airdrops; 0 disables the schedule. */
    var autoSpawnIntervalSeconds: Long = 3600L

    /** Seconds a placed box survives before it despawns; 0 means it never expires. */
    var despawnSeconds: Long = 600L

    /** Cap on concurrently live boxes of this type, queued ones included (spec §10). */
    var maxSpawnCount: Int = 3

    /** How many boxes one automatic cycle tries to place. */
    var spawnAmountPerCycle: Int = 1

    // --- messages & events (spec §79-83) -----------------------------------
    var spawnMessage: String? = null
    var despawnMessage: String? = null
    var openMessage: String? = null
    var broadcastSpawn: Boolean = true
    var broadcastDespawn: Boolean = true
    var broadcastOpen: Boolean = true

    /** `/urb spawnnow` is an admin action; announcing it is usually noise. */
    var broadcastManualSpawn: Boolean = false

    /** Capsules are opened anywhere, so their open message is toggled separately. */
    var broadcastCapsuleOpen: Boolean = true

    var discordAlert: Boolean = true

    /** Per-box webhook overrides; null falls back to config.yml. */
    var discordSpawnWebhook: String? = null
    var discordRewardWebhook: String? = null

    var openCommands: MutableList<String> = mutableListOf()

    // --- visuals -----------------------------------------------------------
    var particlePreset: ParticlePreset = ParticlePreset.NONE
    var hologramEnabled: Boolean = false

    /** How the loot is revealed. */
    var openAnimation: OpenAnimation = OpenAnimation.NONE

    /** Flourish played when the loot lands. */
    var rewardFlair: RewardFlair = RewardFlair.NONE

    /** Kept as a computed view so older configs and existing menus keep working. */
    var totemEffect: Boolean
        get() = rewardFlair == RewardFlair.TOTEM
        set(value) {
            rewardFlair = if (value) RewardFlair.TOTEM else RewardFlair.NONE
        }

    // --- tiers & schedule ---------------------------------------------------
    /** Named reward pools with their own count ranges; empty means one flat pool. */
    val tiers: MutableMap<String, BoxTier> = LinkedHashMap()

    var schedule: BoxSchedule = BoxSchedule()

    // --- discovery ---------------------------------------------------------
    /** Hidden boxes are the "go find it" content: no compass tracking, no search listing. */
    var trackable: Boolean = true
    var searchable: Boolean = true

    // --- per-player open limits --------------------------------------------
    /** Seconds one player must wait before opening *this* box type again; 0 = no limit. */
    var perPlayerCooldownSeconds: Long = 0L

    /** Cap on how many times one player may open this box per period; 0 = unlimited. */
    var maxOpensPerPlayer: Int = 0

    /**
     * How long a player's [maxOpensPerPlayer] allowance lasts before it refills; 0 = never.
     *
     * Zero keeps the original meaning - a lifetime cap, the thing that makes a permanent box
     * safe. Anything else turns it into a rolling allowance ("하루 3번"), measured from that
     * player's first open of the period rather than from a server-wide clock, so nobody gets a
     * two-second window because they happened to arrive just before the reset.
     */
    var openLimitResetSeconds: Long = 0L

    // --- region filters (soft WorldGuard / Lands) --------------------------
    var regionMode: ListMode = ListMode.WHITELIST
    var regionList: MutableList<String> = mutableListOf()
    var landMode: ListMode = ListMode.WHITELIST
    var landList: MutableList<String> = mutableListOf()

    // --- loot table --------------------------------------------------------
    val rewards: MutableList<Reward> = mutableListOf()

    // --- runtime (not persisted in boxes/*.yml) ----------------------------
    /** Epoch millis of the next automatic airdrop; 0 = compute on next tick. */
    @Volatile
    var nextSpawnAt: Long = 0L

    fun reward(id: String): Reward? = rewards.firstOrNull { it.id == id }

    /**
     * The pools this box rolls. One for untiered rewards using the box-level range, plus one
     * per enabled tier. A box with no tiers yields exactly one group, so the behaviour is
     * identical to before tiers existed.
     */
    fun rollGroups(): List<RollGroup> = buildRollGroups(rewards, tiers, minRolls, maxRolls)

    /** Rewards that roll against the box-level min/max rather than a tier of their own. */
    fun untieredRewards(): List<Reward> = rewards.filter { it.tier == null || it.tier !in tiers }

    fun rewardsIn(tier: String): List<Reward> = rewards.filter { it.tier == tier }

    /** Highest number of rewards one open can produce, across every pool. */
    fun effectiveMaxRolls(): Int =
        rollGroups().sumOf { it.maxRolls.coerceIn(1, it.entries.size) }

    val usesTiers: Boolean get() = tiers.isNotEmpty() && rewards.any { it.tier != null }

    val isPermanent: Boolean get() = spawnMode == SpawnMode.PERMANENT

    /**
     * Why this box can never place anything on its own, in plain Korean, or null when it can.
     *
     * Checked from the settings alone - no world access - so the admin menus can show it while
     * drawing. Terrain-dependent failure (all ocean, all protected) is a different question and
     * belongs to `/urb testarea`, which actually samples the world.
     */
    fun autoSpawnProblem(): String? = when {
        !enabled -> "상자가 비활성화되어 있습니다"
        rewards.isEmpty() -> "등록된 보상이 없습니다"
        spawnMode == SpawnMode.RANDOM_AREA && !area.isConfigured ->
            "생성 영역이 설정되지 않았습니다"

        spawnMode != SpawnMode.RANDOM_AREA && fixedPoints.isEmpty() ->
            "'${spawnMode.label()}' 방식인데 등록된 좌표가 없습니다"

        !isPermanent && autoSpawnIntervalSeconds <= 0L -> "자동 생성 주기가 0입니다"
        else -> null
    }

    /**
     * A permanent box must cost something. With no key and no money it would be an unlimited
     * item source, so opening is refused outright rather than silently giving loot away.
     */
    fun permanentHasCost(): Boolean = keyItem != null || moneyCost > 0.0

    /** Permanent boxes always use the window, so the totem flourish has something to play over. */
    fun effectiveOpenMode(): OpenMode = if (isPermanent) OpenMode.GUI else openMode

    /** Permanent boxes never expire, whatever the despawn field says. */
    fun effectiveDespawnSeconds(): Long = if (isPermanent) 0L else despawnSeconds

    fun effectiveMinY(worldMin: Int): Int = (minY ?: worldMin).coerceAtLeast(worldMin)

    fun effectiveMaxY(worldMax: Int): Int = (maxY ?: worldMax).coerceAtMost(worldMax)

    fun save(config: YamlConfiguration) {
        config.set("enabled", enabled)
        config.set("display-name", displayName)
        config.set("lore", lore)
        config.set("block", block.serialize())

        config.set("open.mode", openMode.name)
        config.set("open.time", openTimeSeconds)
        config.set("open.reopen-cooldown", reopenCooldownSeconds)
        config.set("open.min-rolls", minRolls)
        config.set("open.max-rolls", maxRolls)
        config.set("open.preview", previewEnabled)
        config.set("open.commands", openCommands)

        config.set("conditions.money", moneyCost)
        config.set("conditions.currency", currency.takeIf { it.isNotBlank() })
        config.set("conditions.key", null)
        keyItem?.save(config.createSection("conditions.key"))
        config.set("conditions.capsule", null)
        capsuleItem?.save(config.createSection("conditions.capsule"))

        config.set("spawn.mode", spawnMode.name)
        config.set("spawn.world", world)
        config.set("spawn.area.shape", area.shape.name)
        config.set("spawn.area.x1", area.x1)
        config.set("spawn.area.z1", area.z1)
        config.set("spawn.area.x2", area.x2)
        config.set("spawn.area.z2", area.z2)
        config.set("spawn.area.center.x", area.centerX)
        config.set("spawn.area.center.z", area.centerZ)
        config.set("spawn.area.radius", area.radius)
        config.set("spawn.points", fixedPoints.map { it.toString() })
        config.set("spawn.allow-cave", allowCaveSpawn)
        config.set("spawn.min-y", minY)
        config.set("spawn.max-y", maxY)
        config.set("spawn.interval", Durations.format(autoSpawnIntervalSeconds))
        config.set("spawn.despawn-after", Durations.format(despawnSeconds))
        config.set("spawn.max-count", maxSpawnCount)
        config.set("spawn.amount-per-cycle", spawnAmountPerCycle)

        config.set("messages.spawn", spawnMessage)
        config.set("messages.despawn", despawnMessage)
        config.set("messages.open", openMessage)
        config.set("messages.broadcast-spawn", broadcastSpawn)
        config.set("messages.broadcast-despawn", broadcastDespawn)
        config.set("messages.broadcast-open", broadcastOpen)
        config.set("messages.broadcast-manual-spawn", broadcastManualSpawn)
        config.set("messages.broadcast-capsule-open", broadcastCapsuleOpen)
        config.set("messages.discord-alert", discordAlert)
        config.set("messages.discord-spawn-webhook", discordSpawnWebhook)
        config.set("messages.discord-reward-webhook", discordRewardWebhook)

        config.set("visual.particle", particlePreset.name)
        config.set("visual.hologram", hologramEnabled)
        config.set("visual.animation", openAnimation.name)
        config.set("visual.flair", rewardFlair.name)

        config.set("tiers", null)
        if (tiers.isNotEmpty()) {
            val section = config.createSection("tiers")
            tiers.forEach { (name, tier) -> tier.save(section.createSection(name)) }
        }
        config.set("schedule", null)
        schedule.save(config.createSection("schedule"))

        config.set("discovery.trackable", trackable)
        config.set("discovery.searchable", searchable)

        config.set("limits.per-player-cooldown", Durations.format(perPlayerCooldownSeconds))
        config.set("limits.max-opens-per-player", maxOpensPerPlayer)
        config.set("limits.reset-after", Durations.format(openLimitResetSeconds))

        config.set("regions.worldguard.mode", regionMode.name)
        config.set("regions.worldguard.list", regionList)
        config.set("regions.lands.mode", landMode.name)
        config.set("regions.lands.list", landList)

        config.set("rewards", null)
        val rewardsSection = config.createSection("rewards")
        rewards.forEachIndexed { index, reward ->
            reward.save(rewardsSection.createSection(index.toString()))
        }
    }

    companion object {

        fun create(name: String, defaults: BoxDefaults): RandomBox = RandomBox(name).apply {
            displayName = name
            block = defaults.block
            openTimeSeconds = defaults.openTimeSeconds
            reopenCooldownSeconds = defaults.reopenCooldownSeconds
            maxRolls = defaults.maxRolls
            minRolls = defaults.minRolls
            world = defaults.world
            autoSpawnIntervalSeconds = defaults.autoSpawnIntervalSeconds
            despawnSeconds = defaults.despawnSeconds
            maxSpawnCount = defaults.maxSpawnCount
            broadcastManualSpawn = defaults.broadcastManualSpawn
            broadcastCapsuleOpen = defaults.broadcastCapsuleOpen
            particlePreset = defaults.particlePreset
            hologramEnabled = defaults.hologramEnabled
            totemEffect = defaults.totemEffect
            defaults.location?.let {
                fixedPoints.add(it)
                world = it.world
                area = SpawnArea(x1 = it.x - 100, z1 = it.z - 100, x2 = it.x + 100, z2 = it.z + 100)
            }
        }

        fun load(name: String, config: YamlConfiguration, defaults: BoxDefaults): RandomBox {
            val box = RandomBox(name)
            box.enabled = config.getBoolean("enabled", true)
            box.displayName = config.getString("display-name") ?: name
            box.lore = config.getStringList("lore").toMutableList()
            box.block = config.getString("block")?.let { BlockRef.parse(it) } ?: defaults.block

            box.openMode = OpenMode.parse(config.getString("open.mode"))
            box.openTimeSeconds = config.getInt("open.time", defaults.openTimeSeconds).coerceIn(0, 60)
            box.reopenCooldownSeconds = config.getInt("open.reopen-cooldown", defaults.reopenCooldownSeconds)
            box.maxRolls = config.getInt("open.max-rolls", defaults.maxRolls)
            box.minRolls = config.getInt("open.min-rolls", defaults.minRolls)
            box.previewEnabled = config.getBoolean("open.preview", true)
            box.openCommands = config.getStringList("open.commands").toMutableList()

            box.moneyCost = config.getDouble("conditions.money", 0.0)
            box.currency = config.getString("conditions.currency").orEmpty()
            box.keyItem = config.getConfigurationSection("conditions.key")?.let { StoredItem.load(it) }
            box.capsuleItem = config.getConfigurationSection("conditions.capsule")?.let { StoredItem.load(it) }

            box.spawnMode = SpawnMode.parse(config.getString("spawn.mode"))
            box.world = config.getString("spawn.world") ?: defaults.world
            box.area = SpawnArea(
                shape = AreaShape.parse(config.getString("spawn.area.shape")),
                x1 = config.getInt("spawn.area.x1", 0),
                z1 = config.getInt("spawn.area.z1", 0),
                x2 = config.getInt("spawn.area.x2", 0),
                z2 = config.getInt("spawn.area.z2", 0),
                centerX = config.getInt("spawn.area.center.x", 0),
                centerZ = config.getInt("spawn.area.center.z", 0),
                radius = config.getInt("spawn.area.radius", 0).coerceAtLeast(0),
            )
            box.fixedPoints = config.getStringList("spawn.points")
                .mapNotNull { BlockKey.parse(it) }
                .toMutableList()
            box.allowCaveSpawn = config.getBoolean("spawn.allow-cave", false)
            box.minY = if (config.isSet("spawn.min-y")) config.getInt("spawn.min-y") else null
            box.maxY = if (config.isSet("spawn.max-y")) config.getInt("spawn.max-y") else null
            box.autoSpawnIntervalSeconds =
                Durations.parse(config.getString("spawn.interval"), defaults.autoSpawnIntervalSeconds)
            box.despawnSeconds =
                Durations.parse(config.getString("spawn.despawn-after"), defaults.despawnSeconds)
            box.maxSpawnCount = config.getInt("spawn.max-count", defaults.maxSpawnCount).coerceAtLeast(0)
            box.spawnAmountPerCycle = config.getInt("spawn.amount-per-cycle", 1).coerceIn(1, 64)

            box.spawnMessage = config.getString("messages.spawn")
            box.despawnMessage = config.getString("messages.despawn")
            box.openMessage = config.getString("messages.open")
            box.broadcastSpawn = config.getBoolean("messages.broadcast-spawn", true)
            box.broadcastDespawn = config.getBoolean("messages.broadcast-despawn", true)
            box.broadcastOpen = config.getBoolean("messages.broadcast-open", true)
            box.broadcastManualSpawn =
                config.getBoolean("messages.broadcast-manual-spawn", defaults.broadcastManualSpawn)
            box.broadcastCapsuleOpen =
                config.getBoolean("messages.broadcast-capsule-open", defaults.broadcastCapsuleOpen)
            box.discordAlert = config.getBoolean("messages.discord-alert", true)
            box.discordSpawnWebhook = config.getString("messages.discord-spawn-webhook")?.takeIf { it.isNotBlank() }
            box.discordRewardWebhook = config.getString("messages.discord-reward-webhook")?.takeIf { it.isNotBlank() }

            box.particlePreset = ParticlePreset.parse(
                config.getString("visual.particle") ?: defaults.particlePreset.name
            )
            box.hologramEnabled = config.getBoolean("visual.hologram", defaults.hologramEnabled)
            box.openAnimation = OpenAnimation.parse(config.getString("visual.animation") ?: defaults.openAnimation.name)
            box.rewardFlair = if (config.isSet("visual.flair")) {
                RewardFlair.parse(config.getString("visual.flair"))
            } else {
                // Older files only had a boolean totem toggle.
                if (config.getBoolean("visual.totem-effect", defaults.totemEffect)) RewardFlair.TOTEM
                else defaults.rewardFlair
            }

            config.getConfigurationSection("tiers")?.let { section ->
                for (key in section.getKeys(false)) {
                    section.getConfigurationSection(key)?.let { box.tiers[key] = BoxTier.load(key, it) }
                }
            }
            box.schedule = BoxSchedule.load(config.getConfigurationSection("schedule"))

            box.trackable = config.getBoolean("discovery.trackable", true)
            box.searchable = config.getBoolean("discovery.searchable", true)

            box.perPlayerCooldownSeconds =
                Durations.parse(config.getString("limits.per-player-cooldown"), 0L)
            box.maxOpensPerPlayer = config.getInt("limits.max-opens-per-player", 0).coerceAtLeast(0)
            box.openLimitResetSeconds = Durations.parse(config.getString("limits.reset-after"), 0L)

            box.regionMode = ListMode.parse(config.getString("regions.worldguard.mode"))
            box.regionList = config.getStringList("regions.worldguard.list").toMutableList()
            box.landMode = ListMode.parse(config.getString("regions.lands.mode"))
            box.landList = config.getStringList("regions.lands.list").toMutableList()

            config.getConfigurationSection("rewards")?.let { section ->
                for (key in section.getKeys(false)) {
                    val entry: ConfigurationSection = section.getConfigurationSection(key) ?: continue
                    Reward.load(entry, defaults.itemChance)?.let { box.rewards.add(it) }
                }
            }
            return box
        }
    }
}
