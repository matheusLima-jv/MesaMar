package br.com.mesamar.support

import br.com.mesamar.common.EmailGateway
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

data class EmailEnviado(
    val to: String,
    val subject: String,
    val body: String,
)

/** Substitui o envio real nos testes: guarda os e-mails para o teste ler o link. */
class RecordingEmailGateway : EmailGateway {
    private val enviados = CopyOnWriteArrayList<EmailEnviado>()

    override fun send(
        to: String,
        subject: String,
        body: String,
    ) {
        enviados += EmailEnviado(to, subject, body)
    }

    fun para(to: String): List<EmailEnviado> = enviados.filter { it.to.equals(to, ignoreCase = true) }

    /** Caminho (sem domínio) do primeiro link do painel no último e-mail para `to`. */
    fun ultimoLink(to: String): String? =
        para(to).lastOrNull()?.let { Regex("""https?://[^/\s]+(/painel/\S+)""").find(it.body)?.groupValues?.get(1) }
}

@TestConfiguration(proxyBeanMethods = false)
class TestEmailConfiguration {
    @Bean
    @Primary
    fun recordingEmailGateway(): RecordingEmailGateway = RecordingEmailGateway()
}
