package br.com.mesamar.lead

import br.com.mesamar.common.BrazilianPhone
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.Errors
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class LeadService(
    private val repository: LeadRepository,
    private val clock: Clock,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Regras que o Bean Validation não cobre: praia conhecida e WhatsApp brasileiro válido. */
    fun validate(
        form: LeadForm,
        errors: Errors,
    ) {
        if (form.praia.isNotBlank() && form.praiaEnum == null) {
            errors.rejectValue("praia", "praia.invalida", "Escolha uma praia da lista.")
        }
        if (form.whatsapp.isNotBlank() && BrazilianPhone.normalize(form.whatsapp) == null) {
            errors.rejectValue("whatsapp", "whatsapp.invalido", "Confira o número: DDD + celular, ex.: (13) 99999-8888.")
        }
    }

    /**
     * Salva o interessado. Se o mesmo WhatsApp já estiver na lista, atualiza os dados
     * em vez de duplicar (a pessoa pode ter mandado de novo para corrigir algo).
     */
    @Transactional
    fun register(
        form: LeadForm,
        origem: String,
    ): Lead {
        val whatsapp = requireNotNull(BrazilianPhone.normalize(form.whatsapp)) { "WhatsApp não validado" }
        val praia = requireNotNull(form.praiaEnum) { "Praia não validada" }
        val now = Instant.now(clock)

        val id =
            jdbc.queryForObject(
                """INSERT INTO lead (id,nome,nome_barraca,praia,whatsapp,consentimento_em,origem,criado_em,atualizado_em)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (whatsapp) DO UPDATE SET nome=EXCLUDED.nome, nome_barraca=EXCLUDED.nome_barraca,
                    praia=EXCLUDED.praia, consentimento_em=EXCLUDED.consentimento_em, atualizado_em=EXCLUDED.atualizado_em
                RETURNING id""",
                UUID::class.java,
                UUID.randomUUID(),
                form.nome.trim(),
                form.nomeBarraca.trim(),
                praia.name,
                whatsapp,
                Timestamp.from(now),
                origem,
                Timestamp.from(now),
                Timestamp.from(now),
            )!!
        val saved = repository.findById(id).orElseThrow()
        log.info("Lead {} na lista de espera (praia={})", saved.id, praia)
        return saved
    }
}
