package com.inmc.urb

import com.inmc.urb.config.Messages
import kr.inmc.core.util.Durations
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parses the bundled resources exactly as the plugin does at startup.
 *
 * A YAML typo in a shipped default only shows up as a broken first boot otherwise, and the
 * Korean text makes an encoding regression easy to miss.
 */
class ResourceTest {

    private fun load(path: String): YamlConfiguration {
        val stream = javaClass.classLoader.getResourceAsStream(path)
        assertNotNull(stream, "리소스를 찾을 수 없습니다: $path")
        return stream.use {
            YamlConfiguration.loadConfiguration(InputStreamReader(it, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `config yml parses and carries every key the plugin reads`() {
        val config = load("config.yml")

        assertEquals("world", config.getString("defaults.world"))
        assertEquals("CHEST", config.getString("defaults.block"))
        assertEquals(3600L, Durations.parse(config.getString("defaults.auto-spawn-interval")))
        assertEquals(600L, Durations.parse(config.getString("defaults.despawn-after")))
        assertEquals(3, config.getInt("defaults.max-spawn-count"))
        assertEquals(50.0, config.getDouble("defaults.item-chance"))
        assertEquals(3, config.getInt("defaults.open-time"))
        assertEquals(1, config.getInt("defaults.min-rolls"))
        assertEquals(2, config.getInt("defaults.max-rolls"))

        assertEquals(172800L, Durations.parse(config.getString("spawn.pending-max-age")))
        assertEquals("ON_PLACE", config.getString("spawn.despawn-timer-start"))
        assertTrue(config.getInt("spawn.loaded-chunk-attempts") > 0)
        assertTrue(config.getBoolean("spawn.require-players-online"))

        // Boxes must survive a restart by default - only an explicit opt-in clears them.
        assertEquals(false, config.getBoolean("shutdown.cleanup-all"))

        assertEquals(300L, Durations.parse(config.getString("search.cooldown")))
        assertEquals("", config.getString("discord.webhook-url"))
    }

    /** Korean text must round-trip; a mojibake regression would show up right here. */
    @Test
    fun `config yml keeps korean text intact`() {
        val config = load("config.yml")
        val spawn = config.getString("defaults.messages.spawn")
        assertNotNull(spawn)
        assertTrue(spawn.contains("{상자이름}"), "한글 플레이스홀더가 깨졌습니다: $spawn")
        assertTrue(spawn.contains("생성되었습니다"))
    }

    /** Every message key the code can ask for has to exist in the shipped file. */
    @Test
    fun `messages yml covers every default key`() {
        val config = load("messages.yml")
        val present = config.getKeys(true).filterNot { config.isConfigurationSection(it) }.toSet()

        val missing = Messages.DEFAULTS.keys.filterNot { it in present }
        assertTrue(missing.isEmpty(), "messages.yml 에 빠진 키가 있습니다: $missing")
    }

    @Test
    fun `messages yml has no keys the code never reads`() {
        val config = load("messages.yml")
        val present = config.getKeys(true).filterNot { config.isConfigurationSection(it) }
        val unused = present.filterNot { it in Messages.DEFAULTS }
        assertTrue(unused.isEmpty(), "messages.yml 에 사용되지 않는 키가 있습니다: $unused")
    }

    @Test
    fun `example box parses with the expected shape`() {
        val config = load("boxes/example.yml")

        assertEquals(false, config.getBoolean("enabled"))
        assertEquals("GUI", config.getString("open.mode"))
        assertEquals("RANDOM_AREA", config.getString("spawn.mode"))
        assertEquals("RECTANGLE", config.getString("spawn.area.shape"))
        assertEquals(500, config.getInt("spawn.area.radius"))
        assertEquals(3600L, Durations.parse(config.getString("spawn.interval")))
        assertEquals(600L, Durations.parse(config.getString("spawn.despawn-after")))

        // Explicit nulls must read as "unset" so the world limits apply.
        assertTrue(!config.isSet("spawn.min-y") || config.get("spawn.min-y") == null)
        assertTrue(!config.isSet("messages.spawn") || config.get("messages.spawn") == null)

        val rewards = config.getConfigurationSection("rewards")
        assertNotNull(rewards)
        assertEquals(4, rewards.getKeys(false).size)

        val rare = rewards.getConfigurationSection("2")
        assertNotNull(rare)
        assertEquals("minecraft:netherite_ingot", rare.getString("item"))
        assertEquals(0.5, rare.getDouble("chance"))
        assertTrue(rare.getBoolean("announce"))

        val commandOnly = rewards.getConfigurationSection("3")
        assertNotNull(commandOnly)
        assertEquals(false, commandOnly.getBoolean("give-item"))
        assertEquals(1, commandOnly.getStringList("commands").size)
    }

    @Test
    fun `paper plugin descriptor targets the right api`() {
        val config = load("paper-plugin.yml")
        assertEquals("inmc-urb", config.getString("name"))
        assertEquals("com.inmc.urb.UrbPlugin", config.getString("main"))
        assertEquals("26.2", config.getString("api-version"))

        // Exactly one admin permission node (spec: 권한은 어드민권한 1개만).
        // `urb.admin` reads back as a nested path here because the config API splits on dots,
        // so nodes are identified by the `default` child that every declaration carries.
        val permissions = config.getConfigurationSection("permissions")
        assertNotNull(permissions)
        val nodes = permissions.getKeys(true).filter { permissions.contains("$it.default") }
        assertEquals(listOf("urb.admin"), nodes)

        // 연동은 전부 선택이어야 한다. 단 하나의 예외가 inmc-core 이며,
        // core 는 kotlin-stdlib 과 공용 프레임워크를 들고 있어 없으면 우리가 아예 못 돈다.
        val servers = config.getConfigurationSection("dependencies.server")
        assertNotNull(servers)
        for (name in servers.getKeys(false)) {
            if (name == "inmc-core") continue
            assertEquals(false, servers.getBoolean("$name.required"), "$name 이 필수 의존성으로 잡혀 있습니다")
        }
        assertEquals(true, servers.getBoolean("inmc-core.required"), "inmc-core 는 required 여야 합니다")
        // BEFORE = 의존이 우리보다 먼저 로드된다. 반대로 걸면 클래스로더가 붙지 않는다.
        assertEquals("BEFORE", servers.getString("inmc-core.load"), "inmc-core 는 load: BEFORE 여야 합니다")
        assertEquals(true, servers.getBoolean("inmc-core.join-classpath"), "inmc-core 의 클래스패스를 붙여야 합니다")
    }
}
