package br.com.mesamar.common

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Limite simples de requisições por chave (ex.: "lead:<ip>") em janela deslizante, em memória.
 *
 * Suficiente para uma única instância da aplicação (nosso caso no MVP). Se um dia houver
 * mais de uma instância, trocar por algo compartilhado (Postgres ou Redis). Ver ADR 0003.
 */
@Component
class RateLimiter(
    private val clock: Clock,
) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    /** Registra uma tentativa e diz se ela está dentro do limite. */
    fun tryAcquire(
        key: String,
        limit: Int,
        window: Duration,
    ): Boolean {
        val now = clock.instant()
        val cutoff = now.minus(window)
        val queue = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && queue.first() <= cutoff) queue.removeFirst()
            if (queue.size >= limit) return false
            queue.addLast(now)
            return true
        }
    }

    /** Remove chaves antigas para a memória não crescer para sempre. */
    fun evictOlderThan(window: Duration) {
        val cutoff = clock.instant().minus(window)
        hits.entries.removeIf { (_, queue) -> synchronized(queue) { queue.lastOrNull()?.let { it <= cutoff } ?: true } }
    }
}
