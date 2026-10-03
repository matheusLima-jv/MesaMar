package br.com.mesamar.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Component
@Order(-1000)
class DiagnosticoRequisicao(
    private val metricas: MeterRegistry,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ativas = AtomicInteger()

    init {
        Gauge.builder("mesamar.http.ativas", ativas) { it.get().toDouble() }.register(metricas)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val id = request.getHeader("X-Request-ID")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.randomUUID()
        val area =
            when {
                request.requestURI.startsWith("/api/c/") -> "cliente"
                request.requestURI.startsWith("/api/staff/") -> "equipe"
                request.requestURI.startsWith("/painel") -> "painel"
                request.requestURI.startsWith("/admin") -> "admin"
                else -> "publico"
            }
        val amostra = Timer.start(metricas)
        response.setHeader("X-Request-ID", id.toString())
        MDC.put("requestId", id.toString())
        ativas.incrementAndGet()
        try {
            chain.doFilter(request, response)
        } finally {
            val status = response.status.toString()
            amostra.stop(metricas.timer("mesamar.http.latencia", "area", area, "status", status))
            if (response.status >= 400) {
                metricas.counter("mesamar.http.erros", "area", area, "status", status).increment()
                log.info("Requisição {} área={} status={}", id, area, status)
            }
            ativas.decrementAndGet()
            MDC.remove("requestId")
        }
    }
}
