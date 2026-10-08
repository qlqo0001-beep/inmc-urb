package com.inmc.urb.command

import com.inmc.urb.Urb
import com.inmc.urb.box.SpawnOutcome
import com.inmc.urb.gui.BoxListMenu
import com.inmc.urb.gui.NoticeMenu
import com.inmc.urb.gui.OpenLogMenu
import com.inmc.urb.gui.PreviewMenu
import com.inmc.urb.gui.RankingMenu
import com.inmc.urb.gui.SimulationLauncher
import com.inmc.urb.gui.StatsMenu
import com.inmc.urb.gui.TrackMenu
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.util.Durations
import com.inmc.urb.util.Ph
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * `/urb`, registered through Paper's Brigadier API so tab completion and argument validation
 * come for free.
 *
 * Permissions follow the spec: exactly one admin node, `urb.admin`. The player-facing
 * subcommands (`info`, `search`, `track`, `notice`) need no permission at all.
 */
class UrbCommand(private val urb: Urb) {

    fun register(plugin: JavaPlugin) {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(tree().build(), "INMC 랜덤박스", listOf("randombox", "랜덤박스"))
        }
    }

    // --- suggestions -----------------------------------------------------------

    private val boxNames = SuggestionProvider<CommandSourceStack> { _, builder ->
        urb.boxes.names()
            .filter { it.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    private val previewableBoxes = SuggestionProvider<CommandSourceStack> { _, builder ->
        urb.boxes.all()
            .filter { it.previewEnabled && it.name.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it.name) }
        builder.buildFuture()
    }

    // --- tree ------------------------------------------------------------------

    private fun tree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("urb")
            .executes { ctx -> root(ctx.source.sender) }

            // --- admin ---
            .then(
                Commands.literal("create").requires(::isAdmin)
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString())
                            .executes { ctx -> create(ctx.source.sender, ctx.arg("name")) }
                    )
            )
            .then(
                Commands.literal("remove").requires(::isAdmin)
                    .then(Commands.literal("all").executes { ctx -> removeAll(ctx.source.sender) })
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString()).suggests(boxNames)
                            .executes { ctx -> remove(ctx.source.sender, ctx.arg("name")) }
                    )
            )
            .then(
                Commands.literal("spawn").requires(::isAdmin)
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString()).suggests(boxNames)
                            .executes { ctx -> spawn(ctx.source.sender, ctx.arg("name")) }
                    )
            )
            .then(
                Commands.literal("spawnnow").requires(::isAdmin)
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString()).suggests(boxNames)
                            .executes { ctx -> spawnNow(ctx.source.sender, ctx.arg("name")) }
                    )
            )
            .then(
                Commands.literal("open").requires(::isAdmin)
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString()).suggests(boxNames)
                            .executes { ctx -> open(ctx.source.sender, ctx.arg("name")) }
                    )
            )
            .then(
                Commands.literal("reset").requires(::isAdmin)
                    .then(Commands.literal("all").executes { ctx -> reset(ctx.source.sender, null) })
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString()).suggests(boxNames)
                            .executes { ctx -> reset(ctx.source.sender, ctx.arg("name")) }
                    )
            )
            .then(
                Commands.literal("simulate").requires(::isAdmin)
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString()).suggests(boxNames)
                            .executes { ctx -> simulate(ctx.source.sender, ctx.arg("name")) }
                    )
            )
            .then(
                Commands.literal("testarea").requires(::isAdmin)
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString()).suggests(boxNames)
                            .executes { ctx -> testArea(ctx.source.sender, ctx.arg("name")) }
                    )
            )
            .then(
                Commands.literal("copy").requires(::isAdmin)
                    .then(
                        Commands.argument("names", StringArgumentType.greedyString()).suggests(boxNames)
                            .executes { ctx -> copy(ctx.source.sender, ctx.arg("names")) }
                    )
            )
            .then(
                Commands.literal("stats").requires(::isAdmin)
                    .executes { ctx -> stats(ctx.source.sender, null) }
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString()).suggests(boxNames)
                            .executes { ctx -> stats(ctx.source.sender, ctx.arg("name")) }
                    )
            )
            .then(Commands.literal("log").requires(::isAdmin).executes { ctx -> log(ctx.source.sender) })
            .then(Commands.literal("reload").requires(::isAdmin).executes { ctx -> reload(ctx.source.sender) })
            // 상자 없이 떠 있는 우리 홀로그램 치우기(관리자 도구, 2026-10-08).
            .then(Commands.literal("cleanup").requires(::isAdmin).executes { ctx ->
                urb.messages.send(ctx.source.sender, "holograms-swept", Ph.of().count(urb.visuals.sweepStray()))
                SUCCESS
            })
            // 서버 안 자동 검증(2026-10-08) — 정의·추첨기·조건·소환·홀로그램·진입점·화면.
            .then(Commands.literal("verify").requires(::isAdmin).executes { ctx ->
                (ctx.source.sender as? org.bukkit.entity.Player)?.let { com.inmc.urb.verify.Verifier(urb).run(it) }
                    ?: urb.messages.send(ctx.source.sender, "player-only")
                SUCCESS
            })

            // --- everyone ---
            .then(
                Commands.literal("info")
                    .executes { ctx -> info(ctx.source.sender, null) }
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString()).suggests(previewableBoxes)
                            .executes { ctx -> info(ctx.source.sender, ctx.arg("name")) }
                    )
            )
            .then(Commands.literal("search").executes { ctx -> search(ctx.source.sender) })
            .then(Commands.literal("track").executes { ctx -> search(ctx.source.sender) })
            .then(Commands.literal("untrack").executes { ctx -> untrack(ctx.source.sender) })
            .then(Commands.literal("notice").executes { ctx -> notice(ctx.source.sender) })
            .then(Commands.literal("ranking").executes { ctx -> ranking(ctx.source.sender) })
            // Not advertised in the usage text: this is what the spawn broadcast's
            // "[추적하기]" button runs, with the coordinates already filled in.
            .then(
                Commands.literal("trackto")
                    .then(
                        Commands.argument("target", StringArgumentType.greedyString())
                            .executes { ctx -> trackTo(ctx.source.sender, ctx.arg("target")) }
                    )
            )

    /**
     * Box names are read with `greedyString`, not `word` or `string`.
     *
     * Both of those only accept Brigadier's "allowed in unquoted string" set - ASCII letters,
     * digits and a few symbols - so a Korean box name failed with "Expected whitespace to end
     * one argument". greedyString takes the rest of the line verbatim, which is safe here
     * because the name is always the final argument.
     */
    private fun CommandContext<CommandSourceStack>.arg(name: String): String =
        StringArgumentType.getString(this, name).trim()

    private fun isAdmin(source: CommandSourceStack): Boolean = source.sender.hasPermission(PERMISSION)

    // --- handlers --------------------------------------------------------------

    private fun root(sender: CommandSender): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player
        if (player == null) {
            urb.messages.send(sender, "usage")
            return SUCCESS
        }
        if (player.hasPermission(PERMISSION)) {
            BoxListMenu(urb).open(player)
        } else {
            urb.messages.send(player, "usage-player")
        }
        return SUCCESS
    }

    private fun create(sender: CommandSender, name: String): Int {
        if (!ready(sender)) return SUCCESS
        if (!com.inmc.urb.box.BoxRegistry.isValidName(name)) {
            urb.messages.send(sender, "box-invalid-name")
            return SUCCESS
        }
        if (urb.boxes.exists(name)) {
            urb.messages.send(sender, "box-exists", Ph.of().box(name))
            return SUCCESS
        }
        urb.boxes.create(name, urb.config.defaults)
        urb.messages.send(sender, "box-created", Ph.of().box(name))
        return SUCCESS
    }

    private fun remove(sender: CommandSender, name: String): Int {
        if (!ready(sender)) return SUCCESS
        val box = urb.boxes.get(name) ?: return unknown(sender, name)
        urb.spawns.reset(box.name)
        urb.boxes.delete(box.name)
        urb.messages.send(sender, "box-deleted", Ph.of().box(box.displayName))
        return SUCCESS
    }

    /** Spec §25: warn first, delete only after a click in the confirmation window. */
    private fun removeAll(sender: CommandSender): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player
        if (player == null) {
            val count = urb.boxes.size
            urb.spawns.reset(null)
            urb.boxes.deleteAll()
            urb.messages.send(sender, "box-deleted-all", Ph.of().count(count))
            return SUCCESS
        }
        ConfirmMenu(
            urb,
            question = "<yellow>⚠ 확인이 필요합니다</yellow>",
            title = "<dark_red>정말 모든 상자를 삭제할까요?</dark_red>",
            detail = listOf(
                "<red>등록된 상자 <white>${urb.boxes.size}</white>개가 전부 삭제됩니다.</red>",
                "<red>이 작업은 되돌릴 수 없습니다.</red>",
            ),
            onConfirm = {
                val count = urb.boxes.size
                urb.spawns.reset(null)
                urb.boxes.deleteAll()
                urb.messages.send(player, "box-deleted-all", Ph.of().count(count))
                player.closeInventory()
            },
            onCancel = { player.closeInventory() },
        ).open(player)
        return SUCCESS
    }

    private fun spawn(sender: CommandSender, name: String): Int {
        if (!ready(sender)) return SUCCESS
        val box = urb.boxes.get(name) ?: return unknown(sender, name)
        report(sender, box.displayName, box.maxSpawnCount, urb.spawns.requestSpawn(box))
        return SUCCESS
    }

    private fun spawnNow(sender: CommandSender, name: String): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player ?: run {
            urb.messages.send(sender, "player-only")
            return SUCCESS
        }
        val box = urb.boxes.get(name) ?: return unknown(sender, name)
        val outcome = urb.spawns.spawnExact(box, player.location)
        if (outcome == SpawnOutcome.PLACED) {
            urb.messages.send(
                sender, "spawn-success",
                Ph.of().box(box.displayName).location(player.location),
            )
        } else {
            report(sender, box.displayName, box.maxSpawnCount, outcome)
        }
        return SUCCESS
    }

    private fun open(sender: CommandSender, name: String): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player ?: run {
            urb.messages.send(sender, "player-only")
            return SUCCESS
        }
        val box = urb.boxes.get(name) ?: return unknown(sender, name)
        urb.opens.openViaCommand(player, box)
        return SUCCESS
    }

    private fun reset(sender: CommandSender, name: String?): Int {
        if (!ready(sender)) return SUCCESS
        val boxName = name?.let { urb.boxes.get(it)?.name ?: return unknown(sender, it) }
        val removed = urb.spawns.reset(boxName)
        urb.messages.send(sender, "reset-done", Ph.of().count(removed))
        return SUCCESS
    }

    /**
     * Shortcut into the simulator. The results are a GUI, so this needs a player - from the
     * console there is nowhere to draw them.
     */
    private fun simulate(sender: CommandSender, name: String): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player ?: run {
            urb.messages.send(sender, "player-only")
            return SUCCESS
        }
        val box = urb.boxes.get(name) ?: return unknown(sender, name)
        SimulationLauncher(urb).prompt(player, box)
        return SUCCESS
    }

    /**
     * Dry-run of the box's own placement rules.
     *
     * Samples candidate positions and reports why each was rejected, without placing anything
     * and without loading a single chunk - an area that turns out to be all ocean or all
     * protected region shows up here instead of as silently missing airdrops.
     */
    private fun testArea(sender: CommandSender, name: String): Int {
        if (!ready(sender)) return SUCCESS
        val box = urb.boxes.get(name) ?: return unknown(sender, name)
        val world = Bukkit.getWorld(box.world) ?: run {
            urb.messages.send(sender, "testarea-no-world", Ph.of().raw("{world}", box.world))
            return SUCCESS
        }

        val points: List<IntArray> = when (box.spawnMode) {
            com.inmc.urb.box.SpawnMode.RANDOM_AREA -> {
                if (!box.area.isConfigured) {
                    urb.messages.send(sender, "testarea-no-area", Ph.of().box(box.displayName))
                    return SUCCESS
                }
                (1..SAMPLES).mapNotNull { box.area.randomPoint(rng) }
            }

            else -> {
                if (box.fixedPoints.isEmpty()) {
                    urb.messages.send(sender, "testarea-no-points", Ph.of().box(box.displayName))
                    return SUCCESS
                }
                box.fixedPoints.map { intArrayOf(it.x, it.z) }
            }
        }

        var unloaded = 0
        var blockedByRegion = 0
        var noGround = 0
        var placeable = 0

        for (point in points) {
            val x = point[0]
            val z = point[1]
            if (!world.isChunkLoaded(x shr 4, z shr 4)) {
                unloaded++
                continue
            }
            if (!urb.regions.isAllowed(box, org.bukkit.Location(world, x.toDouble(), 0.0, z.toDouble()))) {
                blockedByRegion++
                continue
            }
            // Fixed points clear whatever is in the way, so only the random mode needs a
            // surface search; a fixed point that is inside the world is always usable.
            if (box.spawnMode == com.inmc.urb.box.SpawnMode.RANDOM_AREA) {
                if (urb.finder.findY(world, x, z, box, rng) == null) noGround++ else placeable++
            } else {
                placeable++
            }
        }

        val tested = points.size
        val loaded = tested - unloaded
        val ph = Ph.of().box(box.displayName)

        urb.messages.send(sender, "testarea-header", ph.copy().count(tested))
        urb.messages.sendRaw(
            sender,
            "<gray>· 청크 로드됨: <white>" + loaded + "</white> / 미로드: <white>" + unloaded + "</white>" +
                " <dark_gray>(미로드는 대기열로 들어갑니다)</dark_gray></gray>",
        )
        if (loaded > 0) {
            val rate = placeable * 100.0 / loaded
            urb.messages.sendRaw(
                sender,
                "<gray>· 배치 가능: <white>" + placeable + "</white> <white>(" +
                    kr.inmc.core.util.Numbers.chance(rate) + "%)</white></gray>",
            )
            if (blockedByRegion > 0) {
                urb.messages.sendRaw(sender, "<gray>· 보호구역에 막힘: <yellow>" + blockedByRegion + "</yellow></gray>")
            }
            if (noGround > 0) {
                urb.messages.sendRaw(
                    sender,
                    "<gray>· 지면 없음(물·용암·동굴 등): <yellow>" + noGround + "</yellow></gray>",
                )
            }
            when {
                rate < 10.0 -> urb.messages.send(sender, "testarea-bad")
                rate < 50.0 -> urb.messages.send(sender, "testarea-poor")
                else -> urb.messages.send(sender, "testarea-good")
            }
        } else {
            urb.messages.send(sender, "testarea-unloaded")
        }
        return SUCCESS
    }

    /**
     * `/urb copy <원본> <새이름>`.
     *
     * One greedy argument split by hand rather than two Brigadier arguments: an unquoted
     * Brigadier string argument rejects Hangul, so a Korean box name can only ever arrive as
     * the greedy tail. Box names cannot contain whitespace, so splitting on it is unambiguous.
     */
    private fun copy(sender: CommandSender, raw: String): Int {
        if (!ready(sender)) return SUCCESS
        val parts = raw.split(WHITESPACE).filter { it.isNotEmpty() }
        if (parts.size != 2) {
            urb.messages.send(sender, "copy-usage")
            return SUCCESS
        }
        val source = urb.boxes.get(parts[0]) ?: return unknown(sender, parts[0])
        val target = parts[1]

        if (!com.inmc.urb.box.BoxRegistry.isValidName(target)) {
            urb.messages.send(sender, "box-invalid-name")
            return SUCCESS
        }
        if (urb.boxes.exists(target)) {
            urb.messages.send(sender, "box-exists", Ph.of().box(target))
            return SUCCESS
        }
        if (urb.boxes.copy(source, target, urb.config.defaults) == null) {
            urb.messages.send(sender, "copy-failed")
            return SUCCESS
        }
        urb.messages.send(
            sender, "box-copied",
            Ph.of().box(source.displayName).raw("{새이름}", target),
        )
        return SUCCESS
    }

    private fun stats(sender: CommandSender, name: String?): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player ?: run {
            urb.messages.send(sender, "player-only")
            return SUCCESS
        }
        if (name == null) {
            OpenLogMenu(urb).open(player)
            return SUCCESS
        }
        val box = urb.boxes.get(name) ?: return unknown(sender, name)
        StatsMenu(urb, box).open(player)
        return SUCCESS
    }

    private fun log(sender: CommandSender): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player ?: run {
            urb.messages.send(sender, "player-only")
            return SUCCESS
        }
        OpenLogMenu(urb).open(player)
        return SUCCESS
    }

    private fun ranking(sender: CommandSender): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player ?: run {
            urb.messages.send(sender, "player-only")
            return SUCCESS
        }
        RankingMenu(urb, player).open(player)
        return SUCCESS
    }

    /** `/urb trackto <world> <x> <z>` - the target of the spawn broadcast click button. */
    private fun trackTo(sender: CommandSender, raw: String): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player ?: run {
            urb.messages.send(sender, "player-only")
            return SUCCESS
        }
        val parts = raw.split(WHITESPACE).filter { it.isNotEmpty() }
        val x = parts.getOrNull(1)?.toIntOrNull()
        val z = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size < 3 || x == null || z == null) {
            urb.messages.send(sender, "track-gone")
            return SUCCESS
        }
        urb.tracking.trackAt(player, parts[0], x, z)
        return SUCCESS
    }

    private fun reload(sender: CommandSender): Int {
        urb.messages.send(sender, "reloading")
        urb.reload { count -> urb.messages.send(sender, "reloaded", Ph.of().count(count)) }
        return SUCCESS
    }

    private fun info(sender: CommandSender, name: String?): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player ?: run {
            urb.messages.send(sender, "player-only")
            return SUCCESS
        }
        if (name == null) {
            PreviewMenu.chooser(urb, player).open(player)
            return SUCCESS
        }
        val box = urb.boxes.get(name) ?: return unknown(sender, name)
        if (!box.previewEnabled && !player.hasPermission(PERMISSION)) {
            urb.messages.send(sender, "preview-disabled")
            return SUCCESS
        }
        PreviewMenu(urb, box, player.hasPermission(PERMISSION)).open(player)
        return SUCCESS
    }

    private fun search(sender: CommandSender): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player ?: run {
            urb.messages.send(sender, "player-only")
            return SUCCESS
        }
        urb.tracking.searchCooldown(player)?.let { remaining ->
            urb.messages.send(player, "search-cooldown", Ph.of().time(Durations.formatShort(remaining)))
            return SUCCESS
        }
        val sightings = urb.spawns.sightings(urb.config.searchShowPending, player.location)
        if (sightings.isEmpty()) {
            urb.messages.send(player, "search-empty")
            return SUCCESS
        }
        urb.tracking.markSearched(player)
        TrackMenu(urb, player, sightings).open(player)
        return SUCCESS
    }

    private fun untrack(sender: CommandSender): Int {
        val player = sender as? Player ?: return SUCCESS
        urb.tracking.clear(player)
        return SUCCESS
    }

    private fun notice(sender: CommandSender): Int {
        if (!ready(sender)) return SUCCESS
        val player = sender as? Player ?: run {
            urb.messages.send(sender, "player-only")
            return SUCCESS
        }
        NoticeMenu(urb).open(player)
        return SUCCESS
    }

    // --- helpers ---------------------------------------------------------------

    private fun ready(sender: CommandSender): Boolean {
        if (urb.ready) return true
        urb.messages.send(sender, "not-ready")
        return false
    }

    private fun unknown(sender: CommandSender, name: String): Int {
        urb.messages.send(sender, "unknown-box", Ph.of().box(name))
        return SUCCESS
    }

    private fun report(sender: CommandSender, displayName: String, maxCount: Int, outcome: SpawnOutcome) {
        when (outcome) {
            SpawnOutcome.PLACED -> Unit // the spawn broadcast already covers it
            SpawnOutcome.QUEUED -> urb.messages.send(sender, "spawn-queued")
            SpawnOutcome.LIMIT ->
                urb.messages.send(sender, "spawn-limit", Ph.of().box(displayName).count(maxCount))
            SpawnOutcome.DISABLED -> urb.messages.send(sender, "box-disabled", Ph.of().box(displayName))
            SpawnOutcome.NO_WORLD, SpawnOutcome.NO_LOCATION, SpawnOutcome.NO_POINTS ->
                urb.messages.send(sender, "spawn-failed")
        }
    }

    /** Only used by `/urb testarea`, which must not disturb the live spawn sequence. */
    private val rng = java.util.Random()

    companion object {
        const val PERMISSION = "urb.admin"
        private const val SUCCESS = Command.SINGLE_SUCCESS

        /** Enough samples for a percentage to mean something without stalling the tick. */
        private const val SAMPLES = 200

        private val WHITESPACE = Regex("""\s+""")
    }
}
