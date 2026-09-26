package com.inmc.urb.gui

import com.inmc.urb.Urb
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Who has opened the most boxes.
 *
 * Open to everyone, not just admins: a leaderboard nobody can see is not a leaderboard, and it
 * is the cheapest way to make a scheduled airdrop feel competitive.
 *
 * Heads are drawn without an owning profile on purpose. `SkullMeta.setOwningPlayer` on an
 * offline player can hit Mojang's session server, and a ranking of 45 names would then block
 * the main thread once per open of this screen.
 */
class RankingMenu(urb: Urb, private val viewer: Player? = null) : Menu(urb, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val ranking = urb.stats.ranking(urb.config.statsRankingSize)
        val now = System.currentTimeMillis()

        if (ranking.isEmpty()) {
            set(
                22,
                Icon.of(
                    Material.BARRIER, "<gray>기록 없음</gray>",
                    "<dark_gray>아직 상자를 연 사람이 없습니다.</dark_gray>",
                )
            )
        }

        ranking.forEachIndexed { index, record ->
            val slot = SLOTS.getOrNull(index) ?: return@forEachIndexed
            val rank = index + 1
            val lore = mutableListOf(
                "<gray>오픈 <white>${record.opens}회</white></gray>",
            )
            if (record.lastOpenAt > 0L) {
                lore.add("<gray>마지막: <white>${Durations.formatShort((now - record.lastOpenAt) / 1000L)} 전</white></gray>")
            }
            if (viewer != null && record.id == viewer.uniqueId) {
                lore.add("")
                lore.add("<green>◀ 나</green>")
            }

            set(
                slot,
                Icon.of(
                    medal(rank),
                    "${colour(rank)}${rank}위 <white>${record.name}</white>",
                    lore,
                )
            )
        }

        if (viewer != null) {
            val mine = urb.stats.playerOpens(viewer.uniqueId)
            val rank = urb.stats.rankOf(viewer.uniqueId)
            set(
                49,
                Icon.of(
                    Material.NAME_TAG, "<yellow>내 기록</yellow>",
                    "<gray>오픈 <white>${mine}회</white></gray>",
                    if (rank != null) "<gray>순위: <white>${rank}위</white> / ${urb.stats.trackedPlayers()}명</gray>"
                    else "<dark_gray>아직 상자를 연 적이 없습니다.</dark_gray>",
                )
            )
        }

        set(53, Icon.close()) { event -> (event.whoClicked as? Player)?.closeInventory() }
    }

    private fun medal(rank: Int): Material = when (rank) {
        1 -> Material.GOLDEN_HELMET
        2 -> Material.IRON_HELMET
        3 -> Material.CHAINMAIL_HELMET
        else -> Material.PLAYER_HEAD
    }

    private fun colour(rank: Int): String = when (rank) {
        1 -> "<gold>"
        2 -> "<white>"
        3 -> "<#cd7f32>"
        else -> "<gray>"
    }

    companion object {
        private val SLOTS = (0..44).toList()
        private val TITLE = Text.renderFlat("<dark_gray>랜덤박스 오픈 랭킹</dark_gray>")
    }
}
