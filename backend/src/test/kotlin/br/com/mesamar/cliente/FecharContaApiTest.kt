package br.com.mesamar.cliente

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.common.BrCodePix
import br.com.mesamar.common.TipoChavePix
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.pagamento.MetodoPagamento
import br.com.mesamar.pagamento.Pagamento
import br.com.mesamar.pagamento.PagamentoRepository
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Fechar conta" pelo cliente: extrato, Pix, dinheiro e cartão, voltar e pedir mais (Fase 5 · checkpoint 5.1). */
@IntegrationTest
class FecharContaApiTest(
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val comandas: ComandaRepository,
    private val pagamentos: PagamentoRepository,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    private fun comPix(q: Quiosque) {
        val barraca = barracas.findById(q.barracaId).orElseThrow()
        barraca.tipoChavePix = TipoChavePix.ALEATORIA
        barraca.chavePix = "123e4567-e12b-12d1-a456-426655440000"
        barraca.nomeRecebedorPix = "FULANO DE TAL"
        barraca.cidadePix = "BRASILIA"
        barracas.save(barraca)
    }

    private fun fechar(
        celular: HttpTestClient,
        token: String,
        corpo: String,
    ) = celular.postJson("/api/c/$token/conta/fechar", corpo)

    private fun status(token: String) = comandas.findByTokenAcesso(token)!!.status

    private fun pagar(
        q: Quiosque,
        token: String,
        centavos: Long,
    ) {
        val comanda = comandas.findByTokenAcesso(token)!!
        pagamentos.save(
            Pagamento(
                barracaId = q.barracaId,
                comandaId = comanda.id,
                metodo = MetodoPagamento.DINHEIRO,
                valorCentavos = centavos,
                confirmadoPorUsuarioId = q.usuarioId,
                confirmadoEm = Instant.now(),
            ),
        )
    }

    // ---------- Extrato ----------

    @Test
    fun `o extrato mostra pedidos, cancelados e o saldo, e uma comanda aberta nao tem forma escolhida`() {
        val q = apoio.quiosque("Barraca Extrato")
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 2), Item(q.pastel.id, 1))

        val r = celular.get("/api/c/$token/conta")

        assertEquals(200, r.statusCode(), r.body())
        assertContains(r.body(), "\"status\":\"ABERTA\"")
        assertContains(r.body(), "\"pedidoCentavos\":4900")
        assertContains(r.body(), "\"pagoCentavos\":0")
        assertContains(r.body(), "\"saldoCentavos\":4900")
        assertContains(r.body(), "\"metodo\":null")
        assertContains(r.body(), "\"pix\":null")
        assertContains(r.body(), "\"aceitaPix\":false")
        assertContains(r.body(), "\"nome\":\"Pastel\"")
        assertTrue(r.headers().firstValue("Set-Cookie").isEmpty)
        assertEquals(404, celular.get("/api/c/${"Z".repeat(22)}/conta").statusCode())
    }

    @Test
    fun `o pagamento confirmado abate o saldo e aparece no extrato`() {
        val q = apoio.quiosque("Barraca Extrato Pago")
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        apoio.pedir(celular, token, UUID.randomUUID(), Item(q.pastel.id, 2))
        pagar(q, token, 2000)

        val r = celular.get("/api/c/$token/conta")

        assertContains(r.body(), "\"pedidoCentavos\":5000")
        assertContains(r.body(), "\"pagoCentavos\":2000")
        assertContains(r.body(), "\"saldoCentavos\":3000")
        assertContains(r.body(), "\"rotulo\":\"Dinheiro\"")
        assertContains(celular.get("/api/c/$token/pedidos").body(), "\"saldoCentavos\":3000")
        assertContains(celular.get("/api/c/$token").body(), "\"saldoCentavos\":3000")
    }

    // ---------- Fechar ----------

    @Test
    fun `fechar no Pix gera o codigo com o saldo, aguarda o pagamento e bloqueia pedidos novos`() {
        val q = apoio.quiosque("Barraca Fecha Pix")
        comPix(q)
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1), Item(q.pastel.id, 3))

        val r = fechar(celular, token, """{"metodo":"PIX"}""")

        assertEquals(200, r.statusCode(), r.body())
        assertContains(r.body(), "\"status\":\"AGUARDANDO_PAGAMENTO\"")
        assertContains(r.body(), "\"metodo\":\"PIX\"")
        assertContains(r.body(), "\"avisadoEm\":null")
        assertContains(r.body(), "\"aceitaPix\":true")
        val codigo = assertNotNull(Regex(""""codigo":"([^"]+)"""").find(r.body())).groupValues[1]
        assertTrue(BrCodePix.crcConfere(codigo), codigo)
        assertContains(codigo, "540587.00", message = "valor do saldo, 87,00")
        assertContains(codigo, "123e4567-e12b-12d1-a456-426655440000")
        assertContains(codigo, "5913FULANO DE TAL")
        assertEquals(StatusComanda.AGUARDANDO_PAGAMENTO, status(token))

        val pedido = apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1))
        assertEquals(409, pedido.statusCode())
        assertContains(pedido.body(), "aguardando-pagamento")
        assertContains(celular.get("/api/c/$token").body(), "\"bloqueio\":{\"codigo\":\"aguardando-pagamento\"")
        assertContains(celular.get("/api/c/$token/conta").body(), codigo)
    }

    @Test
    fun `no Pix o ja paguei marca a hora do aviso e repetir nao muda`() {
        val q = apoio.quiosque("Barraca Ja Paguei")
        comPix(q)
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1))
        assertEquals(409, celular.postJson("/api/c/$token/conta/pix-pago", "{}").statusCode(), "ainda não fechou a conta")
        fechar(celular, token, """{"metodo":"PIX"}""")
        assertNull(comandas.findByTokenAcesso(token)!!.avisoPagamentoEm)

        val primeiro = celular.postJson("/api/c/$token/conta/pix-pago", "{}")
        val hora = assertNotNull(comandas.findByTokenAcesso(token)!!.avisoPagamentoEm)
        val segundo = celular.postJson("/api/c/$token/conta/pix-pago", "{}")

        assertEquals(200, primeiro.statusCode(), primeiro.body())
        assertEquals(200, segundo.statusCode())
        assertEquals(hora, comandas.findByTokenAcesso(token)!!.avisoPagamentoEm)
        assertFalse(primeiro.body().contains("\"avisadoEm\":null"))
    }

    @Test
    fun `dinheiro com troco e cartao avisam na hora, e o troco nao pode ser menor que a conta`() {
        val q = apoio.quiosque("Barraca Fecha Dinheiro")
        val ana = apoio.novaComanda(q, "Ana")
        val beto = apoio.novaComanda(q, "Beto")
        val celular = apoio.celular()
        apoio.pedir(celular, ana, UUID.randomUUID(), Item(q.pastel.id, 3))
        apoio.pedir(celular, beto, UUID.randomUUID(), Item(q.coco.id, 1))

        val pouco = fechar(celular, ana, """{"metodo":"DINHEIRO","trocoParaCentavos":5000}""")
        assertEquals(400, pouco.statusCode())
        assertContains(pouco.body(), "trocoParaCentavos")
        assertEquals(StatusComanda.ABERTA, status(ana), "troco inválido não fecha a conta")

        val dinheiro = fechar(celular, ana, """{"metodo":"DINHEIRO","trocoParaCentavos":10000}""")
        assertEquals(200, dinheiro.statusCode(), dinheiro.body())
        assertContains(dinheiro.body(), "\"trocoParaCentavos\":10000")
        assertFalse(dinheiro.body().contains("\"avisadoEm\":null"))
        assertContains(dinheiro.body(), "\"pix\":null")

        val cartao = fechar(celular, beto, """{"metodo":"CARTAO","trocoParaCentavos":9999}""")
        assertEquals(200, cartao.statusCode(), cartao.body())
        assertContains(cartao.body(), "\"trocoParaCentavos\":null")
        assertEquals(MetodoPagamento.CARTAO, comandas.findByTokenAcesso(beto)!!.metodoPagamentoPretendido)
    }

    @Test
    fun `fechar de novo troca a forma de pagamento`() {
        val q = apoio.quiosque("Barraca Troca Forma")
        comPix(q)
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        apoio.pedir(celular, token, UUID.randomUUID(), Item(q.pastel.id, 1))
        fechar(celular, token, """{"metodo":"PIX"}""")

        val r = fechar(celular, token, """{"metodo":"DINHEIRO","trocoParaCentavos":5000}""")

        assertEquals(200, r.statusCode(), r.body())
        assertContains(r.body(), "\"metodo\":\"DINHEIRO\"")
        assertContains(r.body(), "\"pix\":null")
        assertEquals(StatusComanda.AGUARDANDO_PAGAMENTO, status(token))
    }

    @Test
    fun `nao fecha sem forma, com forma desconhecida, com a conta zerada ou sem Pix no quiosque`() {
        val q = apoio.quiosque("Barraca Fecha Recusa")
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()

        val vazia = fechar(celular, token, """{"metodo":"CARTAO"}""")
        assertEquals(409, vazia.statusCode())
        assertContains(vazia.body(), "conta-zerada")

        apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1))
        assertEquals(400, fechar(celular, token, "{}").statusCode(), "sem forma de pagamento")
        assertEquals(400, fechar(celular, token, """{"metodo":"BOLETO"}""").statusCode())
        assertEquals(400, fechar(celular, token, """{"metodo":"PIX","trocoParaCentavos":"abc"}""").statusCode())
        val semPix = fechar(celular, token, """{"metodo":"PIX"}""")
        assertEquals(409, semPix.statusCode())
        assertContains(semPix.body(), "pix-indisponivel")
        assertEquals(StatusComanda.ABERTA, status(token))
    }

    @Test
    fun `comanda bloqueada, encerrada ou de barraca desligada nao fecha conta`() {
        val q = apoio.quiosque("Barraca Fecha Estados")
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1))

        listOf(
            StatusComanda.BLOQUEADA to "comanda-bloqueada",
            StatusComanda.FECHADA to "comanda-fechada",
            StatusComanda.CANCELADA to "comanda-cancelada",
        ).forEach { (estado, codigo) ->
            comandas.save(comandas.findByTokenAcesso(token)!!.also { it.status = estado })
            val r = fechar(celular, token, """{"metodo":"CARTAO"}""")
            assertEquals(409, r.statusCode(), r.body())
            assertContains(r.body(), codigo)
            assertEquals(estado, status(token))
        }

        comandas.save(comandas.findByTokenAcesso(token)!!.also { it.status = StatusComanda.ABERTA })
        barracas.save(barracas.findById(q.barracaId).orElseThrow().also { it.ativa = false })
        assertContains(fechar(celular, token, """{"metodo":"CARTAO"}""").body(), "barraca-inativa")
    }

    // ---------- Voltar e pedir mais ----------

    @Test
    fun `voltar e pedir mais reabre a comanda enquanto nao ha pagamento confirmado`() {
        val q = apoio.quiosque("Barraca Reabre")
        comPix(q)
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1))
        fechar(celular, token, """{"metodo":"DINHEIRO","trocoParaCentavos":2000}""")

        val r = celular.postJson("/api/c/$token/conta/reabrir", "{}")

        assertEquals(200, r.statusCode(), r.body())
        assertContains(r.body(), "\"status\":\"ABERTA\"")
        assertContains(r.body(), "\"metodo\":null")
        val comanda = comandas.findByTokenAcesso(token)!!
        assertEquals(StatusComanda.ABERTA, comanda.status)
        assertNull(comanda.trocoParaCentavos)
        assertNull(comanda.avisoPagamentoEm)
        assertEquals(201, apoio.pedir(celular, token, UUID.randomUUID(), Item(q.pastel.id, 1)).statusCode())
        assertEquals(200, celular.postJson("/api/c/$token/conta/reabrir", "{}").statusCode(), "já aberta: não faz nada")
    }

    @Test
    fun `depois de um pagamento confirmado nao da para voltar`() {
        val q = apoio.quiosque("Barraca Reabre Recusa")
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        apoio.pedir(celular, token, UUID.randomUUID(), Item(q.pastel.id, 2))
        fechar(celular, token, """{"metodo":"CARTAO"}""")
        pagar(q, token, 2500)

        val r = celular.postJson("/api/c/$token/conta/reabrir", "{}")

        assertEquals(409, r.statusCode())
        assertContains(r.body(), "pagamento-confirmado")
        assertEquals(StatusComanda.AGUARDANDO_PAGAMENTO, status(token))
    }

    @Test
    fun `comanda encerrada nao reabre`() {
        val q = apoio.quiosque("Barraca Reabre Fechada")
        val token = apoio.novaComanda(q)
        comandas.save(comandas.findByTokenAcesso(token)!!.also { it.status = StatusComanda.FECHADA })

        val r = apoio.celular().postJson("/api/c/$token/conta/reabrir", "{}")

        assertEquals(409, r.statusCode())
        assertContains(r.body(), "comanda-fechada")
    }

    // ---------- Isolamento ----------

    @Test
    fun `cada token so mexe na propria comanda, e token desconhecido da 404 em todas as rotas`() {
        val a = apoio.quiosque("Barraca Isola A")
        val b = apoio.quiosque("Barraca Isola B")
        comPix(a)
        comPix(b)
        val ana = apoio.novaComanda(a, "Ana")
        val beto = apoio.novaComanda(a, "Beto")
        val carol = apoio.novaComanda(b, "Carol")
        val celular = apoio.celular()
        apoio.pedir(celular, ana, UUID.randomUUID(), Item(a.coco.id, 1))
        apoio.pedir(celular, beto, UUID.randomUUID(), Item(a.coco.id, 2))
        apoio.pedir(celular, carol, UUID.randomUUID(), Item(b.pastel.id, 1))

        fechar(celular, ana, """{"metodo":"PIX"}""")

        assertEquals(StatusComanda.AGUARDANDO_PAGAMENTO, status(ana))
        assertEquals(StatusComanda.ABERTA, status(beto), "a outra comanda da mesma barraca não muda")
        assertEquals(StatusComanda.ABERTA, status(carol), "a comanda de outra barraca não muda")
        assertContains(celular.get("/api/c/$beto/conta").body(), "\"saldoCentavos\":2400")
        assertContains(celular.get("/api/c/$carol/conta").body(), "\"saldoCentavos\":2500")
        assertFalse(celular.get("/api/c/$carol/conta").body().contains("Água de coco"), "o extrato da Carol não vaza itens da barraca A")

        // O Pix de cada um leva a chave e o saldo da própria comanda
        pagar(b, carol, 500)
        assertContains(celular.get("/api/c/$ana/conta").body(), "\"saldoCentavos\":1200")
        assertContains(celular.get("/api/c/$carol/conta").body(), "\"saldoCentavos\":2000")

        val desconhecido = "Z".repeat(22)
        assertEquals(404, celular.get("/api/c/$desconhecido/conta").statusCode())
        assertEquals(404, fechar(celular, desconhecido, """{"metodo":"CARTAO"}""").statusCode())
        assertEquals(404, celular.postJson("/api/c/$desconhecido/conta/pix-pago", "{}").statusCode())
        assertEquals(404, celular.postJson("/api/c/$desconhecido/conta/reabrir", "{}").statusCode())
    }
}
