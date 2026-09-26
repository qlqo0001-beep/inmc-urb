package com.inmc.urb

import com.inmc.urb.config.Messages
import com.inmc.urb.util.Ph
import kr.inmc.core.util.Text
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Every shipped message has to survive MiniMessage.
 *
 * A malformed tag does not fail loudly - it either throws at render time, deep inside whatever
 * code path happened to send it, or it silently prints the tag as literal text to players. Both
 * are the kind of thing that only shows up in production, so all of them are parsed here.
 */
class MessageRenderTest {

    private val plain = PlainTextComponentSerializer.plainText()

    @Test
    fun `every default message parses as MiniMessage`() {
        for ((key, value) in Messages.DEFAULTS) {
            val rendered = runCatching { Text.render(value, null, null) }
            assertTrue(rendered.isSuccess, "$key 렌더 실패: ${rendered.exceptionOrNull()?.message}")
        }
    }

    /** Unclosed or misspelled tags leak into the output as literal `<...>` text. */
    @Test
    fun `no default message leaks a raw tag into the output`() {
        val allowed = setOf("copy-usage", "usage", "usage-player")
        for ((key, value) in Messages.DEFAULTS) {
            if (key in allowed) continue
            val text = plain.serialize(Text.render(value, null, null))
            assertTrue(
                !text.contains('<') && !text.contains('>'),
                "$key 에 해석되지 않은 태그가 남았습니다: $text",
            )
        }
    }

    /**
     * The spawn broadcast's track button is the only message carrying a click handler, and it
     * is assembled from two halves - so it is checked as the plugin actually builds it.
     *
     * Asserted by round-tripping through MiniMessage rather than by reading the click event
     * off the component: Adventure's ClickEvent is generic over its payload here, and the
     * serialized form is what actually has to survive to the client anyway.
     */
    @Test
    fun `the spawn track button produces a runnable command`() {
        val template = Messages.DEFAULTS.getValue("spawn-track-button")
        val body = template
            .replace("{command}", "/urb trackto world 128 -64")
            .replace("{world}", "world")
            .replace("{x}", "128")
            .replace("{z}", "-64")

        val component = Text.render(body, null, null)
        assertTrue(plain.serialize(component).contains("추적하기"))

        val roundTrip = MiniMessage.miniMessage().serialize(component)
        assertTrue(roundTrip.contains("run_command"), "클릭 이벤트가 붙지 않았습니다: $roundTrip")
        assertTrue(
            roundTrip.contains("/urb trackto world 128 -64"),
            "명령어가 잘못 들어갔습니다: $roundTrip",
        )
        assertTrue(roundTrip.contains("show_text"), "호버 안내가 사라졌습니다: $roundTrip")
    }

    /** Coordinates reach the button through the same placeholder bag the broadcast uses. */
    @Test
    fun `the spawn message and its button render together`() {
        val ph = Ph.of().box("보급 상자").location("world", 128, 70, -64)
        val body = "<white>{world}월드에 {상자이름} 상자가 생성되었습니다! 위치: {x}, {z}</white>"
        val button = Messages.DEFAULTS.getValue("spawn-track-button")
            .replace("{command}", "/urb trackto world 128 -64")

        val text = plain.serialize(Text.render(body + button, ph, null))
        assertTrue(text.contains("world월드에 보급 상자 상자가"), text)
        assertTrue(text.contains("128, -64"), text)
        assertTrue(text.endsWith("[추적하기]"), text)
    }

}
