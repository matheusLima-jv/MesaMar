package br.com.mesamar.push

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.staff.StaffApp
import br.com.mesamar.support.AparelhoDeTeste
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inscrição do aparelho nos avisos (Web Push): validação do destino, isolamento entre barracas e limites (Fase 4 · 4.2). */
@IntegrationTest
class PushApiTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val subs: PushSubscriptionRepository,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun inscrever(
        app: StaffApp,
        endpoint: String,
        aparelho: AparelhoDeTeste,
    ): HttpResponse<String> = app.post("/api/staff/push/inscrever", corpoInscricao(endpoint, aparelho))

    private fun corpoInscricao(
        endpoint: String,
        aparelho: AparelhoDeTeste,
    ) = """{"endpoint":"$endpoint","expirationTime":null,"keys":{"p256dh":"${Base64Url.codificar(
        aparelho.publicaBytes,
    )}","auth":"${Base64Url.codificar(aparelho.segredoAuth)}"}}"""

    private fun endpointFcm(id: String) = "https://fcm.googleapis.com/fcm/send/$id"

    @Test
    fun `a configuracao diz que o push esta ligado e entrega a chave publica`() {
        val app = app(apoio.quiosque("Barraca Push Config"))

        val r = app.get("/api/staff/push")

        assertEquals(200, r.statusCode())
        assertContains(r.body(), "\"habilitado\":true")
        assertContains(
            r.body(),
            "\"chavePublica\":\"BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8\"",
        )
    }

    @Test
    fun `inscrever guarda o aparelho da barraca e inscrever de novo nao duplica`() {
        val q = apoio.quiosque("Barraca Push Inscreve")
        val app = app(q)
        val aparelho = AparelhoDeTeste()
        val endpoint = endpointFcm("inscreve-${q.barracaId}")

        assertEquals(204, inscrever(app, endpoint, aparelho).statusCode())
        assertEquals(204, inscrever(app, endpoint, aparelho).statusCode(), "inscrever de novo é inofensivo")

        val s = subs.findAllByBarracaId(q.barracaId).single()
        assertEquals(endpoint, s.endpoint)
        assertEquals(q.usuarioId, s.usuarioId, "quem ativou os avisos fica registrado")
        assertNull(s.atendenteId)
        assertEquals(Base64Url.codificar(aparelho.publicaBytes), s.p256dh)
    }

    @Test
    fun `destino que nao e um servico de push conhecido e recusado`() {
        val q = apoio.quiosque("Barraca Push Destino")
        val app = app(q)
        val aparelho = AparelhoDeTeste()
        val recusados =
            listOf(
                "http://fcm.googleapis.com/fcm/send/x", // sem TLS
                "https://127.0.0.1/x", // a própria máquina
                "https://169.254.169.254/latest/meta-data", // metadados de nuvem
                "https://10.0.0.5/x", // rede interna
                "https://fcm.googleapis.com.malvado.com/x", // parece o Google, mas não é
                "https://malvadofcm.googleapis.com.br/x",
                "https://usuario@fcm.googleapis.com/x", // credenciais na URL
                "https://fcm.googleapis.com:8443/x", // porta diferente
                "ftp://fcm.googleapis.com/x",
                "isto não é uma url",
            )

        recusados.forEach { destino ->
            val r = inscrever(app, destino, aparelho)
            assertEquals(400, r.statusCode(), "$destino => ${r.body()}")
            assertContains(r.body(), "\"erro\":\"validacao\"")
        }
        assertEquals(204, inscrever(app, "https://updates.push.services.mozilla.com/wpush/v2/abc", aparelho).statusCode())
        assertEquals(204, inscrever(app, "https://web.push.apple.com/QGxyz", aparelho).statusCode())
        assertEquals(204, inscrever(app, "https://wns2-par02p.notify.windows.com/w/?token=abc", aparelho).statusCode())
        assertEquals(3, subs.countByBarracaId(q.barracaId), "só os destinos conhecidos ficaram")
    }

    @Test
    fun `chaves do aparelho invalidas sao recusadas`() {
        val q = apoio.quiosque("Barraca Push Chaves")
        val app = app(q)
        val aparelho = AparelhoDeTeste()
        val endpoint = endpointFcm("chaves")
        val foraDaCurva = Base64Url.codificar(aparelho.publicaBytes.copyOf().also { it[64] = (it[64] + 1).toByte() })
        val auth = Base64Url.codificar(aparelho.segredoAuth)
        val p256dh = Base64Url.codificar(aparelho.publicaBytes)

        fun corpo(
            chave: String,
            autenticacao: String,
        ) = """{"endpoint":"$endpoint","keys":{"p256dh":"$chave","auth":"$autenticacao"}}"""

        assertEquals(400, app.post("/api/staff/push/inscrever", corpo(foraDaCurva, auth)).statusCode(), "ponto fora da curva")
        assertEquals(400, app.post("/api/staff/push/inscrever", corpo("AAAA", auth)).statusCode(), "chave curta")
        assertEquals(400, app.post("/api/staff/push/inscrever", corpo(p256dh, "curto")).statusCode(), "segredo curto")
        assertEquals(400, app.post("/api/staff/push/inscrever", """{"endpoint":"$endpoint"}""").statusCode(), "sem chaves")
        assertEquals(400, app.post("/api/staff/push/inscrever", """{ isto não é json""").statusCode())
        assertEquals(0, subs.countByBarracaId(q.barracaId))
    }

    @Test
    fun `uma barraca nao cancela nem toma a inscricao de outra`() {
        val a = apoio.quiosque("Barraca Push Iso A")
        val b = apoio.quiosque("Barraca Push Iso B")
        val appA = app(a)
        val appB = app(b)
        val aparelhoA = AparelhoDeTeste()
        val endpoint = endpointFcm("iso-${a.barracaId}")
        assertEquals(204, inscrever(appA, endpoint, aparelhoA).statusCode())

        // B tenta apagar a inscrição de A e tomar o endpoint com chaves próprias
        assertEquals(204, appB.post("/api/staff/push/cancelar", """{"endpoint":"$endpoint"}""").statusCode())
        assertEquals(1, subs.countByBarracaId(a.barracaId), "a inscrição de A continua")
        val tomada = inscrever(appB, endpoint, AparelhoDeTeste())
        assertEquals(409, tomada.statusCode())
        assertEquals(a.barracaId, subs.findByEndpoint(endpoint)?.barracaId, "o endpoint continua sendo de A")
        assertEquals(0, subs.countByBarracaId(b.barracaId))

        // O mesmo aparelho (mesmas chaves) entrando em outra barraca: a inscrição vai junto
        assertEquals(204, inscrever(appB, endpoint, aparelhoA).statusCode())
        assertEquals(b.barracaId, subs.findByEndpoint(endpoint)?.barracaId)
    }

    @Test
    fun `desativar os avisos remove so a inscricao da propria barraca`() {
        val q = apoio.quiosque("Barraca Push Cancela")
        val app = app(q)
        val aparelho = AparelhoDeTeste()
        val um = endpointFcm("cancela-um-${q.barracaId}")
        val dois = endpointFcm("cancela-dois-${q.barracaId}")
        inscrever(app, um, aparelho)
        inscrever(app, dois, aparelho)

        assertEquals(204, app.post("/api/staff/push/cancelar", """{"endpoint":"$um"}""").statusCode())

        assertEquals(listOf(dois), subs.findAllByBarracaId(q.barracaId).map { it.endpoint })
        assertEquals(204, app.post("/api/staff/push/cancelar", """{"endpoint":"$um"}""").statusCode(), "cancelar de novo é inofensivo")
    }

    @Test
    fun `uma barraca tem um limite de aparelhos com avisos`() {
        val q = apoio.quiosque("Barraca Push Limite")
        val app = app(q)
        val aparelho = AparelhoDeTeste()

        repeat(
            PushService.MAX_POR_BARRACA,
        ) { assertEquals(204, inscrever(app, endpointFcm("lim-$it-${q.barracaId}"), aparelho).statusCode()) }
        val demais = inscrever(app, endpointFcm("lim-demais-${q.barracaId}"), aparelho)

        assertEquals(400, demais.statusCode())
        assertTrue(demais.body().contains("já tem ${PushService.MAX_POR_BARRACA} aparelhos"), demais.body())
        assertEquals(
            204,
            inscrever(app, endpointFcm("lim-0-${q.barracaId}"), aparelho).statusCode(),
            "atualizar um que já existe continua valendo",
        )
    }

    @Test
    fun `sem login nada funciona e as acoes sem CSRF sao recusadas`() {
        val q = apoio.quiosque("Barraca Push Sem Login")
        val anonimo = StaffApp(port)
        val aparelho = AparelhoDeTeste()

        assertEquals(401, anonimo.get("/api/staff/push").statusCode())
        assertEquals(401, inscrever(anonimo, endpointFcm("anonimo"), aparelho).statusCode())
        assertEquals(401, anonimo.post("/api/staff/push/cancelar", """{"endpoint":"x"}""").statusCode())

        val app = app(q)
        assertEquals(
            403,
            app.post("/api/staff/push/inscrever", corpoInscricao(endpointFcm("sem-csrf"), aparelho), comCsrf = false).statusCode(),
        )
        assertEquals(0, subs.countByBarracaId(q.barracaId))
    }
}
