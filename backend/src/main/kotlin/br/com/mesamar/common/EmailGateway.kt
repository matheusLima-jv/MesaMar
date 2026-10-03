package br.com.mesamar.common

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "mesamar.mail")
data class MailProperties(
    /** Remetente dos e-mails, ex.: "MesaMar <nao-responda@0mega.fun>". */
    val from: String = "MesaMar <nao-responda@localhost>",
    /**
     * Sem SMTP, escreve no log o destinatário e o corpo inteiro do e-mail (com o link de troca de senha, que é uma
     * credencial). Só para desenvolvimento e testes: em produção fica desligado (padrão) e o log só diz que não enviou.
     */
    val logarCorpoSemSmtp: Boolean = false,
)

/** Envio de e-mail transacional (hoje: só a troca de senha). */
fun interface EmailGateway {
    fun send(
        to: String,
        subject: String,
        body: String,
    )
}

/**
 * Usa SMTP quando `spring.mail.host` está configurado. Sem SMTP (local e testes),
 * só registra no log: dá para copiar o link de troca de senha do log da aplicação.
 */
@Component
class SmtpOrLogEmailGateway(
    private val mailSender: ObjectProvider<JavaMailSender>,
    private val properties: MailProperties,
) : EmailGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(
        to: String,
        subject: String,
        body: String,
    ) {
        // Docker Compose repassa SPRING_MAIL_HOST vazio quando não há SMTP: tratamos igual a "sem SMTP".
        val sender = mailSender.ifAvailable?.takeUnless { (it as? JavaMailSenderImpl)?.host.isNullOrBlank() }
        if (sender == null) {
            if (properties.logarCorpoSemSmtp) {
                log.warn("SMTP não configurado; e-mail NÃO enviado. Para: {} | Assunto: {}\n{}", to, subject, body)
            } else {
                log.warn("SMTP não configurado; e-mail NÃO enviado (assunto: {}). Configure o SMTP para o e-mail sair.", subject)
            }
            return
        }
        val message =
            SimpleMailMessage().apply {
                from = properties.from
                setTo(to)
                this.subject = subject
                text = body
            }
        sender.send(message)
        log.info("E-mail enviado ({})", subject)
    }
}
