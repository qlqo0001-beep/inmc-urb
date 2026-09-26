package com.inmc.urb.box

import kr.inmc.core.item.BlockRef
import com.inmc.urb.Urb
import com.inmc.urb.config.DespawnTimerStart
import com.inmc.urb.util.BlockKey
import com.inmc.urb.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import java.util.Collections
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

enum class SpawnOutcome { PLACED, QUEUED, LIMIT, NO_LOCATION, NO_WORLD, DISABLED, NO_POINTS }

/** Where a box physically is right now, whether placed or still queued. */
data class BoxSighting(
    val boxName: String,
    val displayName: String,
    val world: String,
    val x: Int,
    val y: Int?,
    val z: Int,
    val remainingSeconds: Long,
    val queued: Boolean,
)

/**
 * Owns every box that exists in the world, plus the queue of boxes that have been announced
 * but whose chunk is not loaded yet.
 *
 * Two design points worth stating, because both differ from the obvious approach:
 *
 *  - **No chunk is ever force-loaded.** A random box is a supply drop: the broadcast tells
 *    players where to go, and the block appears when someone actually travels there and the
 *    chunk loads on its own. Calling `getChunkAtAsync(.., generate = true)` for a wide random
 *    area would generate terrain server-wide and is precisely the hitch the queue avoids.
 *  - **Boxes survive a restart.** State is persisted with absolute expiry timestamps and
 *    the original [org.bukkit.block.data.BlockData], so a box placed before a restart is
 *    still there afterwards and still despawns on time.
 */
class BoxSpawnService(private val urb: Urb) {

    private val rng = Random()

    /** Box name -> the structural failure already logged, so one bad box logs once, not hourly. */
    private val reportedFailures = ConcurrentHashMap<String, SpawnOutcome>()

    private val byBlock = ConcurrentHashMap<BlockKey, SpawnedBox>()
    private val byChunk = ConcurrentHashMap<Long, MutableList<SpawnedBox>>()
    private val byBox = ConcurrentHashMap<String, MutableList<SpawnedBox>>()

    private val pendingByChunk = ConcurrentHashMap<Long, MutableList<PendingSpawn>>()
    private val pendingByBox = ConcurrentHashMap<String, MutableList<PendingSpawn>>()

    /** Boxes whose chunk was unloaded when they expired - restored on the next chunk load. */
    private val restoreByChunk = ConcurrentHashMap<Long, MutableList<RestoreEntry>>()

    @Volatile
    private var stateDirty = false

    data class RestoreEntry(
        val key: BlockKey,
        val blockData: String,
        /** Material to overwrite; null means restore unconditionally. */
        val boxMaterial: Material?,
        /**
         * Needed so a custom block can be de-registered with its owning plugin.
         *
         * Persisted alongside the entry: a deferred restore that outlives a restart used to
         * come back with this null, which is exactly the case where the owning plugin is left
         * holding an invisible block.
         */
        val boxRef: kr.inmc.core.item.BlockRef? = null,
    )

    // --- queries ---------------------------------------------------------------

    fun at(key: BlockKey): SpawnedBox? = byBlock[key]

    fun at(location: Location): SpawnedBox? = byBlock[BlockKey.of(location)]

    fun spawnedCount(boxName: String): Int = byBox[boxName]?.size ?: 0

    fun pendingCount(boxName: String): Int = pendingByBox[boxName]?.size ?: 0

    /** Placed plus queued - a queued box already holds its slot, since it was announced. */
    fun liveCount(boxName: String): Int = spawnedCount(boxName) + pendingCount(boxName)

    fun totalSpawned(): Int = byBlock.size

    fun totalPending(): Int = pendingByChunk.values.sumOf { it.size }

    fun allSpawned(): List<SpawnedBox> = byBlock.values.toList()

    /** Everything a player may see in `/urb search`, nearest first when [from] is given. */
    fun sightings(includePending: Boolean, from: Location?): List<BoxSighting> {
        val now = System.currentTimeMillis()
        val result = ArrayList<BoxSighting>()

        for (spawned in byBlock.values) {
            val box = urb.boxes.get(spawned.boxName)
            if (box != null && !box.searchable) continue
            result += BoxSighting(
                boxName = spawned.boxName,
                displayName = box?.displayName ?: spawned.boxName,
                world = spawned.key.world,
                x = spawned.key.x,
                y = spawned.key.y,
                z = spawned.key.z,
                remainingSeconds = spawned.remainingSeconds(now),
                queued = false,
            )
        }
        if (includePending) {
            for (list in pendingByChunk.values) {
                for (pending in list) {
                    val box = urb.boxes.get(pending.boxName)
                    if (box != null && !box.searchable) continue
                    result += BoxSighting(
                        boxName = pending.boxName,
                        displayName = box?.displayName ?: pending.boxName,
                        world = pending.world,
                        x = pending.x,
                        y = pending.forcedY,
                        z = pending.z,
                        remainingSeconds = pending.remainingSeconds(now),
                        queued = true,
                    )
                }
            }
        }

        if (from == null) return result
        return result.sortedBy { sighting ->
            if (sighting.world != from.world.name) Double.MAX_VALUE
            else horizontalDistance(from, sighting.x, sighting.z)
        }
    }

    private fun horizontalDistance(from: Location, x: Int, z: Int): Double {
        val dx = from.blockX - x
        val dz = from.blockZ - z
        return Math.sqrt((dx.toDouble() * dx) + (dz.toDouble() * dz))
    }

    // --- spawning --------------------------------------------------------------

    /** Automatic / `/urb spawn` path: honours the box's own spawn mode and its count cap. */
    fun requestSpawn(box: RandomBox): SpawnOutcome {
        if (!box.enabled) return SpawnOutcome.DISABLED
        val world = Bukkit.getWorld(box.world) ?: return SpawnOutcome.NO_WORLD

        // A permanent box is installed, not spawned - it re-asserts its own points and is not
        // subject to the concurrent-count cap.
        if (box.spawnMode == SpawnMode.PERMANENT) {
            if (box.fixedPoints.isEmpty()) return SpawnOutcome.NO_POINTS
            ensurePermanent(box)
            return SpawnOutcome.PLACED
        }

        if (box.maxSpawnCount > 0 && liveCount(box.name) >= box.maxSpawnCount) return SpawnOutcome.LIMIT

        return when (box.spawnMode) {
            SpawnMode.FIXED_POINTS -> spawnAtFixedPoint(box, world)
            SpawnMode.RANDOM_AREA -> spawnInArea(box, world)
            SpawnMode.PERMANENT -> SpawnOutcome.PLACED
        }
    }

    /** `/urb spawnnow`: deliberate placement at an exact spot, so the count cap is bypassed. */
    fun spawnExact(box: RandomBox, location: Location): SpawnOutcome {
        if (!box.enabled) return SpawnOutcome.DISABLED
        val world = location.world ?: return SpawnOutcome.NO_WORLD
        val placed = place(box, world, location.blockX, location.blockY, location.blockZ, replaceBlock = true)
            ?: return SpawnOutcome.NO_LOCATION
        announceSpawn(box, placed.key.world, placed.key.x, placed.key.y, placed.key.z, manual = true)
        return SpawnOutcome.PLACED
    }

    /**
     * Installs every configured point of a PERMANENT box that is not already standing.
     * Called on startup and after a reload, so the furniture re-appears by itself.
     */
    fun ensurePermanent(box: RandomBox): Int {
        if (!box.enabled || !box.isPermanent) return 0
        var placed = 0
        for (point in box.fixedPoints) {
            if (byBlock[point] != null) continue
            val world = Bukkit.getWorld(point.world) ?: continue
            if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) {
                // Not loaded yet; the chunk-load hook will pick it up.
                enqueue(box, world.name, point.x, point.z, forcedY = point.y, replaceBlock = true, announce = false)
                continue
            }
            if (place(box, world, point.x, point.y, point.z, replaceBlock = true) != null) placed++
        }
        return placed
    }

    private fun spawnAtFixedPoint(box: RandomBox, world: World): SpawnOutcome {
        val free = box.fixedPoints.filter { byBlock[it] == null }
        if (free.isEmpty()) return SpawnOutcome.NO_POINTS
        val point = free[rng.nextInt(free.size)]
        val target = Bukkit.getWorld(point.world) ?: world

        if (target.isChunkLoaded(point.x shr 4, point.z shr 4)) {
            if (!urb.regions.isAllowed(box, point.toLocation(target))) return SpawnOutcome.NO_LOCATION
            // Fixed points clear whatever is in the way (spec §59).
            val placed = place(box, target, point.x, point.y, point.z, replaceBlock = true)
                ?: return SpawnOutcome.NO_LOCATION
            announceSpawn(box, placed.key.world, placed.key.x, placed.key.y, placed.key.z)
            return SpawnOutcome.PLACED
        }

        enqueue(box, target.name, point.x, point.z, forcedY = point.y, replaceBlock = true)
        return SpawnOutcome.QUEUED
    }

    private fun spawnInArea(box: RandomBox, world: World): SpawnOutcome {
        val area = box.area
        if (!area.isConfigured) return SpawnOutcome.NO_LOCATION

        // Prefer already-loaded chunks so the common case needs no queueing at all.
        var fallbackX = 0
        var fallbackZ = 0
        var haveFallback = false

        repeat(urb.config.loadedChunkAttempts) {
            val point = area.randomPoint(rng) ?: return@repeat
            val x = point[0]
            val z = point[1]

            if (!haveFallback) {
                fallbackX = x
                fallbackZ = z
                haveFallback = true
            }
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return@repeat
            if (!urb.regions.isAllowed(box, Location(world, x.toDouble(), 0.0, z.toDouble()))) return@repeat

            val y = urb.finder.findY(world, x, z, box, rng) ?: return@repeat
            val placed = place(box, world, x, y, z, replaceBlock = false) ?: return@repeat
            announceSpawn(box, placed.key.world, placed.key.x, placed.key.y, placed.key.z)
            return SpawnOutcome.PLACED
        }

        if (!haveFallback) return SpawnOutcome.NO_LOCATION
        if (!urb.regions.isAllowed(box, Location(world, fallbackX.toDouble(), 0.0, fallbackZ.toDouble()))) {
            return SpawnOutcome.NO_LOCATION
        }
        if (urb.config.pendingLimit in 1..totalPending()) return SpawnOutcome.LIMIT

        enqueue(box, world.name, fallbackX, fallbackZ, forcedY = null, replaceBlock = false)
        return SpawnOutcome.QUEUED
    }

    /**
     * Announces immediately and parks the spawn until the chunk loads naturally.
     *
     * Announcing here rather than at placement is intentional: the coordinates *are* the
     * gameplay, so players need them the moment the drop is decided. Y is unknown at this
     * point and renders as `?`.
     */
    private fun enqueue(
        box: RandomBox,
        world: String,
        x: Int,
        z: Int,
        forcedY: Int?,
        replaceBlock: Boolean,
        announce: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val pending = PendingSpawn(
            boxName = box.name,
            world = world,
            x = x,
            z = z,
            forcedY = forcedY,
            replaceBlock = replaceBlock,
            announcedAt = now,
            // Permanent installations wait as long as it takes - they are not a timed drop.
            expiresAt = if (box.isPermanent) 0L else now + urb.config.pendingMaxAgeSeconds * 1000L,
        )
        pendingByChunk.computeIfAbsent(pending.chunkKey) { synchronizedList() }.add(pending)
        pendingByBox.computeIfAbsent(box.name) { synchronizedList() }.add(pending)
        stateDirty = true
        if (announce) announceSpawn(box, world, x, forcedY, z)
    }

    /**
     * Places the block and records it. Returns null when the position is already taken or the
     * world refuses it.
     */
    private fun place(box: RandomBox, world: World, x: Int, y: Int, z: Int, replaceBlock: Boolean): SpawnedBox? {
        val key = BlockKey(world.name, x, y, z)
        if (byBlock.containsKey(key)) return null
        if (y < world.minHeight || y >= world.maxHeight) return null

        val block = world.getBlockAt(x, y, z)
        if (!replaceBlock && !urb.finder.isEmpty(block)) return null

        val original = block.blockData.getAsString(true)
        urb.blockPlacer.place(block, box.block)

        // Read back what actually landed. For a custom block this is the owning plugin's own
        // material, which is not the one the box config names.
        val placed = block.type

        val now = System.currentTimeMillis()
        val spawned = SpawnedBox(
            boxName = box.name,
            key = key,
            originalBlockData = original,
            placedMaterial = placed,
            spawnedAt = now,
            expiresAt = box.effectiveDespawnSeconds().let { if (it > 0) now + it * 1000L else 0L },
        )
        index(spawned)
        stateDirty = true
        return spawned
    }

    private fun index(spawned: SpawnedBox) {
        byBlock[spawned.key] = spawned
        byChunk.computeIfAbsent(spawned.chunkKey) { synchronizedList() }.add(spawned)
        byBox.computeIfAbsent(spawned.boxName) { synchronizedList() }.add(spawned)
    }

    private fun unindex(spawned: SpawnedBox) {
        byBlock.remove(spawned.key)
        byChunk[spawned.chunkKey]?.let { list ->
            list.remove(spawned)
            if (list.isEmpty()) byChunk.remove(spawned.chunkKey)
        }
        byBox[spawned.boxName]?.let { list ->
            list.remove(spawned)
            if (list.isEmpty()) byBox.remove(spawned.boxName)
        }
        stateDirty = true
    }

    // --- removal ---------------------------------------------------------------

    /** Removes a box from the world, restoring whatever block it replaced. */
    fun remove(spawned: SpawnedBox, announce: Boolean) {
        unindex(spawned)
        urb.visuals.onBoxRemoved(spawned.key)
        restoreBlock(spawned)
        if (announce) {
            urb.boxes.get(spawned.boxName)?.let { announceDespawn(it, spawned.key) }
        }
    }

    /**
     * The material the restore is allowed to overwrite.
     *
     * Prefers what was recorded at placement. Falls back to the box's configured block only
     * for state entries written before that was recorded, so upgrading cannot change how an
     * already-standing box behaves.
     */
    private fun expectedMaterial(spawned: SpawnedBox): Material? =
        spawned.placedMaterial ?: urb.boxes.get(spawned.boxName)?.blockMaterial

    private fun restoreBlock(spawned: SpawnedBox) {
        val world = Bukkit.getWorld(spawned.key.world)
        if (world == null) return
        val box = urb.boxes.get(spawned.boxName)
        val expected = expectedMaterial(spawned)

        if (!world.isChunkLoaded(spawned.key.x shr 4, spawned.key.z shr 4)) {
            // Never force-load just to tidy up; do it when the chunk next comes in.
            restoreByChunk.computeIfAbsent(spawned.chunkKey) { synchronizedList() }.add(
                RestoreEntry(spawned.key, spawned.originalBlockData, expected, box?.block)
            )
            stateDirty = true
            return
        }
        applyRestore(world, spawned.key, spawned.originalBlockData, expected, box?.block)
    }

    private fun applyRestore(
        world: World,
        key: BlockKey,
        blockData: String,
        expected: Material?,
        ref: kr.inmc.core.item.BlockRef? = null,
    ) {
        val block = world.getBlockAt(key.x, key.y, key.z)
        // Someone may have replaced the block by hand or with WorldEdit - leave that alone.
        if (expected != null && block.type != expected) return

        // A custom block keeps state in its owning plugin; clearing that first stops
        // ItemsAdder from leaving an invisible entry behind after we overwrite the block.
        ref?.let { urb.blockPlacer.clear(block, it) }

        try {
            block.setBlockData(Bukkit.createBlockData(blockData), false)
        } catch (_: IllegalArgumentException) {
            block.setType(Material.AIR, false)
        }
    }

    fun removePending(pending: PendingSpawn, announce: Boolean) {
        pendingByChunk[pending.chunkKey]?.let { list ->
            list.remove(pending)
            if (list.isEmpty()) pendingByChunk.remove(pending.chunkKey)
        }
        pendingByBox[pending.boxName]?.let { list ->
            list.remove(pending)
            if (list.isEmpty()) pendingByBox.remove(pending.boxName)
        }
        stateDirty = true
        if (announce) {
            urb.boxes.get(pending.boxName)?.let {
                announceDespawn(it, BlockKey(pending.world, pending.x, pending.forcedY ?: 0, pending.z), pending.forcedY == null)
            }
        }
    }

    /** `/urb reset <name>` / `reset all`. Returns how many were removed, queue included. */
    fun reset(boxName: String?): Int {
        val targets = if (boxName == null) byBlock.values.toList()
        else byBlock.values.filter { it.boxName == boxName }
        targets.forEach { remove(it, announce = false) }

        val queued = if (boxName == null) pendingByChunk.values.flatten()
        else pendingByBox[boxName]?.toList() ?: emptyList()
        queued.forEach { removePending(it, announce = false) }

        return targets.size + queued.size
    }

    // --- ticking ---------------------------------------------------------------

    /** Called once a second by the ticker. Expires placed boxes and stale queue entries. */
    fun tick(now: Long) {
        for (spawned in byBlock.values) {
            if (spawned.isExpired(now)) remove(spawned, announce = true)
        }
        for (list in pendingByChunk.values.toList()) {
            for (pending in list.toList()) {
                if (pending.isExpired(now)) removePending(pending, announce = true)
            }
        }
    }

    /** Auto-airdrop schedule; also driven by the ticker. */
    fun tickAutoSpawn(now: Long) {
        if (urb.config.requirePlayersOnline && Bukkit.getOnlinePlayers().isEmpty()) return
        val clock = java.time.ZonedDateTime.now()
        for (box in urb.boxes.all()) {
            if (!box.enabled || box.autoSpawnIntervalSeconds <= 0L) continue
            if (box.nextSpawnAt == 0L) {
                box.nextSpawnAt = now + box.autoSpawnIntervalSeconds * 1000L
                continue
            }
            if (now < box.nextSpawnAt) continue

            // The timer keeps running outside the window; only the placement is skipped. That
            // way a box whose window opens mid-interval does not fire a backlog all at once.
            box.nextSpawnAt = now + box.autoSpawnIntervalSeconds * 1000L
            if (!box.schedule.allows(clock)) continue

            var best = SpawnOutcome.NO_LOCATION
            var any = false
            repeat(box.spawnAmountPerCycle) {
                val outcome = requestSpawn(box)
                if (!any || outcome.rank() > best.rank()) best = outcome
                any = true
            }
            if (any) reportAutoSpawn(box, best)
        }
    }

    /** Ordering for "the least bad thing that happened this cycle". */
    private fun SpawnOutcome.rank(): Int = when (this) {
        SpawnOutcome.PLACED -> 3
        SpawnOutcome.QUEUED -> 2
        SpawnOutcome.LIMIT -> 1
        else -> 0
    }

    /**
     * Says out loud when a box's automatic cycle cannot place anything.
     *
     * Auto-spawn used to fail in total silence: a box left in 특정 좌표 mode with no coordinates
     * registered, or in 랜덤 영역 mode with no area set, simply never appeared and wrote nothing
     * anywhere. From the admin's side that is indistinguishable from the scheduler being broken.
     *
     * Reported once per box per cause, and re-armed as soon as the box places something, so a
     * misconfigured box says so exactly once instead of every interval.
     */
    private fun reportAutoSpawn(box: RandomBox, outcome: SpawnOutcome) {
        if (outcome == SpawnOutcome.PLACED || outcome == SpawnOutcome.QUEUED) {
            reportedFailures.remove(box.name)
            return
        }
        // The cap being full is the system working, not a problem.
        if (outcome == SpawnOutcome.LIMIT) return
        if (reportedFailures.put(box.name, outcome) == outcome) return

        val reason = when (outcome) {
            SpawnOutcome.NO_POINTS ->
                if (box.spawnMode == SpawnMode.RANDOM_AREA) "생성 영역이 설정되지 않았습니다"
                else "'${box.spawnMode.label()}' 방식인데 등록된 좌표가 없습니다"

            // An unconfigured area also surfaces as NO_LOCATION, and "no room in the area" is
            // the wrong thing to tell someone who never drew one.
            SpawnOutcome.NO_LOCATION ->
                if (box.spawnMode == SpawnMode.RANDOM_AREA && !box.area.isConfigured)
                    "생성 영역이 설정되지 않았습니다"
                else "영역 안에서 상자를 놓을 수 있는 자리를 찾지 못했습니다"
            SpawnOutcome.NO_WORLD -> "'${box.world}' 월드를 찾을 수 없습니다"
            SpawnOutcome.DISABLED -> "상자가 비활성화되어 있습니다"
            SpawnOutcome.PLACED, SpawnOutcome.QUEUED, SpawnOutcome.LIMIT -> return
        }
        urb.logger.warning(
            "'${box.name}' 상자의 자동 생성이 실패했습니다 - $reason. " +
                "/urb testarea ${box.name} 으로 원인을 확인할 수 있습니다."
        )
    }

    /** Seconds until the next automatic attempt, or null when the box has no schedule. */
    fun secondsUntilAutoSpawn(box: RandomBox, now: Long = System.currentTimeMillis()): Long? {
        if (!box.enabled || box.autoSpawnIntervalSeconds <= 0L) return null
        if (box.nextSpawnAt <= 0L) return box.autoSpawnIntervalSeconds
        return ((box.nextSpawnAt - now) / 1000L).coerceAtLeast(0L)
    }

    // --- chunk load ------------------------------------------------------------

    /** Materialises anything waiting on this chunk, and finishes any deferred restores. */
    fun onChunkLoad(chunk: Chunk) {
        val key = chunk.chunkKey

        restoreByChunk.remove(key)?.let { entries ->
            entries.forEach { applyRestore(chunk.world, it.key, it.blockData, it.boxMaterial, it.boxRef) }
            stateDirty = true
        }

        val waiting = pendingByChunk.remove(key) ?: return
        for (pending in waiting.toList()) {
            pendingByBox[pending.boxName]?.remove(pending)
            stateDirty = true

            val box = urb.boxes.get(pending.boxName) ?: continue
            val world = chunk.world

            if (!urb.regions.isAllowed(box, Location(world, pending.x.toDouble(), 0.0, pending.z.toDouble()))) {
                announceDespawn(box, BlockKey(pending.world, pending.x, 0, pending.z), unknownY = true)
                continue
            }

            val y = pending.forcedY ?: urb.finder.findY(world, pending.x, pending.z, box, rng)
            if (y == null) {
                // Announced but unplaceable - retract it so players are not sent to a ghost.
                announceDespawn(box, BlockKey(pending.world, pending.x, 0, pending.z), unknownY = true)
                continue
            }

            val placed = place(box, world, pending.x, y, pending.z, pending.replaceBlock)
            if (placed == null) {
                announceDespawn(box, BlockKey(pending.world, pending.x, y, pending.z))
                continue
            }
            if (urb.config.despawnTimerStart == DespawnTimerStart.ON_ANNOUNCE && box.despawnSeconds > 0) {
                placed.expiresAt = pending.announcedAt + box.despawnSeconds * 1000L
            }
        }
        if (pendingByBox.isNotEmpty()) pendingByBox.values.removeIf { it.isEmpty() }
    }

    // --- announcements ---------------------------------------------------------

    /**
     * @param manual true for `/urb spawnnow`, which is an admin placing a box by hand and is
     *   silent unless the box opts in - the broadcast is meant for scheduled airdrops.
     */
    private fun announceSpawn(box: RandomBox, world: String, x: Int, y: Int?, z: Int, manual: Boolean = false) {
        val ph = Ph.of().box(box.displayName).location(world, x, y, z)
        val body = box.spawnMessage ?: urb.config.defaults.spawnMessage
        val allowed = if (manual) box.broadcastManualSpawn else box.broadcastSpawn

        if (allowed && body.isNotBlank()) {
            val prefix = urb.messages.raw(kr.inmc.core.config.MessageCatalog.PREFIX)
            // The button is appended rather than woven into the configured text, so an admin
            // can rewrite the message freely without having to remember the click tag.
            val button = trackButton(box, world, x, z)
            for (player in Bukkit.getOnlinePlayers()) {
                player.sendMessage(Text.render(prefix + body + button, ph, player))
            }
        }
        val discord = urb.config.discord
        if (allowed && box.discordAlert && discord.spawnEnabled) {
            urb.discord.enqueue(discord.spawnUrl(box.discordSpawnWebhook), Text.plain(discord.spawnMessage, ph))
        }
    }

    /**
     * The clickable "추적하기" suffix.
     *
     * Empty for a box that cannot be tracked - offering a button that then refuses is worse
     * than offering nothing - and empty when the server turns it off in config.yml.
     */
    private fun trackButton(box: RandomBox, world: String, x: Int, z: Int): String {
        if (!urb.config.spawnTrackButton || !box.trackable) return ""
        val template = urb.messages.raw("spawn-track-button")
        if (template.isBlank()) return ""
        return template
            .replace("{command}", "/urb trackto $world $x $z")
            .replace("{world}", world)
            .replace("{x}", x.toString())
            .replace("{z}", z.toString())
    }

    private fun announceDespawn(box: RandomBox, key: BlockKey, unknownY: Boolean = false) {
        val ph = Ph.of().box(box.displayName)
            .location(key.world, key.x, if (unknownY) null else key.y, key.z)
        val body = box.despawnMessage ?: urb.config.defaults.despawnMessage

        if (box.broadcastDespawn && body.isNotBlank()) {
            val prefix = urb.messages.raw(kr.inmc.core.config.MessageCatalog.PREFIX)
            for (player in Bukkit.getOnlinePlayers()) {
                player.sendMessage(Text.render(prefix + body, ph, player))
            }
        }
        val discord = urb.config.discord
        if (box.discordAlert && discord.despawnEnabled) {
            urb.discord.enqueue(
                discord.despawnUrl(box.discordSpawnWebhook),
                Text.plain(discord.despawnMessage, ph),
            )
        }
    }

    // --- persistence -----------------------------------------------------------

    fun markDirty() {
        stateDirty = true
    }

    fun flushState() {
        if (!stateDirty) return
        stateDirty = false
        val text = buildStateYaml()
        urb.io.asyncRun { urb.io.file("data", "state.yml").also { it.parentFile?.mkdirs() }.writeText(text, Charsets.UTF_8) }
    }

    fun flushStateBlocking() {
        val text = buildStateYaml()
        val file = urb.io.file("data", "state.yml")
        file.parentFile?.mkdirs()
        runCatching { file.writeText(text, Charsets.UTF_8) }
            .onFailure { urb.logger.severe("상태 저장 실패: ${it.message}") }
        stateDirty = false
    }

    private fun buildStateYaml(): String {
        val config = YamlConfiguration()
        config.set("spawned", byBlock.values.map { spawned ->
            mapOf(
                "id" to spawned.id,
                "box" to spawned.boxName,
                "at" to spawned.key.toString(),
                "original" to spawned.originalBlockData,
                "placed" to spawned.placedMaterial?.name,
                "spawned-at" to spawned.spawnedAt,
                "expires-at" to spawned.expiresAt,
            )
        })
        config.set("pending", pendingByChunk.values.flatten().map { pending ->
            mapOf(
                "id" to pending.id,
                "box" to pending.boxName,
                "world" to pending.world,
                "x" to pending.x,
                "z" to pending.z,
                "y" to pending.forcedY,
                "replace" to pending.replaceBlock,
                "announced-at" to pending.announcedAt,
                "expires-at" to pending.expiresAt,
            )
        })
        config.set("restore", restoreByChunk.values.flatten().map { entry ->
            mapOf(
                "at" to entry.key.toString(),
                "data" to entry.blockData,
                "material" to entry.boxMaterial?.name,
                "ref" to entry.boxRef?.serialize(),
            )
        })
        return config.saveToString()
    }

    /** Reads persisted state off-thread and rebuilds the indexes on the main thread. */
    fun loadState(then: () -> Unit) {
        urb.io.async({
            val file = urb.io.file("data", "state.yml")
            if (file.exists()) urb.io.load(file) else YamlConfiguration()
        }) { config ->
            byBlock.clear(); byChunk.clear(); byBox.clear()
            pendingByChunk.clear(); pendingByBox.clear(); restoreByChunk.clear()

            for (entry in config.getMapList("spawned")) {
                val key = BlockKey.parse(entry["at"] as? String ?: continue) ?: continue
                val spawned = SpawnedBox(
                    id = entry["id"] as? String ?: "",
                    boxName = entry["box"] as? String ?: continue,
                    key = key,
                    originalBlockData = entry["original"] as? String ?: "minecraft:air",
                    // Absent in files written before this was recorded - stays null, and the
                    // restore falls back to the configured block exactly as it used to.
                    placedMaterial = (entry["placed"] as? String)?.let { Material.matchMaterial(it) },
                    spawnedAt = (entry["spawned-at"] as? Number)?.toLong() ?: 0L,
                    expiresAt = (entry["expires-at"] as? Number)?.toLong() ?: 0L,
                )
                index(spawned)
            }

            for (entry in config.getMapList("pending")) {
                val pending = PendingSpawn(
                    id = entry["id"] as? String ?: "",
                    boxName = entry["box"] as? String ?: continue,
                    world = entry["world"] as? String ?: continue,
                    x = (entry["x"] as? Number)?.toInt() ?: continue,
                    z = (entry["z"] as? Number)?.toInt() ?: continue,
                    forcedY = (entry["y"] as? Number)?.toInt(),
                    replaceBlock = entry["replace"] as? Boolean ?: false,
                    announcedAt = (entry["announced-at"] as? Number)?.toLong() ?: 0L,
                    expiresAt = (entry["expires-at"] as? Number)?.toLong() ?: 0L,
                )
                pendingByChunk.computeIfAbsent(pending.chunkKey) { synchronizedList() }.add(pending)
                pendingByBox.computeIfAbsent(pending.boxName) { synchronizedList() }.add(pending)
            }

            for (entry in config.getMapList("restore")) {
                val key = BlockKey.parse(entry["at"] as? String ?: continue) ?: continue
                val material = (entry["material"] as? String)?.let { Material.matchMaterial(it) }
                val ref = (entry["ref"] as? String)?.let { kr.inmc.core.item.BlockRef.parse(it) }
                restoreByChunk.computeIfAbsent(key.chunkKey) { synchronizedList() }
                    .add(RestoreEntry(key, entry["data"] as? String ?: "minecraft:air", material, ref))
            }

            urb.logger.info("상자 상태 복원 완료 - 배치 ${byBlock.size}개, 대기 ${totalPending()}개")
            stateDirty = false
            then()
        }
    }

    /** Restores every box immediately; only used when `shutdown.cleanup-all` is on. */
    fun removeAllForShutdown() {
        var deferred = 0
        for (spawned in byBlock.values.toList()) {
            val world = Bukkit.getWorld(spawned.key.world) ?: continue
            val material = expectedMaterial(spawned)
            val ref = urb.boxes.get(spawned.boxName)?.block
            if (world.isChunkLoaded(spawned.key.x shr 4, spawned.key.z shr 4)) {
                applyRestore(world, spawned.key, spawned.originalBlockData, material, ref)
            } else {
                // Still no force-loading, even on shutdown. Keep the restore so the block is
                // put back the next time that chunk loads instead of being orphaned.
                restoreByChunk.computeIfAbsent(spawned.chunkKey) { synchronizedList() }.add(
                    RestoreEntry(spawned.key, spawned.originalBlockData, material, ref)
                )
                deferred++
            }
        }
        byBlock.clear(); byChunk.clear(); byBox.clear()
        pendingByChunk.clear(); pendingByBox.clear()
        if (deferred > 0) {
            urb.logger.info("청크가 로드되지 않은 상자 ${deferred}개는 다음 청크 로드 시 복원됩니다")
        }
        stateDirty = true
    }

    private fun <T> synchronizedList(): MutableList<T> = Collections.synchronizedList(ArrayList())
}
