package br.com.mesamar.lead

import br.com.mesamar.common.BrazilianPhone
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.Errors
import java.time.Clock
import java.time.Instant

@Service
class LeadService(
    private val repository: LeadRepository,
    private val clock: Clock,
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

        val existing = repository.findByWhatsapp(whatsapp)
        val lead =
            if (existing != null) {
                existing.apply {
                    nome = form.nome.trim()
                    nomeBarraca = form.nomeBarraca.trim()
                    this.praia = praia
                    consentimentoEm = now
                    atualizadoEm = now
                }
            } else {
                Lead(
                    nome = form.nome.trim(),
                    nomeBarraca = form.nomeBarraca.trim(),
                    praia = praia,
                    whatsapp = whatsapp,
                    consentimentoEm = now,
                    origem = origem,
                    criadoEm = now,
                    atualizadoEm = now,
                )
            }
        val saved = repository.save(lead)
        log.info("Lead {} na lista de espera (praia={}, novo={})", saved.id, praia, existing == null)
        return saved
    }
}
