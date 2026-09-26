package com.inmc.urb.box

import kr.inmc.core.store.PlayerStore
import kr.inmc.core.store.Profile
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

/**
 * `data/stats.yml` 의 `players.<uuid>.name` 을 core 의 `profile` 로 한 번 옮긴다.
 *
 * `opens` 와 `last` 는 **옮기지 않는다.** 그 둘은 `boxes:`·`log:` 와 한 문서로 원자적으로
 * 저장되고 `reset(null)` 이 셋을 한 번에 지우는데, 쪼개면 그 원자성이 깨진다. 랭킹 화면이
 * 전체 정렬을 하는데 공유 저장소에는 인덱스가 없다는 문제도 있다. 중복이던 것은 이름뿐이다.
 *
 * `stats.yml` 은 그대로 두고 이름도 한 릴리스 동안 계속 쓴다 — 옛 jar 로 되돌려도 이름이
 * 살아 있어야 하기 때문이다. 그래서 이 임포트는 몇 번을 돌려도 결과가 같다.
 */
object BoxStatsNameImport {

    data class Entry(val playerId: UUID, val name: String, val seen: Long)

    /** Bukkit 없이 도는 순수 파싱. [Entry.seen] 은 `last`(마지막 개봉 시각). */
    fun parse(config: YamlConfiguration): List<Entry> {
        val section = config.getConfigurationSection("players") ?: return emptyList()
        val out = ArrayList<Entry>()
        for (raw in section.getKeys(false)) {
            val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: continue
            val name = section.getString("$raw.name") ?: continue
            // 로드 폴백으로 "?" 를 쓰는 자리가 있어 그대로 넘어오면 core 를 오염시킨다.
            if (name == "?" || name.isBlank()) continue
            out.add(Entry(id, name, section.getLong("$raw.last", 0L)))
        }
        return out
    }

    fun apply(store: PlayerStore, entries: List<Entry>): Int {
        var written = 0
        for (entry in entries) {
            val merged = Profile.merge(
                existingName = Profile.nameOf(store, entry.playerId),
                existingSeen = Profile.seenAt(store, entry.playerId),
                incomingName = entry.name,
                incomingSeen = entry.seen,
            ) ?: continue
            Profile.touch(store, entry.playerId, merged.first!!, merged.second)
            written++
        }
        return written
    }
}
