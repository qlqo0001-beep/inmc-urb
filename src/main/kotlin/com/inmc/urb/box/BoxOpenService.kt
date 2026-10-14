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
     *
     * [keep] 은 연동 진입점(`UrbPlugin.grant`)용 — 보상을 **바닥에 두지 않는다**(2026-10-10). 창(GUI)은 닫을 때 남은 것을
     * 가방으로(넘침은 우편), 공중 투하(DROP)는 바로 가방으로. 던전 월드처럼 곧 지워지는 곳에서 주기 때문이다.
     */
    fun deliver(
        player: Player,
        box: RandomBox,
        at: Location?,
        announceOpen: Boolean,
        scope: com.inmc.urb.util.BlockKey? = null,
        keep: Boolean = false,
    ) {
        if (box.rewards.isEmpty()) {
            urb.messages.send(player, "box-no-rewards", Ph.of().box(box.displayName))
            return
        }

        val (rolled, items, commands, amounts) = rollLoot(box)

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
            handOver(player, box, at, items, rolled, commands, announceOpen, keep)
        }
    }

    /** 한 번 굴린 결과 — [deliver] 와 [roll](연동 진입점)이 같은 굴림을 쓴다. */
    private data class Loot(
        val rolled: List<Reward>,
        val items: List<ItemStack>,
        val commands: List<String>,
        val amounts: List<Pair<Reward, Int>>,
    )

    private fun rollLoot(box: RandomBox): Loot {
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
        return Loot(rolled, items, commands, amounts)
    }

    /**
     * 표만 굴려 아이템을 돌려준다 — 주지도, 알리지도, 기록하지도 않는다(연동 진입점 `UrbPlugin.roll`).
     * 보상의 명령어는 버린다(아이템만) — 명령어까지 우편에 담으려면 [rollForMail].
     */
    fun roll(box: RandomBox): List<ItemStack> = rollLoot(box).items

    /**
     * 표를 굴려 우편 한 통에 담을 것을 돌려준다(연동 진입점 `UrbPlugin.rollForMail`) — 아이템과, 받을 때 콘솔로 돌 명령어
     * (보상의 명령어 + 상자의 열 때 명령어 — 접속해 있는 사람이 `grant` 로 받는 것과 같다). 받는 사람 자리는 core 우편의
     * `{player}` 하나로 바꾸고 상자 이름은 미리 채운다. 좌표 자리는 채울 곳이 없어 그대로 둔다. 주지도, 알리지도, 기록하지도 않는다.
     */
    fun rollForMail(box: RandomBox): Pair<List<ItemStack>, List<String>> {
        val loot = rollLoot(box)
        val ph = Ph.of().player(kr.inmc.core.integration.Mail.PLAYER_TOKEN).box(box.displayName)
        val commands = (loot.commands + box.openCommands).map { ph.apply(it).removePrefix("/").trim() }.filter { it.isNotEmpty() }
        return loot.items to commands
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
        keep: Boolean = false,
    ) {
        // [keep] 이면 공중 투하도 바로 가방으로 — 연동으로 준 보상은 바닥에 두지 않는다([deliver]).
        val mode = box.effectiveOpenMode().let { if (keep && it == OpenMode.DROP) OpenMode.DIRECT else it }
        if (player.isOnline) {
            when (mode) {
                OpenMode.DIRECT -> giveDirect(player, box, items)
                // A permanent box hands its loot over rather than dropping leftovers on the
                // floor, so the window is only ever a presentation layer for it.
                OpenMode.GUI -> {
                    // A shutdown flush can arrive after the server has begun tearing menus
                    // down. The loot is already the player's by then, so fall back to handing
                    // it over directly rather than letting the window failure eat it.
                    val shown = runCatching {
                        LootMenu(urb, box, items, returnToInventory = box.isPermanent || keep).open(player)
                    }.isSuccess
                    if (!shown) giveDirect(player, box, items)
                }
                // Scatter where the box stood, not at the opener - the point of an airdrop is
                // that everyone who made it there gets a shot at the pile.
                OpenMode.DROP -> dropAt(at ?: player.location, items)
            }
            urb.visuals.playFlair(player, box.rewardFlair, at)
            // 본인에게는 무엇을 얻었는지 늘 알린다(잡템이어도 — 서버 공지와 따로, 테섭 2026-10-02).
            if (rolled.isNotEmpty()) {
                val got = rolled.joinToString("<gray>, </gray>") { "<white>${it.label()}</white>" }
                val key = if (mode == OpenMode.GUI) "reward-received-window" else "reward-received"
                urb.messages.send(player, key, Ph.of().box(box.displayName).item(got))
            }
        } else if (items.isNotEmpty()) {
            // Disconnected mid-animation: the loot was already theirs. 우편 창구가 있으면 우편으로(사용자 결정 2026-10-10 —
            // 미수령 보상은 전부 우편). 없거나 공중 투하(DROP — 모두가 주우라고 뿌리는 방식)면 전처럼 상자 자리에 떨군다.
            val mailed = mode != OpenMode.DROP && mail(player.uniqueId, box, items)
            if (!mailed) dropAt(at ?: player.location, items)
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

    private fun giveDirect(player: Player, box: RandomBox, items: List<ItemStack>) {
        val overflow = items.flatMap { player.inventory.addItem(it).values }
        if (overflow.isEmpty()) return
        // 가방이 가득 차면 바닥 대신 우편으로(사용자 결정 2026-10-10). 창구가 없으면 전처럼 발밑에.
        if (mail(player.uniqueId, box, overflow)) {
            urb.messages.send(player, "open-inventory-full-mail")
            return
        }
        for (leftover in overflow) player.world.dropItemNaturally(player.location, leftover)
        urb.messages.send(player, "open-inventory-full")
    }

    /** 우편 창구(core [kr.inmc.core.integration.Mail])로 보낸다. 창구가 없거나 거절하면 false — 부른 쪽이 제 길(바닥)로. */
    fun mail(playerId: UUID, box: RandomBox, items: List<ItemStack>): Boolean =
        items.isNotEmpty() && kr.inmc.core.integration.Mail.send(
            playerId, origin = urb.plugin.name, source = "랜덤박스 ${Text.plain(box.displayName)}", stacks = items,
        )

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
        // 투명인 사람의 당첨은 남는 공지 기록(/urb notice — 누구나 본다)에도 적지 않는다 — 접속해 있었다는 것이 드러난다.
        val hidden = kr.inmc.core.integration.Vanish.isVanished(player)
        if (!hidden) urb.notices.record(player.name, box, reward)

        val body = urb.messages.raw("reward-announce")
        if (body.isNotBlank()) {
            val prefix = urb.messages.raw(kr.inmc.core.config.MessageCatalog.PREFIX)
            for (viewer in Bukkit.getOnlinePlayers()) {
                // 개인 설정 "희귀 드랍·당첨 공지 받기"를 끈 사람은 빼고 — 당첨된 본인은 늘 본다(core PlayerSettings).
                if (viewer != player && !kr.inmc.core.integration.PlayerSettings.enabled(viewer, kr.inmc.core.integration.PlayerSettings.RARE_ANNOUNCE)) continue
                // 투명인 사람의 이름은 그를 볼 수 있는 사람에게만(본인 포함 — core Vanish).
                if (!kr.inmc.core.integration.Vanish.canSee(viewer, player)) continue
                viewer.sendMessage(Text.render(prefix + body, ph, viewer))
            }
        }
        val discord = urb.config.discord
        // 디스코드는 누가 보는지 모르는 곳 — 투명이면 보내지 않는다.
        if (discord.rewardEnabled && !hidden) {
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
            // 투명인 사람이 연 것은 그를 볼 수 있는 사람에게만(본인 포함).
            if (!kr.inmc.core.integration.Vanish.canSee(viewer, player)) continue
            viewer.sendMessage(Text.render(prefix + body, ph, viewer))
        }
    }
}
