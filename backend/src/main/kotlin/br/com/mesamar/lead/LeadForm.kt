package br.com.mesamar.lead

import br.com.mesamar.common.Praia
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Dados do formulário da lista de espera, exatamente como chegam do navegador.
 * O WhatsApp é validado e normalizado no LeadService (regra de DDD e dígitos).
 */
data class LeadForm(
    @field:NotBlank(message = "Conta pra gente o seu nome.")
    @field:Size(min = 2, max = 80, message = "O nome precisa ter entre 2 e 80 letras.")
    var nome: String = "",
    @field:NotBlank(message = "Qual é o nome da sua barraca ou quiosque?")
    @field:Size(min = 2, max = 80, message = "O nome da barraca precisa ter entre 2 e 80 letras.")
    var nomeBarraca: String = "",
    @field:NotBlank(message = "Escolha a praia onde você trabalha.")
    var praia: String = "",
    @field:NotBlank(message = "Deixe um WhatsApp para a gente falar com você.")
    var whatsapp: String = "",
    @field:AssertTrue(message = "Para entrar na lista, é preciso concordar com o contato pelo WhatsApp.")
    var consentimento: Boolean = false,
    /** Honeypot: campo invisível para pessoas. Se vier preenchido, é robô. */
    var website: String = "",
) {
    val praiaEnum: Praia? get() = Praia.fromCode(praia)
    val isBot: Boolean get() = website.isNotBlank()
}
