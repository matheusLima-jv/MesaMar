package br.com.mesamar.lead

import br.com.mesamar.common.TurnstileVerifier
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.TestIps
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Formulário com o Cloudflare Turnstile ligado (chaves configuradas). */
@IntegrationTest
@Import(LeadTurnstileTest.FakeTurnstile::class)
@TestPropertySource(
    properties = [
        "mesamar.turnstile.site-key=chave-publica-de-teste",
        "mesamar.turnstile.secret-key=chave-secreta-de-teste",
    ],
)
class LeadTurnstileTest(
    @Value("\${local.server.port}") private val port: Int,
    private val jdbc: JdbcTemplate,
) {
    /** Substitui a chamada real à Cloudflare: só o token "token-valido" passa. */
    @TestConfiguration(proxyBeanMethods = false)
    class FakeTurnstile {
        @Bean
        @Primary
        fun fakeTurnstileVerifier() = TurnstileVerifier { token, _ -> token == "token-valido" }
    }

    private val form =
        mapOf(
            "nome" to "Maria",
            "nomeBarraca" to "Quiosque da Maria",
            "praia" to "TOMBO",
            "consentimento" to "true",
            "_consentimento" to "on",
            "website" to "",
        )

    @Test
    fun `widget e script do Turnstile aparecem na landing`() {
        val html = HttpTestClient(port, TestIps.next()).get("/").body()

        assertContains(html, "https://challenges.cloudflare.com/turnstile/v0/api.js")
        assertContains(html, """data-sitekey="chave-publica-de-teste"""")
        assertEquals(false, html.contains("chave-secreta-de-teste"), "a chave secreta nunca pode ir para o HTML")
    }

    @Test
    fun `sem desafio resolvido o envio e recusado com mensagem`() {
        val response =
            HttpTestClient(port, TestIps.next())
                .postForm("/lista-de-espera", form + ("whatsapp" to "13 99111-2301") + ("cf-turnstile-response" to "invalido"))

        assertEquals(422, response.statusCode())
        assertContains(response.body(), "Não conseguimos confirmar que você não é um robô.")
        assertEquals(0, count("+5513991112301"))
    }

    @Test
    fun `com desafio resolvido o lead e salvo`() {
        val response =
            HttpTestClient(port, TestIps.next())
                .postForm("/lista-de-espera", form + ("whatsapp" to "13 99111-2302") + ("cf-turnstile-response" to "token-valido"))

        assertEquals(302, response.statusCode())
        assertEquals(1, count("+5513991112302"))
    }

    private fun count(e164: String): Int = jdbc.queryForObject("SELECT count(*) FROM lead WHERE whatsapp = ?", Int::class.java, e164)!!
}
