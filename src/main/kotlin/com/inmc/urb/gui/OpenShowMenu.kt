package com.inmc.urb.gui

import com.inmc.urb.Urb
import com.inmc.urb.box.RandomBox
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Text
import com.inmc.urb.visual.OpenAnimation
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack

/**
 * The reveal window: a roulette that spins down onto the result, or a staggered pop-out.
 *
 * The roll has **already happened** before this opens - the animation only decides how the
 * player sees it. That ordering matters: an animation that decided the outcome would have to
 * run on the main thread under a timer, and a lag spike or a disconnect could change what the
 * player gets. Here neither can, because there is nothing left to decide.
 *
 * Frames are chained one-shot `runTaskLater` calls rather than a repeating timer, so the
 * plugin keeps its single repeating task. [onFinish] is guaranteed to run exactly once: on the
 * last frame, on an early close, or on shutdown via the caller's pending-delivery registry.
 */
class OpenShowMenu(
    urb: Urb,
    private val box: RandomBox,
    /** What the player actually won, already resolved to display stacks. */
    private val prizes: List<ItemStack>,
    /** Everything the box can produce, used as the spinning strip. */
    private val pool: List<ItemStack>,
    private val animation: OpenAnimation,
    private val onFinish: () -> Unit,
) : Menu(urb, SIZE, title(box)) {

    private var offset = 0
    private var revealed = 0
    private var finished = false

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)
        set(MARKER_TOP, Icon.of(org.bukkit.Material.HOPPER, "<yellow>▼</yellow>"))
        set(MARKER_BOTTOM, Icon.of(org.bukkit.Material.HOPPER, "<yellow>▲</yellow>"))
        when (animation) {
            OpenAnimation.ROULETTE -> drawStrip()
            else -> Unit
        }
    }

    fun start(player: Player) {
        open(player)
        when (animation) {
            OpenAnimation.ROULETTE -> spin(player, step = 0)
            OpenAnimation.REVEAL -> reveal(player)
            OpenAnimation.NONE -> finish(player)
        }
    }

    override fun onClose(event: InventoryCloseEvent) {
        if (finished) return
        // Closing early must not cost the player their loot. Deferred a tick because handing
        // items over - or opening the loot window - inside an InventoryCloseEvent is unsafe.
        val player = event.player as? Player ?: return
        finished = true
        Bukkit.getScheduler().runTask(urb.plugin, Runnable { onFinish() })
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
    }

    // --- roulette --------------------------------------------------------------

    private fun drawStrip() {
        if (pool.isEmpty()) return
        for (column in 0 until 9) {
            inventory.setItem(STRIP_START + column, pool[(offset + column) % pool.size])
        }
    }

    private fun spin(player: Player, step: Int) {
        if (finished) return
        if (!player.isOnline || player.openInventory.topInventory.holder !== this) return

        offset++
        drawStrip()
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.4f)

        if (step >= SPIN_DELAYS.size - 1) {
            land(player)
            return
        }
        later(SPIN_DELAYS[step].toLong()) { spin(player, step + 1) }
    }

    /** Drops the real result into the centre slot and stops the strip. */
    private fun land(player: Player) {
        if (finished) return
        val prize = prizes.firstOrNull()
        if (prize != null) inventory.setItem(CENTER, prize)
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.4f)

        // The rest of the haul, if any, pops out one at a time below the winner.
        revealed = 1
        later(REVEAL_GAP) { revealExtras(player) }
    }

    private fun revealExtras(player: Player) {
        if (finished) return
        if (!player.isOnline) {
            finish(player)
            return
        }
        if (revealed >= prizes.size || revealed - 1 >= EXTRA_SLOTS.size) {
            later(END_PAUSE) { finish(player) }
            return
        }
        inventory.setItem(EXTRA_SLOTS[revealed - 1], prizes[revealed])
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f + revealed * 0.1f)
        revealed++
        later(REVEAL_GAP) { revealExtras(player) }
    }

    // --- sequential reveal -----------------------------------------------------

    private fun reveal(player: Player) {
        if (finished) return
        if (!player.isOnline) {
            finish(player)
            return
        }
        if (revealed >= prizes.size) {
            later(END_PAUSE) { finish(player) }
            return
        }

        val slot = centeredSlot(revealed, prizes.size)
        inventory.setItem(slot, prizes[revealed])
        // Pitch climbs with each item, so a big haul sounds like a build-up.
        val pitch = (1.0f + revealed * 0.12f).coerceAtMost(2.0f)
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, pitch)
        revealed++
        later(REVEAL_GAP) { reveal(player) }
    }

    /** Lays [total] items out symmetrically across the middle row. */
    private fun centeredSlot(index: Int, total: Int): Int {
        val width = total.coerceIn(1, 9)
        val start = STRIP_START + (9 - width) / 2
        return start + index.coerceIn(0, width - 1)
    }

    // --- shared ----------------------------------------------------------------

    private fun finish(player: Player) {
        if (finished) return
        finished = true
        if (player.isOnline && player.openInventory.topInventory.holder === this) {
            player.closeInventory()
        }
        onFinish()
    }

    private fun later(ticks: Long, block: () -> Unit) {
        Bukkit.getScheduler().runTaskLater(urb.plugin, Runnable { block() }, ticks.coerceAtLeast(1L))
    }

    companion object {
        private const val SIZE = 27
        private const val STRIP_START = 9
        private const val CENTER = 13
        private const val MARKER_TOP = 4
        private const val MARKER_BOTTOM = 22

        /** Extra winnings land in the bottom row, spreading outwards from the middle. */
        private val EXTRA_SLOTS = intArrayOf(22, 21, 23, 20, 24, 19, 25, 18, 26)

        /**
         * Per-frame delay in ticks. Deliberately front-loaded and then stretched: a linear
         * slowdown reads as a stall, whereas an accelerating gap reads as a wheel losing speed.
         */
        private val SPIN_DELAYS = intArrayOf(
            1, 1, 1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 4, 4, 5, 5, 6, 7, 8,
            10, 12, 14,
        )

        private const val REVEAL_GAP = 6L
        private const val END_PAUSE = 25L

        private fun title(box: RandomBox) = Text.renderFlat(box.displayName)
    }
}
