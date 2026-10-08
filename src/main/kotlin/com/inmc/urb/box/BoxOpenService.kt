package com.inmc.urb.box

import com.inmc.urb.Urb
import com.inmc.urb.gui.LootMenu
import com.inmc.urb.gui.OpenShowMenu
import com.inmc.urb.util.BlockKey
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Numbers
import com.inmc.urb.util.Ph
import kr.inmc.core.util.Text
import com.inmc.urb.visual.OpenAnimation
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Duration
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** How an open was triggered - decides whether it announces and what it consumes. */
enum class OpenSource { BLOCK, CAPSULE, COMMAND }

/** One in-progress open. Ticked once a second; cancelled by movement. */
class OpenSession(
    val playerId: UUID,
    val boxName: String,
    val blockKey: BlockKey,
    val origin: Location,
    val totalSeconds: Int,
) {
    var elapsed: Int = 0
}

/**
 * Everything that happens between a player interacting with a box and the loot landing.
 *
 * Conditions are checked before the progress bar starts and again when it finishes, but only
 * *consumed* on success - a cancelled open must not eat the key or the money.
 */
class BoxOpenService(private val urb: Urb) {

    private val rng = Random()
    private val sessions = ConcurrentHashMap<UUID, OpenSession>()

    /** "<uuid>:<box>" -> epoch millis the player may retry at. */
    private val cooldowns = ConcurrentHashMap<String, Long>()

    /** Player -> the hand-over their reveal animation still owes them. */
    private val pendingDeliveries = ConcurrentHashMap<UUID, () -> Unit>()

    fun hasSession(playerId: UUID): Boolean = sessions.containsKey(playerId)

    fun session(playerId: UUID): OpenSession? = sessions[playerId]

    // --- entry points ----------------------------------------------------------

    /** 고정 좌표(FIXED_POINTS·PERMANENT) 상자의 블록 오픈은 위치마다 따로 센다. 랜덤 영역·캡슐·명령은 상자 단위. */
    private fun scopeAt(box: RandomBox, key: com.inmc.urb.util.BlockKey): com.inmc.urb.util.BlockKey? =
        if (box.spawnMode == SpawnMode.RANDOM_AREA) null else key

    /** Right-clicking a spawned box block. */
    fun beginBlockOpen(player: Player, spawned: SpawnedBox, box: RandomBox) {
        if (!box.enabled) {
            urb.messages.send(player, "box-disabled", Ph.of().box(box.displayName))
            return
        }
        if (spawned.opened) {
            urb.messages.send(player, "open-taken")
            return
        }
        if (sessions.containsKey(player.uniqueId)) {
            urb.messages.send(player, "open-busy")
            return
        }
        val at = scopeAt(box, spawned.key)
        remainingCooldown(player, box)?.let { seconds ->
            urb.messages.send(player, "open-cooldown", Ph.of().time(Durations.formatShort(seconds)))
            return
        }
        if (!checkConditions(player, box, at)) return

        if (box.openTimeSeconds <= 0) {
            spawned.opened = true
            complete(player, box, spawned)
            return
        }

        spawned.opened = true
        val session = OpenSession(
            playerId = player.uniqueId,
            boxName = box.name,
            blockKey = spawned.key,
            origin = player.location.clone(),
            totalSeconds = box.openTimeSeconds,
        )
        sessions[player.uniqueId] = session
        showProgress(player, box, session)
    }

    /** Right-clicking a capsule item (spec §75). */
    fun openViaCapsule(player: Player, box: RandomBox, consumeCapsule: () -> Unit): Boolean {
        if (!box.enabled) {
            urb.messages.send(player, "box-disabled", Ph.of().box(box.displayName))
            return false
        }
        if (!checkConditions(player, box)) return false
        if (!consumeConditions(player, box)) return false
        consumeCapsule()
        deliver(player, box, player.location, announceOpen = box.broadcastCapsuleOpen)
        return true
    }

    /**
     * `/urb open <name>` - spec §31-33: the key and the money are still consumed, but no open
     * message is broadcast.
     */
    fun openViaCommand(player: Player, box: RandomBox): Boolean {
        if (!box.enabled) {
            urb.messages.send(player, "box-disabled", Ph.of().box(box.displayName))
            return false
        }
        if (!checkConditions(player, box)) return false
        if (!consumeConditions(player, box)) return false
        deliver(player, box, player.location, announceOpen = false)
        return true
    }

    // --- progress --------------------------------------------------------------

    /** Driven by the ticker: advances every live session by one second. */
    fun tick() {
        if (sessions.isEmpty()) return
        for (session in sessions.values.toList()) {
            val player = Bukkit.getPlayer(session.playerId)
            if (player == null || !player.isOnline) {
                abandon(session)
                continue
            }
            val box = urb.boxes.get(session.boxName)
            if (box == null) {
                abandon(session)
                continue
            }

            session.elapsed++
            if (session.elapsed < session.totalSeconds) {
                showProgress(player, box, session)
                continue
            }

            sessions.remove(session.playerId)
            val spawned = urb.spawns.at(session.blockKey)
            if (spawned == null) {
                urb.messages.send(player, "open-taken")
                continue
            }
            showProgress(player, box, session)
            complete(player, box, spawned)
        }
    }

    private fun showProgress(player: Player, box: RandomBox, session: OpenSession) {
        val done = session.elapsed.coerceIn(0, session.totalSeconds)
        val filled = urb.messages.raw("open-progress-filled")
        val empty = urb.messages.raw("open-progress-empty")

        val bar = buildString {
            repeat(done) { append(filled).append(' ') }
            repeat(session.totalSeconds - done) { append(empty).append(' ') }
        }.trim()

        val headerKey = if (done >= session.totalSeconds) "open-done-title" else "open-progress-title"
        player.showTitle(
            Title.title(
                urb.messages.component(headerKey, Ph.of().box(box.displayName), player),
                Text.render(bar, null, player),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1400), Duration.ofMillis(200)),
            )
        )
    }

    /** Movement cancels the open (spec §111) and starts the reopen cooldown. */
    fun cancel(playerId: UUID, notify: Boolean) {
        val session = sessions.remove(playerId) ?: return
        releaseReservation(session)

        val player = Bukkit.getPlayer(playerId) ?: return
        val box = urb.boxes.get(session.boxName)
        if (box != null && box.reopenCooldownSeconds > 0) {
            cooldowns[cooldownKey(playerId, box.name)] =
                System.currentTimeMillis() + box.reopenCooldownSeconds * 1000L
        }
        if (!notify) return
        player.showTitle(
            Title.title(
                urb.messages.component("open-cancel-title", null, player),
                Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(300)),
            )
        )
        urb.messages.send(player, "open-cancelled")
    }

    fun cancelAll() {
        sessions.keys.toList().forEach { cancel(it, notify = false) }
        flushPendingDeliveries()
    }

    private fun abandon(session: OpenSession) {
        sessions.remove(session.playerId)
        releaseReservation(session)
    }

    private fun releaseReservation(session: OpenSession) {
        urb.spawns.at(session.blockKey)?.opened = false
    }

    private fun cooldownKey(playerId: UUID, boxName: String) = "$playerId:$boxName"

    private fun remainingCooldown(player: Player, box: RandomBox): Long? {
        val until = cooldowns[cooldownKey(player.uniqueId, box.name)] ?: return null
        val now = System.currentTimeMillis()
        if (until <= now) {
            cooldowns.remove(cooldownKey(player.uniqueId, box.name))
            return null
        }
        return ((until - now) / 1000L).coerceAtLeast(1L)
    }

    fun purgeCooldowns(now: Long) {
        cooldowns.entries.removeIf { it.value <= now }
    }

    // --- completion ------------------------------------------------------------

    private fun complete(player: Player, box: RandomBox, spawned: SpawnedBox) {
        // Re-check, because the world moved on while the bar was filling.
        val at = scopeAt(box, spawned.key)
        if (!checkConditions(player, box, at) || !consumeConditions(player, box)) {
            spawned.opened = false
            return
        }
        val loc = spawned.key.toLocation(player.world)
        if (box.isPermanent) {
            // Furniture: it stays, and the per-player limits are what stop repeat looting.
            spawned.opened = false
        } else {
            // A map-spawned box is gone once opened, whether or not the loot is taken (spec §72).
            urb.spawns.remove(spawned, announce = false, opener = player)
        }
        deliver(player, box, loc, announceOpen = box.broadcastOpen, scope = at)
    }

    // --- conditions ------------------------------------------------------------

    /** Checks without consuming. Sends the reason to the player when it fails. */
    fun checkConditions(player: Player, box: RandomBox, at: com.inmc.urb.util.BlockKey? = null): Boolean {
        // 열쇠·비용이 없어도 무료 오픈 상자로 열린다(테섭 2026-10-04). 1인 제한이 따로 걸려 있으면
        // 그쪽이 막는다 — 무제한 무료 고정 상자를 깔면 아이템 수도꼭지가 되니 주의.
        if (urb.openRecords.isExhausted(player.uniqueId, box, at)) {
            if (box.maxOpensPerPlayer == 1 && box.openLimitResetSeconds <= 0L) {
                urb.messages.send(player, "open-already-found", Ph.of().box(box.displayName))
            } else {
                val ph = Ph.of().box(box.displayName).count(box.maxOpensPerPlayer)
                // A cap that refills is worth saying so - "최대 3번" alone reads as permanent.
                val resetIn = urb.openRecords.resetIn(player.uniqueId, box, at)
                if (resetIn != null) {
                    urb.messages.send(player, "open-limit-reached-timed", ph.time(Durations.formatShort(resetIn)))
                } else {
                    urb.messages.send(player, "open-limit-reached", ph)
                }
            }
            return false
        }
        urb.openRecords.cooldownRemaining(player.uniqueId, box, at)?.let { seconds ->
            urb.messages.send(
                player, "open-personal-cooldown",
                Ph.of().box(box.displayName).time(Durations.formatShort(seconds)),
            )
            return false
        }

        // Checked here rather than in deliver(), so an empty loot table cannot eat the key
        // or the money on the way to finding out there is nothing to hand over.
        if (box.rewards.isEmpty()) {
            urb.messages.send(player, "box-no-rewards", Ph.of().box(box.displayName))
            return false
        }
        val key = box.keyItem
        if (key != null && !urb.itemMatcher.has(player, key) && box.keyLegacy?.let { urb.itemMatcher.has(player, it) } != true) {
            urb.messages.send(
                player, "key-required",
                Ph.of().box(box.displayName).key(key.label()),
            )
            return false
        }
        if (box.moneyCost > 0.0) {
            if (!urb.economy.isEnabled) {
                urb.messages.send(player, "economy-missing")
                return false
            }
            if (!urb.economy.has(player, box.moneyCost, box.currency)) {
                urb.messages.send(
                    player, "money-required",
                    Ph.of().box(box.displayName)
                        .money(Numbers.money(box.moneyCost))
                        .balance(Numbers.money(urb.economy.balance(player, box.currency))),
                )
                return false
            }
        }
        return true
    }

    /** Takes the key and the money. Refunds the key if the money withdrawal then fails. */
    private fun consumeConditions(player: Player, box: RandomBox): Boolean {
        val key = box.keyItem
        var keyTaken: ItemStack? = null

        if (key != null) {
            // 옮기기 전의 옛 열쇠도 받는다(커스텀아이템 연동, [UrbRoles]). 가방 먼저, 없으면 배낭(core CarriedStorage — 2026-09-30).
            keyTaken = urb.itemMatcher.takeOne(player, key) ?: box.keyLegacy?.let { urb.itemMatcher.takeOne(player, it) }
            if (keyTaken == null) {
                urb.messages.send(player, "key-required", Ph.of().box(box.displayName).key(key.label()))
                return false
            }
        }

        if (box.moneyCost > 0.0) {
            if (!urb.economy.withdraw(player, box.moneyCost, box.currency)) {
                keyTaken?.let { player.inventory.addItem(it) }
                urb.messages.send(
                    player, "money-required",
                    Ph.of().box(box.displayName)
                        .money(Numbers.money(box.moneyCost))
                        .balance(Numbers.money(urb.economy.balance(player, box.currency))),
                )
                return false
            }
            urb.messages.send(
                player, "money-consumed",
                Ph.of().box(box.displayName).money(Numbers.money(box.moneyCost)),
            )
        }

        if (key != null) {
            urb.messages.send(
                player, "key-consumed",
                Ph.of().box(box.displayName).key(key.label()),
            )
        }
        return true
    }

    // --- loot ------------------------------------------------------------------

    /**
     * Rolls the table and hands the result over, in whichever mode the box uses.
     *
     * The roll happens here and nowhere else, *before* any animation. Everything downstream -
     * the reveal window, the flourish, the commands, the broadcast - works from an already
     * decided result, so nothing a player does mid-animation can change what they get.
     */
    fun deliver(
        player: Player,
        box: RandomBox,
        at: Location?,
        announceOpen: Boolean,
        scope: com.inmc.urb.util.BlockKey? = null,
    ) {
        if (box.rewards.isEmpty()) {
            urb.messages.send(player, "box-no-rewards", Ph.of().box(box.displayName))
            return
        }

        // One selection per pool: a box with no tiers yields exactly one pool using the
        // box-level min/max, which is the pre-tier behaviour unchanged.
        val rolled = box.rollGroups().flatMap {
            LootRoller.select(it.entries, it.minRolls, it.maxRolls, rng)
        }

        val items = ArrayList<ItemStack>(rolled.size)
        val commands = ArrayList<String>()
        val amounts = ArrayList<Pair<Reward, Int>>(rolled.size)

        for (reward in rolled) {
            val amount = LootRoller.rollAmount(reward.minAmount, reward.maxAmount, rng)
            amounts.add(reward to amount)
            if (reward.giveItem) {
                urb.itemResolver.create(reward.item, amount)?.let { items.add(it) }
            }
            commands.addAll(reward.commands)
        }

        urb.openRecords.record(player.uniqueId, box, scope)
        urb.stats.record(player, box, amounts, at)

        // "이 사람이 이 상자를 열었다" 를 core 의 신호로 알린다. 듣는 쪽이 하나도 없으면
        // 이벤트 객체조차 만들지 않으므로 평소 비용이 0 이다. 이 플러그인은 누가 듣는지 모른다.
        kr.inmc.core.event.InmcSignalEvent.fire(
            source = "urb",
            type = "open",
            playerId = player.uniqueId,
            subject = box.name,
            player = player,
        ) {
            mapOf("box" to box.name, "rewards" to amounts.size.toString())
        }

        present(player, box, items, jackpot = rolled.any { it.announce }) {
            handOver(player, box, at, items, rolled, commands, announceOpen)
        }
    }

    /** Everything that happens once the loot is actually the player's. */
    private fun handOver(
        player: Player,
        box: RandomBox,
        at: Location?,
        items: List<ItemStack>,
        rolled: List<Reward>,
        commands: List<String>,
        announceOpen: Boolean,
    ) {
        if (player.isOnline) {
            when (box.effectiveOpenMode()) {
                OpenMode.DIRECT -> giveDirect(player, items)
                // A permanent box hands its loot over rather than dropping leftovers on the
                // floor, so the window is only ever a presentation layer for it.
                OpenMode.GUI -> {
                    // A shutdown flush can arrive after the server has begun tearing menus
                    // down. The loot is already the player's by then, so fall back to handing
                    // it over directly rather than letting the window failure eat it.
                    val shown = runCatching {
                        LootMenu(urb, box, items, returnToInventory = box.isPermanent).open(player)
                    }.isSuccess
                    if (!shown) giveDirect(player, items)
                }
                // Scatter where the box stood, not at the opener - the point of an airdrop is
                // that everyone who made it there gets a shot at the pile.
                OpenMode.DROP -> dropAt(at ?: player.location, items)
            }
            urb.visuals.playFlair(player, box.rewardFlair, at)
            // 본인에게는 무엇을 얻었는지 늘 알린다(잡템이어도 — 서버 공지와 따로, 테섭 2026-10-02).
            if (rolled.isNotEmpty()) {
                val got = rolled.joinToString("<gray>, </gray>") { "<white>${it.label()}</white>" }
                val key = if (box.effectiveOpenMode() == OpenMode.GUI) "reward-received-window" else "reward-received"
                urb.messages.send(player, key, Ph.of().box(box.displayName).item(got))
            }
        } else if (items.isNotEmpty()) {
            // Disconnected mid-animation: the loot was already theirs, so drop it where the
            // box stood rather than silently deleting it.
            dropAt(at ?: player.location, items)
        }

        for (reward in rolled) {
            if (reward.announce) announceReward(player, box, reward)
        }

        runCommands(player, box, at, commands)
        runCommands(player, box, at, box.openCommands)

        if (announceOpen) broadcastOpen(player, box, at)
    }

    /**
     * Runs the box's reveal animation, then [then].
     *
     * The callback is parked in [pendingDeliveries] for the duration so a shutdown, a reload or
     * a `/urb reset` can force it through - an animation must never be able to swallow loot.
     */
    private fun present(player: Player, box: RandomBox, items: List<ItemStack>, jackpot: Boolean, then: () -> Unit) {
        if (box.openAnimation == OpenAnimation.NONE || items.isEmpty() || !player.isOnline) {
            then()
            return
        }

        val id = player.uniqueId
        val once = AtomicBoolean(false)
        val guarded = {
            if (once.compareAndSet(false, true)) {
                pendingDeliveries.remove(id)
                then()
            }
        }
        pendingDeliveries[id] = guarded

        OpenShowMenu(urb, box, items, spinPool(box, items), box.openAnimation, jackpot, guarded).start(player)
    }

    /** The strip the roulette scrolls: every reward the box can produce, deduplicated by icon. */
    private fun spinPool(box: RandomBox, fallback: List<ItemStack>): List<ItemStack> {
        val pool = box.rewards.asSequence()
            .filter { it.giveItem }
            .map { urb.itemResolver.icon(it.item).stack }
            .take(SPIN_POOL_LIMIT)
            .toList()
        return if (pool.isEmpty()) fallback else pool
    }

    /** Forces every parked animation to hand its loot over. Called on shutdown and reload. */
    fun flushPendingDeliveries() {
        val parked = pendingDeliveries.values.toList()
        pendingDeliveries.clear()
        parked.forEach { runCatching { it() } }
    }

    private fun dropAt(location: Location, items: List<ItemStack>) {
        val world = location.world ?: return
        // Centre of the block so the pile does not clip into a wall.
        val origin = location.clone().add(0.5, 0.5, 0.5)
        for (item in items) world.dropItemNaturally(origin, item)
    }

    private fun giveDirect(player: Player, items: List<ItemStack>) {
        var overflowed = false
        for (item in items) {
            val leftovers = player.inventory.addItem(item)
            for (leftover in leftovers.values) {
                player.world.dropItemNaturally(player.location, leftover)
                overflowed = true
            }
        }
        if (overflowed) urb.messages.send(player, "open-inventory-full")
    }

    private fun runCommands(player: Player, box: RandomBox, at: Location?, commands: List<String>) {
        if (commands.isEmpty()) return
        val ph = Ph.of().player(player).box(box.displayName)
        at?.let { ph.location(it) }
        val console = Bukkit.getConsoleSender()
        for (raw in commands) {
            val command = ph.apply(raw).removePrefix("/").trim()
            if (command.isEmpty()) continue
            try {
                Bukkit.dispatchCommand(console, command)
            } catch (t: Throwable) {
                urb.logger.warning("상자 명령어 실행 실패 ($command): ${t.message}")
            }
        }
    }

    private fun announceReward(player: Player, box: RandomBox, reward: Reward) {
        val ph = Ph.of().player(player).box(box.displayName).item(reward.label())
        urb.notices.record(player.name, box, reward)

        val body = urb.messages.raw("reward-announce")
        if (body.isNotBlank()) {
            val prefix = urb.messages.raw(kr.inmc.core.config.MessageCatalog.PREFIX)
            for (viewer in Bukkit.getOnlinePlayers()) {
                // 개인 설정 "희귀 드랍·당첨 공지 받기"를 끈 사람은 빼고 — 당첨된 본인은 늘 본다(core PlayerSettings).
                if (viewer != player && !kr.inmc.core.integration.PlayerSettings.enabled(viewer, kr.inmc.core.integration.PlayerSettings.RARE_ANNOUNCE)) continue
                viewer.sendMessage(Text.render(prefix + body, ph, viewer))
            }
        }
        val discord = urb.config.discord
        if (discord.rewardEnabled) {
            urb.discord.enqueue(discord.rewardUrl(box.discordRewardWebhook), Text.plain(discord.rewardMessage, ph))
        }
    }

    private companion object {
        /** Enough variety for the strip to look random; more would just be re-serialised icons. */
        const val SPIN_POOL_LIMIT = 27
    }

    private fun broadcastOpen(player: Player, box: RandomBox, at: Location?) {
        if (!box.broadcastOpen) return
        val body = box.openMessage ?: urb.config.defaults.openMessage
        if (body.isBlank()) return

        val ph = Ph.of().player(player).box(box.displayName)
        ph.location(at ?: player.location)
        box.keyItem?.let { ph.key(it.label()) }
        ph.money(Numbers.money(box.moneyCost))

        val prefix = urb.messages.raw(kr.inmc.core.config.MessageCatalog.PREFIX)
        for (viewer in Bukkit.getOnlinePlayers()) {
            viewer.sendMessage(Text.render(prefix + body, ph, viewer))
        }
    }
}
