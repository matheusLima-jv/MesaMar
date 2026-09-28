package br.com.mesamar.common

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    /** Relógio controlado pelo teste. */
    private class MutableClock(
        var now: Instant = Instant.parse("2026-12-20T15:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MutableClock()
    private val limiter = RateLimiter(clock)
    private val window = Duration.ofMinutes(10)

    @Test
    fun `permite ate o limite e bloqueia o seguinte`() {
        repeat(3) { assertTrue(limiter.tryAcquire("ip-a", 3, window)) }
        assertFalse(limiter.tryAcquire("ip-a", 3, window))
    }

    @Test
    fun `chaves diferentes tem contadores separados`() {
        repeat(3) { limiter.tryAcquire("ip-a", 3, window) }
        assertTrue(limiter.tryAcquire("ip-b", 3, window))
    }

    @Test
    fun `libera de novo quando a janela passa`() {
        repeat(3) { limiter.tryAcquire("ip-a", 3, window) }
        clock.now = clock.now.plus(window).plusSeconds(1)
        assertTrue(limiter.tryAcquire("ip-a", 3, window))
    }

    @Test
    fun `limpeza remove chaves inativas sem afetar as ativas`() {
        limiter.tryAcquire("antigo", 3, window)
        clock.now = clock.now.plus(Duration.ofHours(2))
        repeat(3) { limiter.tryAcquire("ativo", 3, window) }

        limiter.evictOlderThan(Duration.ofHours(1))

        assertFalse(limiter.tryAcquire("ativo", 3, window), "a chave ativa deve manter o contador")
        assertTrue(limiter.tryAcquire("antigo", 1, window), "a chave antiga deve ter sido zerada")
    }
}
