package br.com.mesamar.config

import br.com.mesamar.eventos.HubEventos
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MetricasSse {
    @Bean
    fun conexoesSse(
        hub: HubEventos,
        registro: MeterRegistry,
    ): Gauge = Gauge.builder("mesamar.sse.conexoes", hub) { it.total().toDouble() }.register(registro)
}
