package com.inmc.urb.visual

import org.bukkit.Material

/**
 * How the loot is revealed once a box finishes opening.
 *
 * Modelled on what crate plugins converged on: an instant hand-over, a spinning roulette that
 * settles on the result, and a staggered reveal that pops the rewards out one at a time. All
 * three end at the same place - the animation only changes how the player sees it, never what
 * they get, because the roll already happened.
 */
enum class OpenAnimation(val label: String, val icon: Material, val description: List<String>) {

    NONE(
        "즉시 지급", Material.HOPPER,
        listOf("<gray>연출 없이 바로 지급합니다.</gray>"),
    ),

    ROULETTE(
        "룰렛", Material.CLOCK,
        listOf(
            "<gray>후보 아이템이 빠르게 돌다가</gray>",
            "<gray>점점 느려지며 결과에 멈춥니다.</gray>",
            "<dark_gray>결과는 이미 정해져 있고 표현만 바뀝니다.</dark_gray>",
        ),
    ),

    REVEAL(
        "순차 공개", Material.SPYGLASS,
        listOf(
            "<gray>보상이 하나씩 차례로 나타납니다.</gray>",
            "<dark_gray>희귀할수록 뒤에 나옵니다.</dark_gray>",
        ),
    );

    fun next(): OpenAnimation = entries[(ordinal + 1) % entries.size]

    fun previous(): OpenAnimation = entries[(ordinal - 1 + entries.size) % entries.size]

    companion object {
        fun parse(raw: String?): OpenAnimation =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

/**
 * Flourish played when the loot lands. Separate from [OpenAnimation] so a server can have a
 * plain instant hand-over that still fires fireworks on a jackpot.
 */
enum class RewardFlair(val label: String, val icon: Material) {
    NONE("없음", Material.GRAY_DYE),
    TOTEM("불사의 토템", Material.TOTEM_OF_UNDYING),
    FIREWORK("불꽃놀이", Material.FIREWORK_ROCKET),
    LIGHTNING("번개 (연출용)", Material.LIGHTNING_ROD),
    FANFARE("팡파레", Material.NOTE_BLOCK),
    BEAM("빛기둥", Material.END_ROD);

    fun next(): RewardFlair = entries[(ordinal + 1) % entries.size]

    fun previous(): RewardFlair = entries[(ordinal - 1 + entries.size) % entries.size]

    companion object {
        fun parse(raw: String?): RewardFlair =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}
