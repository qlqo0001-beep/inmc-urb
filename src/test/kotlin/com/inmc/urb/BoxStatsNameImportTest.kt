package com.inmc.urb

import com.inmc.urb.box.BoxStatsNameImport
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `stats.yml` 의 `players.<uuid>.name` → core `profile` 임포트.
 *
 * 실서버 데이터로 검증할 수 없어 합성 YAML 로만 지킨다. 그래서 파싱이 Bukkit 없이 도는
 * 순수 함수로 떨어져 있다.
 */
class BoxStatsNameImportTest {

    private val steve = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val alex = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun yaml(build: YamlConfiguration.() -> Unit): YamlConfiguration =
        YamlConfiguration().apply(build).let {
            YamlConfiguration().apply { loadFromString(it.saveToString()) }
        }

    @Test
    fun `이름과 마지막 개봉 시각을 뽑는다`() {
        val config = yaml {
            set("players.$steve.name", "Steve")
            set("players.$steve.opens", 17L)
            set("players.$steve.last", 1_700_000_000_000L)
        }

        val entries = BoxStatsNameImport.parse(config)

        assertEquals(listOf(BoxStatsNameImport.Entry(steve, "Steve", 1_700_000_000_000L)), entries)
    }

    @Test
    fun `폴백으로 남은 물음표는 core 로 넘기지 않는다`() {
        // BoxStats.load 가 이름 없는 항목에 "?" 를 넣는다. 그게 넘어가면 실제 이름을 덮어
        // 랭킹에 물음표가 뜬다.
        val config = yaml {
            set("players.$steve.name", "?")
            set("players.$alex.name", "Alex")
        }

        val entries = BoxStatsNameImport.parse(config)

        assertEquals(listOf(alex), entries.map { it.playerId })
    }

    @Test
    fun `UUID 가 아닌 키는 건너뛴다`() {
        val config = yaml {
            set("players.이상한키.name", "Broken")
            set("players.$steve.name", "Steve")
        }

        assertEquals(listOf(steve), BoxStatsNameImport.parse(config).map { it.playerId })
    }

    @Test
    fun `players 섹션이 없으면 아무것도 나오지 않는다`() {
        val config = yaml { set("boxes.골드상자.opens", 5L) }

        assertTrue(BoxStatsNameImport.parse(config).isEmpty())
    }

    @Test
    fun `opens 와 last 는 stats yml 에 그대로 남는다`() {
        // 이 임포트는 이름만 가져간다. opens/last 를 옮기면 boxes·log 와의 원자적 초기화가
        // 깨지고, 랭킹이 전체 정렬을 하는데 공유 저장소에는 인덱스가 없다.
        val config = yaml {
            set("players.$steve.name", "Steve")
            set("players.$steve.opens", 17L)
        }

        val entry = BoxStatsNameImport.parse(config).single()

        assertEquals("Steve", entry.name)
        assertEquals(17L, config.getLong("players.$steve.opens"), "원본은 건드리지 않는다")
    }
}
