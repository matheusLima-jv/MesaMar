package br.com.mesamar.cliente

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.pedido.ItemPedidoRepository
import br.com.mesamar.pedido.OrigemPedido
import br.com.mesamar.pedido.PedidoRepository
import br.com.mesamar.pedido.StatusPedido
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class PedidoClienteApiTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    private val barracas: BarracaRepository,
    categorias: CategoriaRepository,
    private val produtos: ProdutoRepository,
    private val comandas: ComandaRepository,
    checkin: CheckinService,
    private val pedidos: PedidoRepository,
    private val itens: ItemPedidoRepository,
    private val jdbc: JdbcTemplate,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    private fun celular() = apoio.celular()

    private fun quiosque(nome: String) = apoio.quiosque(nome)

    private fun novaComanda(
        q: Quiosque,
        nome: String = "Ana",
    ) = apoio.novaComanda(q, nome)

    private fun corpo(
        id: UUID?,
        vararg linhas: Item,
    ) = apoio.corpo(id, *linhas)

    private fun pedir(
        http: HttpTestClient,
        token: String,
        id: UUID?,
        vararg linhas: Item,
    ) = apoio.pedir(http, token, id, *linhas)

    private fun numero(body: String) = apoio.numero(body)

    private fun mudarStatus(
        token: String,
        status: StatusComanda,
    ) {
        val comanda = assertNotNull(comandas.findByTokenAcesso(token))
        comanda.status = status
        comandas.save(comanda)
    }

    // ---------- A comanda e o cardápio ----------

    @Test
    fun `a pagina abre com o cardapio, sem esconder o esgotado e sem mostrar o que saiu do cardapio`() {
        val q = quiosque("Barraca Cardápio")
        val outra = quiosque("Barraca Vizinha")
        val token = novaComanda(q, "Ana")
        q.pastel.esgotado = true
        produtos.save(q.pastel)
        val tirado = produtos.save(q.coco.also { it.ativo = false })

        val r = celular().get("/api/c/$token")

        assertEquals(200, r.statusCode(), r.body())
        assertContains(r.body(), "\"barraca\":\"Barraca Cardápio\"")
        assertContains(r.body(), "\"nome\":\"Ana\"")
        assertContains(r.body(), "\"bloqueio\":null")
        assertContains(r.body(), "\"nome\":\"Pastel\"")
        assertContains(r.body(), "\"esgotado\":true")
        assertFalse(r.body().contains(tirado.id.toString()), "produto fora do cardápio não aparece")
        assertFalse(r.body().contains(outra.coco.id.toString()), "produto de outra barraca não aparece")
        assertContains(r.headers().firstValue("Cache-Control").orElse(""), "no-store")
        assertTrue(r.headers().firstValue("Set-Cookie").isEmpty, "a página do cliente não usa cookies")
    }

    @Test
    fun `token que nao existe da 404 igual para qualquer valor e quem insiste e freado`() {
        val celular = celular()

        val get = celular.get("/api/c/${"A".repeat(22)}")
        val post = celular.postJson("/api/c/${"B".repeat(22)}/pedidos", corpo(UUID.randomUUID(), Item(UUID.randomUUID(), 1)))

        assertEquals(404, get.statusCode())
        assertEquals(404, post.statusCode())
        assertEquals(get.body(), celular.get("/api/c/${"C".repeat(22)}").body(), "a mensagem não revela nada sobre o token")
        // 3 tentativas acima + 27 abaixo = 30 (o limite); a próxima é barrada
        repeat(27) { assertEquals(404, celular.get("/api/c/${UUID.randomUUID()}").statusCode()) }
        assertEquals(429, celular.get("/api/c/${UUID.randomUUID()}").statusCode())
    }

    // ---------- Criar o pedido ----------

    @Test
    fun `pedido cria os itens com nome e preco congelados e numera por barraca`() {
        val q = quiosque("Barraca Pedido")
        val token = novaComanda(q)
        val celular = celular()
        val id = UUID.randomUUID()

        val r = pedir(celular, token, id, Item(q.coco.id, 2, "  sem   gelo "), Item(q.pastel.id, 1))

        assertEquals(201, r.statusCode(), r.body())
        assertEquals(1, numero(r.body()))
        assertContains(r.body(), "\"totalCentavos\":4900")
        assertContains(r.body(), "\"observacao\":\"sem gelo\"")
        assertTrue(r.headers().firstValue("Set-Cookie").isEmpty, "pedir não cria sessão nem cookie")

        val pedido = assertNotNull(pedidos.findById(id).orElse(null))
        assertEquals(StatusPedido.RECEBIDO, pedido.status)
        assertEquals(OrigemPedido.CLIENTE, pedido.origem)
        assertEquals(q.barracaId, pedido.barracaId)
        assertEquals(assertNotNull(comandas.findByTokenAcesso(token)).id, pedido.comandaId)
        val linhas = itens.findAllByPedidoIdOrderByLinha(id)
        assertEquals(listOf("Água de coco", "Pastel"), linhas.map { it.nomeSnapshot })
        assertEquals(listOf(1200L, 2500L), linhas.map { it.precoSnapshotCentavos })
        assertEquals(listOf(2, 1), linhas.map { it.quantidade })
        assertEquals(listOf("sem gelo", null), linhas.map { it.observacao })

        assertEquals(
            2,
            numero(pedir(celular, token, UUID.randomUUID(), Item(q.pastel.id, 1)).body()),
            "o segundo pedido da barraca é o nº 2",
        )
        val outra = quiosque("Outra Barraca")
        val deOutra = pedir(celular, novaComanda(outra), UUID.randomUUID(), Item(outra.coco.id, 1))
        assertEquals(1, numero(deOutra.body()), "cada barraca tem a sua própria numeração")
    }

    @Test
    fun `mudar o preco depois nao mexe no pedido que ja foi feito`() {
        val q = quiosque("Barraca Snapshot")
        val token = novaComanda(q)
        val celular = celular()
        val primeiro = UUID.randomUUID()
        assertEquals(201, pedir(celular, token, primeiro, Item(q.coco.id, 1)).statusCode())

        q.coco.precoCentavos = 1500
        q.coco.nome = "Coco gelado"
        q.coco.versao = produtos.save(q.coco).versao
        val segundo = UUID.randomUUID()
        val r = pedir(celular, token, segundo, Item(q.coco.id, 1))

        assertContains(r.body(), "\"nome\":\"Coco gelado\"")
        assertContains(r.body(), "\"totalCentavos\":1500")
        val antigo = itens.findAllByPedidoIdOrderByLinha(primeiro).single()
        assertEquals("Água de coco", antigo.nomeSnapshot)
        assertEquals(1200, antigo.precoSnapshotCentavos)
        // Reenviar o primeiro depois da mudança devolve o pedido como foi gravado
        assertContains(pedir(celular, token, primeiro, Item(q.coco.id, 1)).body(), "\"totalCentavos\":1200")
    }

    @Test
    fun `o mesmo pedido enviado 3 vezes gera um pedido so`() {
        val q = quiosque("Barraca Idempotente")
        val token = novaComanda(q)
        val celular = celular()
        val id = UUID.randomUUID()

        val primeira = pedir(celular, token, id, Item(q.coco.id, 2), Item(q.pastel.id, 1))
        val segunda = pedir(celular, token, id, Item(q.coco.id, 2), Item(q.pastel.id, 1))
        val terceira = pedir(celular, token, id, Item(q.coco.id, 2), Item(q.pastel.id, 1))

        assertEquals(201, primeira.statusCode(), primeira.body())
        assertEquals(200, segunda.statusCode())
        assertEquals(200, terceira.statusCode())
        assertEquals(primeira.body(), terceira.body())
        assertEquals(1, pedidos.countByBarracaId(q.barracaId))
        assertEquals(2, itens.findAllByPedidoIdOrderByLinha(id).size)
        assertEquals(
            2,
            numero(pedir(celular, token, UUID.randomUUID(), Item(q.pastel.id, 1)).body()),
            "o reenvio não gasta número de pedido",
        )
    }

    @Test
    fun `reenvios ao mesmo tempo criam um pedido so`() {
        val q = quiosque("Barraca Corrida")
        val token = novaComanda(q)
        val id = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(8)

        val respostas =
            try {
                pool.invokeAll((1..8).map { Callable { pedir(celular(), token, id, Item(q.coco.id, 1)) } }).map { it.get() }
            } finally {
                pool.shutdown()
            }

        assertEquals(listOf(200, 201), respostas.map { it.statusCode() }.distinct().sorted(), respostas.map { it.statusCode() }.toString())
        assertEquals(1, respostas.count { it.statusCode() == 201 })
        assertEquals(1, pedidos.countByBarracaId(q.barracaId))
        assertEquals(1, itens.findAllByPedidoIdOrderByLinha(id).size)
        assertEquals(2, numero(pedir(celular(), token, UUID.randomUUID(), Item(q.coco.id, 1)).body()), "só um número foi gasto")
    }

    @Test
    fun `o id de um pedido nao serve para outra comanda`() {
        val q = quiosque("Barraca Conflito")
        val dela = novaComanda(q, "Ana")
        val dele = novaComanda(q, "Beto")
        val id = UUID.randomUUID()
        assertEquals(201, pedir(celular(), dela, id, Item(q.coco.id, 1)).statusCode())

        val r = pedir(celular(), dele, id, Item(q.pastel.id, 3))

        assertEquals(409, r.statusCode())
        assertContains(r.body(), "\"erro\":\"conflito\"")
        assertEquals(1, pedidos.countByBarracaId(q.barracaId))
        assertEquals(assertNotNull(comandas.findByTokenAcesso(dela)).id, pedidos.findById(id).orElseThrow().comandaId)
    }

    // ---------- Recusas ----------

    @Test
    fun `produto esgotado ou fora do cardapio recusa o pedido inteiro com mensagem clara`() {
        val q = quiosque("Barraca Esgotado")
        val token = novaComanda(q)
        val celular = celular()
        q.coco.esgotado = true
        q.coco.versao = produtos.save(q.coco).versao

        val esgotado = pedir(celular, token, UUID.randomUUID(), Item(q.pastel.id, 1), Item(q.coco.id, 1))

        assertEquals(409, esgotado.statusCode())
        assertContains(esgotado.body(), "\"erro\":\"indisponivel\"")
        assertContains(esgotado.body(), "Água de coco")
        assertContains(esgotado.body(), "\"produtos\":[\"${q.coco.id}\"]")
        assertEquals(0, pedidos.countByBarracaId(q.barracaId), "o pastel não entra sozinho: o pedido é tudo ou nada")

        q.coco.esgotado = false
        q.coco.ativo = false
        q.coco.versao = produtos.save(q.coco).versao
        assertEquals(409, pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
        assertEquals(0, pedidos.countByBarracaId(q.barracaId))

        q.coco.ativo = true
        q.coco.versao = produtos.save(q.coco).versao
        assertEquals(1, numero(pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).body()), "as recusas não gastaram número")
    }

    @Test
    fun `comanda que nao esta aberta nao recebe pedido, e a pagina avisa por que`() {
        val q = quiosque("Barraca Bloqueios")
        val token = novaComanda(q)
        val celular = celular()
        val esperado =
            mapOf(
                StatusComanda.AGUARDANDO_PAGAMENTO to "aguardando-pagamento",
                StatusComanda.BLOQUEADA to "comanda-bloqueada",
                StatusComanda.FECHADA to "comanda-fechada",
                StatusComanda.CANCELADA to "comanda-cancelada",
            )

        esperado.forEach { (status, codigo) ->
            mudarStatus(token, status)
            val r = pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1))
            assertEquals(409, r.statusCode(), "$status")
            assertContains(r.body(), "\"erro\":\"$codigo\"")
            assertContains(celular.get("/api/c/$token").body(), "\"bloqueio\":{\"codigo\":\"$codigo\"")
        }
        assertEquals(0, pedidos.countByBarracaId(q.barracaId))

        mudarStatus(token, StatusComanda.ABERTA)
        assertEquals(201, pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
    }

    @Test
    fun `pedidos pausados pelo quiosque sao recusados e voltam quando despausa`() {
        val q = quiosque("Barraca Pausada")
        val token = novaComanda(q)
        val celular = celular()
        val barraca = barracas.findById(q.barracaId).orElseThrow()
        barraca.pedidosPausados = true
        barracas.save(barraca)

        val r = pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1))

        assertEquals(409, r.statusCode())
        assertContains(r.body(), "\"erro\":\"pedidos-pausados\"")
        assertContains(celular.get("/api/c/$token").body(), "\"codigo\":\"pedidos-pausados\"")

        barraca.pedidosPausados = false
        barracas.save(barraca)
        assertEquals(201, pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
    }

    @Test
    fun `reenviar um pedido que ja existe continua dando certo mesmo com a comanda fechada depois`() {
        val q = quiosque("Barraca Reenvio Tardio")
        val token = novaComanda(q)
        val id = UUID.randomUUID()
        assertEquals(201, pedir(celular(), token, id, Item(q.coco.id, 1)).statusCode())
        mudarStatus(token, StatusComanda.AGUARDANDO_PAGAMENTO)

        val r = pedir(celular(), token, id, Item(q.coco.id, 1))

        assertEquals(200, r.statusCode(), "a resposta perdida no caminho não vira erro")
        assertEquals(1, numero(r.body()))
    }

    @Test
    fun `pedido invalido e recusado campo a campo e nada e criado`() {
        val q = quiosque("Barraca Validação")
        val token = novaComanda(q)
        val celular = celular()
        val coco = q.coco.id

        fun recusa(
            r: HttpResponse<String>,
            campo: String? = null,
        ) {
            assertEquals(400, r.statusCode(), r.body())
            assertContains(r.body(), "\"erro\":\"validacao\"")
            campo?.let { assertContains(r.body(), "\"$it\"") }
        }
        recusa(pedir(celular, token, null, Item(coco, 1)), "id")
        recusa(celular.postJson("/api/c/$token/pedidos", """{"id":"${UUID.randomUUID()}","itens":[]}"""), "itens")
        recusa(pedir(celular, token, UUID.randomUUID(), Item(coco, 0)), "itens[0].quantidade")
        recusa(pedir(celular, token, UUID.randomUUID(), Item(coco, 21)), "itens[0].quantidade")
        recusa(pedir(celular, token, UUID.randomUUID(), Item(coco, 1, "x".repeat(81))), "itens[0].observacao")
        recusa(pedir(celular, token, UUID.randomUUID(), *Array(31) { Item(coco, 1) }), "itens")
        recusa(pedir(celular, token, UUID.randomUUID(), *Array(4) { Item(coco, 16) }), "itens")
        recusa(celular.postJson("/api/c/$token/pedidos", """{"id":"${UUID.randomUUID()}","itens":[{"quantidade":1}]}"""), "itens[0]")
        recusa(celular.postJson("/api/c/$token/pedidos", """{"id":"não-é-uuid","itens":[]}"""))
        recusa(
            celular.postJson(
                "/api/c/$token/pedidos",
                """{"id":"${UUID.randomUUID()}","itens":[{"produtoId":"${UUID.randomUUID()}","quantidade":"muitos"}]}""",
            ),
        )
        recusa(celular.postJson("/api/c/$token/pedidos", """{ isto não é json"""))

        assertEquals(0, pedidos.countByBarracaId(q.barracaId))
        val vinte = pedir(celular, token, UUID.randomUUID(), Item(coco, 20))
        assertEquals(1, numero(vinte.body()), "20 unidades de um item é o máximo e vale")
    }

    @Test
    fun `quem pede demais por minuto e freado`() {
        val q = quiosque("Barraca Limite")
        val token = novaComanda(q)
        val celular = celular()

        repeat(20) { assertEquals(400, pedir(celular, token, null, Item(q.coco.id, 1)).statusCode()) }
        val r = pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1))

        assertEquals(429, r.statusCode())
        assertEquals("30", r.headers().firstValue("Retry-After").orElse(""))
        assertEquals(200, celular.get("/api/c/$token").statusCode(), "o limite de pedir não trava a leitura do cardápio")
        assertEquals(0, pedidos.countByBarracaId(q.barracaId))
    }

    // ---------- Isolamento entre barracas ----------

    @Test
    fun `o token de uma barraca nunca pede produto de outra`() {
        val a = quiosque("Barraca Iso A")
        val b = quiosque("Barraca Iso B")
        val tokenA = novaComanda(a)
        val celular = celular()

        val r = pedir(celular, tokenA, UUID.randomUUID(), Item(a.coco.id, 1), Item(b.pastel.id, 1))

        assertEquals(409, r.statusCode())
        assertContains(r.body(), "\"erro\":\"indisponivel\"")
        assertContains(r.body(), "\"produtos\":[\"${b.pastel.id}\"]")
        assertFalse(r.body().contains("Pastel"), "o nome do produto de B não vaza para A: ${r.body()}")
        assertEquals(0, pedidos.countByBarracaId(a.barracaId))
        assertEquals(0, pedidos.countByBarracaId(b.barracaId))
        assertFalse(celular.get("/api/c/$tokenA").body().contains(b.pastel.id.toString()), "o cardápio de A não mostra o de B")
    }

    @Test
    fun `o banco impede item de produto de outra barraca e pedido de comanda de outra barraca`() {
        val a = quiosque("Barraca Banco A")
        val b = quiosque("Barraca Banco B")
        val tokenA = novaComanda(a)
        val id = UUID.randomUUID()
        assertEquals(201, pedir(celular(), tokenA, id, Item(a.coco.id, 1)).statusCode())
        val comandaA = assertNotNull(comandas.findByTokenAcesso(tokenA)).id

        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO item_pedido (id, pedido_id, barraca_id, produto_id, linha, nome_snapshot," +
                    " preco_snapshot_centavos, quantidade)" +
                    " VALUES (?, ?, ?, ?, 9, 'X', 100, 1)",
                UUID.randomUUID(),
                id,
                a.barracaId,
                b.pastel.id,
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO pedido (id, barraca_id, comanda_id, dia, numero_pedido, criado_em) VALUES (?, ?, ?, current_date, 99, now())",
                UUID.randomUUID(),
                b.barracaId,
                comandaA,
            )
        }
        assertEquals(1, itens.findAllByPedidoIdOrderByLinha(id).size)
        assertNull(pedidos.findAll().firstOrNull { it.numeroPedido == 99 })
    }
}
