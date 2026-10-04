package br.com.mesamar.staff

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.cliente.ConsultaCliente
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.Atendente
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.lead.LeadForm
import br.com.mesamar.lead.LeadService
import br.com.mesamar.pedido.ItemRequest
import br.com.mesamar.pedido.PedidoRequest
import br.com.mesamar.pedido.PedidoService
import br.com.mesamar.pedido.ResultadoPedido
import br.com.mesamar.sincronia.AutorAparelho
import br.com.mesamar.sincronia.EstadoAparelho
import br.com.mesamar.sincronia.SincroniaAparelho
import br.com.mesamar.sincronia.TipoAparelho
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regressões dos bugs confirmados pela auditoria geral. */
@IntegrationTest
class CorrecoesAuditoriaTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    private val checkin: CheckinService,
    private val consultas: ConsultaCliente,
    private val pedidos: PedidoService,
    private val atendentes: AtendenteRepository,
    private val jdbc: JdbcTemplate,
    private val leads: LeadService,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    @Test
    fun `cadastro concorrente de mesmo whatsapp converge sem erro`() {
        val telefone = "+5513991122334"
        val originalId = UUID.randomUUID()
        val executor = Executors.newSingleThreadExecutor()
        jdbc.dataSource!!.connection.use { con ->
            con.autoCommit = false
            try {
                con
                    .prepareStatement(
                        "INSERT INTO lead(id,nome,nome_barraca,praia,whatsapp,consentimento_em,origem,criado_em,atualizado_em) VALUES (?,'Auditoria','Auditoria','ENSEADA',?,now(),'auditoria',now(),now())",
                    ).use { s ->
                        s.setObject(1, originalId)
                        s.setString(2, telefone)
                        s.executeUpdate()
                    }
                val futuro =
                    executor.submit<br.com.mesamar.lead.Lead> {
                        leads.register(
                            LeadForm(
                                nome = "Auditoria B",
                                nomeBarraca = "Auditoria",
                                praia = "ENSEADA",
                                whatsapp = telefone,
                                consentimento = true,
                            ),
                            "landing",
                        )
                    }
                val prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                var esperando = false
                while (System.nanoTime() < prazo && !esperando) {
                    esperando =
                        jdbc.queryForObject(
                            "SELECT count(*) > 0 FROM pg_stat_activity WHERE wait_event_type='Lock' AND lower(query) LIKE '%insert into lead%'",
                            Boolean::class.java,
                        ) ==
                        true
                    if (!esperando) Thread.sleep(10)
                }
                assertTrue(esperando, "Segundo cadastro deve ter lido ausência do primeiro e aguardado sua inserção")
                con.commit()
                val registrado = futuro.get(5, TimeUnit.SECONDS)
                assertEquals(originalId, registrado.id)
                assertEquals("Auditoria B", registrado.nome)
                assertEquals("auditoria", registrado.origem)
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lead WHERE whatsapp=?", Int::class.java, telefone))
            } finally {
                con.rollback()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `API recusa produto repetido e nao grava pedido`() {
        val q = apoio.quiosque("Auditoria quantidade")
        val token = apoio.novaComanda(q)
        val r = apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.coco.id, 20), Item(q.coco.id, 20), Item(q.coco.id, 20))
        assertEquals(400, r.statusCode(), r.body())
        assertContains(r.body(), "produto")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pedido WHERE barraca_id=?", Int::class.java, q.barracaId))
    }

    @Test
    fun `credencial revogada nao cria pedido mesmo se lida antes da troca`() {
        val q = apoio.quiosque("Auditoria revogacao")
        val token = apoio.novaComanda(q)
        val antiga = assertNotNull(consultas.vigente(token))
        assertNotNull(checkin.novoQr(q.barracaId, antiga.id))
        assertNull(consultas.vigente(token))
        val r = pedidos.criar(antiga, PedidoRequest(UUID.randomUUID(), listOf(ItemRequest(q.coco.id, 1))))
        assertTrue(r is ResultadoPedido.Bloqueado && r.bloqueio.codigo == "link-revogado")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pedido WHERE comanda_id=?", Int::class.java, antiga.id))
    }

    @Test
    fun `heartbeat nao esconde idade da acao pendente`() {
        val q = apoio.quiosque("Auditoria heartbeat")
        val aparelho = UUID.randomUUID()
        val inicio = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val primeira = SincroniaAparelho(jdbc, Clock.fixed(inicio, ZoneOffset.UTC))
        primeira.registrar(q.barracaId, AutorAparelho(q.usuarioId, null), EstadoAparelho(aparelho, 1, 0), TipoAparelho.ANDROID)
        val duasHoras = SincroniaAparelho(jdbc, Clock.fixed(inicio.plusSeconds(7200), ZoneOffset.UTC))
        assertTrue(duasHoras.listar(q.barracaId).single().atencao)
        duasHoras.registrar(q.barracaId, AutorAparelho(q.usuarioId, null), EstadoAparelho(aparelho, 1, 0), TipoAparelho.ANDROID)
        assertEquals(1, duasHoras.listar(q.barracaId).single().pendentes)
        assertTrue(duasHoras.listar(q.barracaId).single().atencao)
        assertEquals(inicio, duasHoras.listar(q.barracaId).single().pendenteDesde)
        duasHoras.registrar(
            q.barracaId,
            AutorAparelho(q.usuarioId, null),
            EstadoAparelho(aparelho, 1, 0, inicio.plusSeconds(7190)),
            TipoAparelho.ANDROID,
        )
        assertFalse(duasHoras.listar(q.barracaId).single().atencao)
        duasHoras.registrar(q.barracaId, AutorAparelho(q.usuarioId, null), EstadoAparelho(aparelho, 0, 0), TipoAparelho.ANDROID)
        assertNull(duasHoras.listar(q.barracaId).single().pendenteDesde)
    }

    @Test
    fun `trocar operador no mesmo aparelho preserva ambas as filas`() {
        val q = apoio.quiosque("Auditoria troca de operador")
        val agora = Instant.now()
        val a =
            atendentes.save(
                Atendente(
                    barracaId = q.barracaId,
                    nome = "Operador B",
                    pinHash = "diagnostico-sem-login",
                    criadoEm = agora,
                    atualizadoEm = agora,
                ),
            )
        val aparelho = UUID.randomUUID()
        val s = SincroniaAparelho(jdbc, Clock.fixed(agora, ZoneOffset.UTC))
        s.registrar(q.barracaId, AutorAparelho(q.usuarioId, null), EstadoAparelho(aparelho, 1, 0), TipoAparelho.ANDROID)
        assertEquals(1, s.listar(q.barracaId).single().pendentes)
        s.registrar(q.barracaId, AutorAparelho(null, a.id), EstadoAparelho(aparelho, 0, 0), TipoAparelho.ANDROID)
        val linhas = s.listar(q.barracaId)
        assertEquals(2, linhas.size)
        assertEquals(1, linhas.first { it.quem != "Operador B" }.pendentes)
        assertEquals(0, linhas.first { it.quem == "Operador B" }.pendentes)
    }

    @Test
    fun `script sem hash revalida e URL com hash tem cache imutavel`() {
        val r = apoio.celular().get("/js/medicoes.js")
        assertEquals(200, r.statusCode())
        val cache = r.headers().firstValue("Cache-Control").orElse("")
        assertContains(cache, "no-cache")
        assertFalse(cache.contains("immutable"))
        val html = apoio.celular().get("/staff/").body()
        val url = assertNotNull(Regex("""/js/medicoes-[a-f0-9]{32}\.js""").find(html)).value
        val versionado = apoio.celular().get(url)
        assertEquals(200, versionado.statusCode())
        assertEquals(r.body(), versionado.body())
        assertContains(versionado.headers().firstValue("Cache-Control").orElse(""), "immutable")
        assertContains(apoio.celular().get("/staff/sw.js").body(), url)
        assertContains(apoio.celular().get("/c/sw.js").body(), url)
    }
}
