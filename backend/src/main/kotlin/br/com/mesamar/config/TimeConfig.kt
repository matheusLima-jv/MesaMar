package br.com.mesamar.config

import br.com.mesamar.common.RateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration

@Configuration
@EnableScheduling
class TimeConfig {
    /** Relógio injetável: permite testar regras de tempo sem esperar de verdade. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

@Configuration
class RateLimiterMaintenance(
    private val rateLimiter: RateLimiter,
) {
    /** A cada 10 minutos, descarta contadores de IPs que não aparecem há mais de 1 hora. */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
    fun evict() = rateLimiter.evictOlderThan(Duration.ofHours(1))
}
