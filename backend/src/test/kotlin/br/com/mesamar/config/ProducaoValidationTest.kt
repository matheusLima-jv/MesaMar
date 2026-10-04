package br.com.mesamar.config

import br.com.mesamar.common.MailProperties
import br.com.mesamar.push.PushProperties
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.ObjectMapper
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProducaoValidationTest {
    private fun ambiente() =
        MockEnvironment()
            .withProperty("DB_USER", "mesamar_app")
            .withProperty("DB_PASSWORD", "segredo-aleatorio-de-banco")
            .withProperty("mesamar.admin.password", "segredo-aleatorio-admin")
            .withProperty("spring.mail.host", "smtp.dominio.test")
            .withProperty("spring.mail.username", "smtp-user")
            .withProperty("spring.mail.password", "segredo-smtp")
            .withProperty("spring.flyway.enabled", "false")

    private fun validar(
        url: String = "https://mesamar.0mega.fun",
        push: PushProperties = PushProperties(),
    ) = ProducaoValidation.validar(
        ambiente(),
        SiteProperties(publicBaseUrl = url),
        MailProperties(from = "MesaMar <mail@dominio.test>"),
        push,
    )

    @Test
    fun `producao recusa urls locais caminhos e placeholders`() {
        listOf(
            "http://mesamar.dominio.test",
            "https://localhost",
            "https://127.0.0.1",
            "https://example.com",
            "https://mesamar.dominio.test",
            "https://mesamar.dominio.invalid",
            "https://mesamar.dominio.example",
            "https://mesamar.dominio.local",
            "https://mesamar.dominio.internal",
            "https://mesamar.dominio.test/subpasta",
            "https://mesamar.dominio.test?token=x",
        ).forEach {
            assertFailsWith<IllegalArgumentException> { validar(it) }
        }
    }

    @Test
    fun `smtp incompleto e migracao na aplicacao falham antecipadamente`() {
        listOf(
            "spring.mail.password" to "",
            "spring.mail.username" to "...",
            "DB_PASSWORD" to "SUBSTITUA",
            "spring.flyway.enabled" to "true",
        ).forEach { (campo, valor) ->
            assertFailsWith<IllegalArgumentException> {
                ProducaoValidation.validar(
                    ambiente().withProperty(campo, valor),
                    SiteProperties("https://mesamar.0mega.fun"),
                    MailProperties(from = "mail@dominio.test"),
                    PushProperties(),
                )
            }
        }
    }

    @Test
    fun `remetente SMTP malformado e recusado`() {
        assertFailsWith<IllegalArgumentException> {
            ProducaoValidation.validar(
                ambiente(),
                SiteProperties("https://mesamar.0mega.fun"),
                MailProperties(from = "mail@@dominio.com"),
                PushProperties(),
            )
        }
    }

    @Test
    fun `push parcial ou par invalido e recusado mas desligamento explicito e aceito`() {
        validar()
        assertFailsWith<IllegalArgumentException> { validar(push = PushProperties(publicKey = "invalida")) }
        assertFailsWith<IllegalArgumentException> { validar(push = PushProperties(publicKey = "invalida", privateKey = "invalida")) }
    }

    @Test
    fun `esquema exigido acompanha a ultima migracao e o manifesto da imagem`() {
        val migracoes =
            File("src/main/resources/db/migration")
                .listFiles()
                .orEmpty()
                .mapNotNull {
                    Regex("^V(\\d+)__")
                        .find(it.name)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                }
        val manifesto = ObjectMapper().readTree(File("../deploy/compatibilidade.json"))
        val esquema = ProducaoValidation.esquemaDaImagem()
        assertEquals(migracoes.max(), esquema)
        assertEquals(manifesto.get("schemaDestino").asInt(), esquema)
        assertEquals(manifesto.get("schemaMaximo").asInt(), esquema)
    }
}
