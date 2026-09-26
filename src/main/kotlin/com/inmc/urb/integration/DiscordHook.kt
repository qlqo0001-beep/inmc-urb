package com.inmc.urb.integration

import com.inmc.urb.config.DiscordSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.ArrayDeque
import java.util.logging.Logger

/**
 * Discord notifications over a plain webhook.
 *
 * The previous plugin required DiscordSRV to be installed and compiled against its shaded
 * JDA. A webhook URL needs no plugin at all - this is one HTTP POST against the JDK's own
 * client, so the integration cost is zero.
 *
 * Messages are queued and drained by the ticker one per second, which keeps us well inside
 * Discord's rate limit; a 429 pushes the whole queue back by the `Retry-After` it asks for.
 */
class DiscordHook(private val logger: Logger) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Each entry carries its own destination, so spawn and reward alerts can differ. */
    private data class Pending(val url: String, val content: String)

    private val queue = ArrayDeque<Pending>()

    @Volatile
    private var settings: DiscordSettings? = null

    @Volatile
    private var mutedUntil: Long = 0L

    val isEnabled: Boolean get() = settings?.isConfigured == true

    fun configure(settings: DiscordSettings) {
        this.settings = settings
        if (settings.webhookUrl.isNotBlank() && !settings.isConfigured) {
            logger.warning("discord.webhook-url 이 https:// 로 시작하지 않아 디스코드 알림을 끕니다")
        }
    }

    /**
     * Queues a plain-text message for one webhook. Safe to call from the main thread - nothing
     * blocks here.
     */
    fun enqueue(url: String, content: String) {
        if (content.isBlank() || !url.startsWith("https://")) return
        synchronized(queue) {
            if (queue.size >= MAX_QUEUE) {
                queue.poll()
                logger.warning("디스코드 전송 대기열이 가득 차 가장 오래된 메시지를 버렸습니다")
            }
            queue.add(Pending(url, content.take(1900)))
        }
    }

    /**
     * Pops one queued message and returns the work to run off-thread, or null when there is
     * nothing to send. The caller decides which thread performs the request.
     */
    fun drainOne(): (() -> Unit)? {
        val config = settings ?: return null
        if (System.currentTimeMillis() < mutedUntil) return null

        val pending = synchronized(queue) { queue.poll() } ?: return null
        return { send(config, pending) }
    }

    fun pendingCount(): Int = synchronized(queue) { queue.size }

    private fun send(config: DiscordSettings, pending: Pending, retry: Boolean = true) {
        val content = pending.content
        val payload = buildString {
            append('{')
            append("\"content\":\"").append(escape(content)).append('"')
            if (config.username.isNotBlank()) {
                append(",\"username\":\"").append(escape(config.username)).append('"')
            }
            if (config.avatarUrl.isNotBlank()) {
                append(",\"avatar_url\":\"").append(escape(config.avatarUrl)).append('"')
            }
            // never let a box name ping @everyone
            append(",\"allowed_mentions\":{\"parse\":[\"roles\",\"users\"]}")
            append('}')
        }

        try {
            val request = HttpRequest.newBuilder(URI.create(pending.url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "inmc-urb (+https://github.com/inmc)")
                .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            when {
                response.statusCode() in 200..299 -> Unit

                response.statusCode() == 429 -> {
                    val retryAfter = response.headers().firstValue("Retry-After")
                        .map { it.toDoubleOrNull() ?: 5.0 }
                        .orElse(5.0)
                    mutedUntil = System.currentTimeMillis() + (retryAfter * 1000).toLong()
                    synchronized(queue) { queue.addFirst(pending) }
                    logger.warning("디스코드 레이트리밋 - ${retryAfter}초 후 재시도합니다")
                }

                response.statusCode() in 400..499 -> {
                    logger.warning(
                        "디스코드 웹훅 거부됨 (HTTP ${response.statusCode()}). URL 을 확인해주세요."
                    )
                    mutedUntil = System.currentTimeMillis() + 60_000L
                }

                retry -> {
                    logger.warning("디스코드 전송 실패 (HTTP ${response.statusCode()}) - 1회 재시도합니다")
                    send(config, pending, retry = false)
                }

                else -> logger.warning("디스코드 전송 최종 실패 (HTTP ${response.statusCode()})")
            }
        } catch (t: Throwable) {
            if (retry) {
                send(config, pending, retry = false)
            } else {
                logger.warning("디스코드 전송 실패: ${t.javaClass.simpleName}: ${t.message}")
                mutedUntil = System.currentTimeMillis() + 30_000L
            }
        }
    }

    private fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        return out.toString()
    }

    companion object {
        private const val MAX_QUEUE = 200
    }
}
