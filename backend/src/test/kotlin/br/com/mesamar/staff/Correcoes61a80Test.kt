package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.cardapio.CardapioService
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.Direcao
import br.com.mesamar.cardapio.ProdutoForm
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.comanda.CheckinRequest
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ResultadoCheckin
import br.com.mesamar.common.EmailPendente
import br.com.mesamar.common.Entregas
import br.com.mesamar.common.Restricoes
import br.com.mesamar.common.Retencao
import br.com.mesamar.common.SecureTokens
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.RecuperacaoSenhaService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.pagamento.PagamentoService
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.validation.BeanPropertyBindingResult
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@IntegrationTest
@TestPropertySource(properties = ["mesamar.entregas.intervalo=PT24H", "mesamar.retencao.intervalo=PT24H"])
class Correcoes61a80Test(
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    private val produtos: ProdutoRepository,
    private val checkin: CheckinService,
    private val cardapio: CardapioService,
    private val barracas: BarracaRepository,
    private val jdbc: JdbcTemplate,
    private val transacoes: PlatformTransactionManager,
    private val recuperacao: RecuperacaoSenhaService,
    private val entregas: Entregas,
    private val retencao: Retencao,
    private val pagamentos: PagamentoService,
    private val push: br.com.mesamar.push.PushService,
    private val json: tools.jackson.databind.ObjectMapper,
    private val dataSource: javax.sql.DataSource,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun <T> juntos(vararg tarefas: () -> T): List<T> {
        val pronto = CountDownLatch(tarefas.size)
        val inicio = CountDownLatch(1)
        return Executors.newFixedThreadPool(tarefas.size).use { executor ->
            val futuros =
                tarefas.map { tarefa ->
                    executor.submit<T> {
                        pronto.countDown()
                        inicio.await()
                        tarefa()
                    }
                }
            assertTrue(pronto.await(5, TimeUnit.SECONDS))
            inicio.countDown()
            futuros.map { it.get(15, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `checkin simultaneo preserva token cliente e ausencia de orfaos`() {
        val q = apoio.quiosque("Checkin simultâneo íntegro")
        val id = UUID.randomUUID()
        val token = SecureTokens.generate(16)
        val tarefas =
            Array(6) {
                {
                    checkin.checkin(
                        q.barracaId,
                        AbertaPor(q.usuarioId, null),
                        CheckinRequest(id = id, nome = "Ana", consentimento = true, token = token),
                    ) as ResultadoCheckin.Ok
                }
            }
        val resultados = juntos(*tarefas)
        assertEquals(1, resultados.count { it.nova })
        assertEquals(setOf(id), resultados.map { it.detalhe.comanda.id }.toSet())
        assertEquals(setOf(token), resultados.map { it.detalhe.comanda.tokenAcesso }.toSet())
        assertEquals(1, resultados.map { it.detalhe.cliente.id }.toSet().size)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cliente WHERE barraca_id = ?", Int::class.java, q.barracaId))
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM cliente cl WHERE barraca_id = ? AND NOT EXISTS (SELECT 1 FROM comanda c WHERE c.cliente_id = cl.id)",
                Int::class.java,
                q.barracaId,
            ),
        )
    }

    @Test
    fun `id de checkin disputado por barracas nao altera a vencedora`() {
        val a = apoio.quiosque("Disputa A")
        val b = apoio.quiosque("Disputa B")
        val req = CheckinRequest(id = UUID.randomUUID(), nome = "Cliente", consentimento = true)
        val r =
            juntos(
                { checkin.checkin(a.barracaId, AbertaPor(a.usuarioId, null), req) },
                { checkin.checkin(b.barracaId, AbertaPor(b.usuarioId, null), req) },
            )
        assertEquals(1, r.count { it is ResultadoCheckin.Ok })
        assertEquals(1, r.count { it == ResultadoCheckin.Conflito })
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM cliente WHERE barraca_id IN (?, ?)", Int::class.java, a.barracaId, b.barracaId),
        )
    }

    @Test
    fun `autor de outra barraca e recusado e nao deixa cliente orfao`() {
        val a = apoio.quiosque("Autor A")
        val b = apoio.quiosque("Autor B")
        val erro =
            assertFailsWith<DataIntegrityViolationException> {
                checkin.checkin(a.barracaId, AbertaPor(b.usuarioId, null), CheckinRequest(nome = "Ana", consentimento = true))
            }
        assertEquals("fk_comanda_autor_usuario", Restricoes.nome(erro))
        assertFalse(Restricoes.unica(erro, "comanda_pkey"))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cliente WHERE barraca_id = ?", Int::class.java, a.barracaId))
    }

    @Test
    fun `reordenacoes simultaneas nao repetem posicoes`() {
        val q = apoio.quiosque("Ordem concorrente")
        val b = barracas.findById(q.barracaId).orElseThrow()
        juntos(
            { repeat(10) { cardapio.moverProduto(b, q.coco.id, Direcao.BAIXO) } },
            { repeat(10) { cardapio.moverProduto(b, q.pastel.id, Direcao.CIMA) } },
        )
        val ordens = produtos.findAllByBarracaIdOrderByOrdem(b.id).map { it.ordem }
        assertEquals(listOf(1, 2), ordens.sorted())
    }

    @Test
    fun `formulario antigo nao sobrescreve produto alterado em outro aparelho`() {
        val q = apoio.quiosque("Edição concorrente")
        val b = barracas.findById(q.barracaId).orElseThrow()
        val antigo = ProdutoForm.of(q.coco)
        cardapio.definirEsgotado(b, q.coco.id, true)
        antigo.nome = "Nome antigo reenviado"
        val erros = BeanPropertyBindingResult(antigo, "produtoForm")
        assertEquals(null, cardapio.editarProduto(b, q.coco.id, antigo, erros))
        assertTrue(erros.hasGlobalErrors())
        assertEquals("Água de coco", produtos.findById(q.coco.id).orElseThrow().nome)
    }

    @Test
    fun `nomes de categoria disputados produzem uma recusa de negocio`() {
        val q = apoio.quiosque("Nome de categoria concorrente")
        val b = barracas.findById(q.barracaId).orElseThrow()
        val r = juntos({ cardapio.criarCategoria(b, "Porções") }, { cardapio.criarCategoria(b, "Porções") })
        assertEquals(1, r.count { it == null })
        assertTrue(r.filterNotNull().single().contains("Já existe"))
    }

    @Test
    fun `recuperacao revertida nao deixa reset nem entrega pendente`() {
        val q = apoio.quiosque("Reset revertido")
        TransactionTemplate(transacoes).executeWithoutResult { tx ->
            recuperacao.solicitar(q.email)
            tx.setRollbackOnly()
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reset_senha WHERE usuario_id = ?", Int::class.java, q.usuarioId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM entrega_pendente WHERE destino = ?", Int::class.java, q.usuarioId))
        recuperacao.solicitar(q.email)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM entrega_pendente WHERE destino = ?", Int::class.java, q.usuarioId))
    }

    @Test
    fun `reservas concorrentes sao exclusivas e recuperaveis apos reinicio`() {
        entregas.registrar(
            "EMAIL",
            UUID.randomUUID(),
            EmailPendente("teste@invalid.test", "Teste", "Teste"),
            Instant.now().plusSeconds(3600),
        )
        val lotes = juntos({ entregas.reservar() }, { entregas.reservar() }).flatten()
        assertEquals(lotes.size, lotes.map { it.id }.toSet().size)
        assertTrue(lotes.isNotEmpty())
        val e = lotes.first()
        jdbc.update("UPDATE entrega_pendente SET reservada_ate = now() - interval '1 second' WHERE id = ?", e.id)
        val retomada = assertNotNull(entregas.reservar().find { it.id == e.id })
        entregas.concluir(e) // confirmação antiga não apaga a nova reserva
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM entrega_pendente WHERE id = ?", Int::class.java, e.id))
        entregas.concluir(retomada)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM entrega_pendente WHERE id = ?", Int::class.java, e.id))
    }

    @Test
    fun `historico mantem cada estado e autor sem guardar dados pessoais`() {
        val q = apoio.quiosque("Histórico de ações")
        val token = apoio.novaComanda(q, "Nome que não deve ir ao histórico")
        val id = jdbc.queryForObject("SELECT id FROM comanda WHERE token_acesso = ?", UUID::class.java, token)!!
        val principal = StaffPrincipal(q.barracaId, PapelStaff.DONO, q.usuarioId, null, "Dono")
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        try {
            pagamentos.bloquear(q.barracaId, id)
            pagamentos.desbloquear(q.barracaId, id)
        } finally {
            SecurityContextHolder.clearContext()
        }
        val eventos =
            jdbc.queryForList(
                "SELECT usuario_id, depois::text AS dados FROM historico_acao WHERE entidade = 'comanda' AND entidade_id = ? ORDER BY id",
                id,
            )
        assertEquals(3, eventos.size)
        assertTrue(eventos.all { it["usuario_id"] == q.usuarioId })
        assertTrue(eventos[1]["dados"].toString().contains("BLOQUEADA"))
        assertTrue(eventos[2]["dados"].toString().contains("ABERTA"))
        assertTrue(eventos.none { it["dados"].toString().contains(token) || it["dados"].toString().contains("Nome que") })
    }

    @Test
    fun `metadados expirados sao removidos sem apagar convites validos`() {
        val expirado = UUID.randomUUID()
        val valido = UUID.randomUUID()
        listOf(expirado, valido).forEach { id ->
            jdbc.update(
                "INSERT INTO convite (id, token_hash, expira_em) VALUES (?, ?, ?)",
                id,
                SecureTokens.hash(id.toString()),
                java.sql.Timestamp.from(
                    Instant.now().plusSeconds(
                        if (id ==
                            expirado
                        ) {
                            -5
                        } else {
                            3600
                        },
                    ),
                ),
            )
        }
        retencao.limpar()
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM convite WHERE id = ?", Int::class.java, expirado))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM convite WHERE id = ?", Int::class.java, valido))
    }

    @Test
    fun `smtp fora do ar preserva reset confirmado e repete a entrega sem afetar solicitacao`() {
        val q = apoio.quiosque("SMTP indisponível")
        recuperacao.solicitar(q.email)
        val gateway =
            br.com.mesamar.common
                .EmailGateway { _, _, _ -> throw IllegalStateException("SMTP indisponível") }
        br.com.mesamar.common
            .Entregador(entregas, gateway, push, json)
            .rodar()
        val pendente =
            jdbc.queryForMap(
                "SELECT tentativas, reserva, proxima_tentativa_em > now() AS aguardando FROM entrega_pendente WHERE destino = ?",
                q.usuarioId,
            )
        assertEquals(1, (pendente["tentativas"] as Number).toInt())
        assertEquals(null, pendente["reserva"])
        assertEquals(true, pendente["aguardando"])
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM reset_senha WHERE usuario_id = ? AND usado_em IS NULL", Int::class.java, q.usuarioId),
        )
    }

    @Test
    fun `pagina limitada mantem total completo e isolamento da barraca`() {
        val q = apoio.quiosque("Paginação com totais")
        repeat(3) { apoio.novaComanda(q) }
        jdbc.update("UPDATE comanda SET status = 'AGUARDANDO_PAGAMENTO' WHERE barraca_id = ?", q.barracaId)
        val outra = apoio.quiosque("Outra paginação")
        apoio.novaComanda(outra)
        val primeira = pagamentos.listar(q.barracaId, pagina = 0, tamanho = 2)
        val segunda = pagamentos.listar(q.barracaId, pagina = 1, tamanho = 2)
        assertEquals(3L, primeira.totalAguardando)
        assertEquals(2, primeira.aguardando.size)
        assertTrue(primeira.temMais)
        assertEquals(1, segunda.aguardando.size)
        assertFalse(segunda.temMais)
        assertEquals(3, (primeira.aguardando + segunda.aguardando).map { it.comandaId }.toSet().size)
        assertEquals(2, checkin.ativos(q.barracaId, tamanho = 2).size)
        assertEquals(1, checkin.ativos(q.barracaId, pagina = 1, tamanho = 2).size)
    }

    @Test
    fun `filtro por horario usa indice com crescimento de historico`() {
        val q = apoio.quiosque("Histórico para medição")
        val token = apoio.novaComanda(q)
        val comandaId = jdbc.queryForObject("SELECT id FROM comanda WHERE token_acesso = ?", UUID::class.java, token)!!
        jdbc.update(
            """INSERT INTO pagamento (id, barraca_id, comanda_id, metodo, valor_centavos, confirmado_por_usuario_id, confirmado_em)
            SELECT gen_random_uuid(), ?, ?, 'PIX', 100, ?, now() - g * interval '1 hour' FROM generate_series(1, 12000) g""",
            q.barracaId,
            comandaId,
            q.usuarioId,
        )
        jdbc.execute("ANALYZE pagamento")
        val fim = java.sql.Timestamp.from(Instant.now().minusSeconds(200L * 86400))
        val inicio = java.sql.Timestamp.from(fim.toInstant().minusSeconds(86400))
        val plano =
            jdbc.queryForObject(
                """EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
            SELECT metodo, SUM(valor_centavos) FROM pagamento WHERE barraca_id = ? AND confirmado_em >= ? AND confirmado_em < ? GROUP BY metodo""",
                String::class.java,
                q.barracaId,
                inicio,
                fim,
            )!!
        assertTrue(plano.contains("idx_pagamento_barraca_hora"), plano)
        val destino =
            java.nio.file.Path
                .of("build/reports/consultas-historico.json")
        java.nio.file.Files
            .createDirectories(destino.parent)
        java.nio.file.Files
            .writeString(destino, plano)
    }

    @Test
    fun `conta de execucao grava dados mas nao cria objetos ou bancos`() {
        val role = "runtime_teste_${UUID.randomUUID().toString().replace("-", "")}"
        jdbc.execute("CREATE ROLE $role NOSUPERUSER NOCREATEDB NOCREATEROLE")
        try {
            jdbc.execute("GRANT USAGE ON SCHEMA public TO $role")
            jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $role")
            jdbc.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO $role")
            dataSource.connection.use { conexao ->
                conexao.createStatement().use { sql ->
                    sql.execute("SET ROLE $role")
                    try {
                        sql.execute(
                            "INSERT INTO convite (id, token_hash, criado_em, expira_em) VALUES (gen_random_uuid(), '${SecureTokens.hash(
                                UUID.randomUUID().toString(),
                            )}', now(), now() - interval '1 hour')",
                        )
                        val ddl = assertFailsWith<java.sql.SQLException> { sql.execute("CREATE TABLE objeto_proibido (id int)") }
                        assertEquals("42501", ddl.sqlState)
                        val banco = assertFailsWith<java.sql.SQLException> { sql.execute("CREATE DATABASE banco_proibido") }
                        assertEquals("42501", banco.sqlState)
                    } finally {
                        sql.execute("RESET ROLE")
                    }
                }
            }
        } finally {
            jdbc.execute("DROP OWNED BY $role")
            jdbc.execute("DROP ROLE $role")
        }
    }

    @Test
    fun `erros de sessao validacao e link expirado tem contrato comum`() {
        val q = apoio.quiosque("Contrato de erros")
        val token = apoio.novaComanda(q)
        val http = apoio.celular()
        val respostas =
            listOf(
                http.get("/api/staff/clientes") to 401,
                http.postJson("/api/c/$token/pedidos", "{") to 400,
                http.get("/api/c/token-inexistente") to 404,
            )
        respostas.forEach { (resposta, status) ->
            assertEquals(status, resposta.statusCode(), resposta.body())
            val dados = json.readTree(resposta.body())
            assertTrue(dados.has("erro"))
            assertTrue(dados.has("mensagem"))
            assertTrue(dados.has("campos"))
            assertEquals(false, dados.get("temporario").asBoolean())
        }
    }

    @Test
    fun `renomeacao de categoria antiga nao apaga nome confirmado`() {
        val q = apoio.quiosque("Categoria com versão")
        val barraca = barracas.findById(q.barracaId).orElseThrow()
        val categoria = cardapio.categorias(barraca).single()
        assertEquals(null, cardapio.renomearCategoria(barraca, categoria.id, "Novo nome", categoria.versao))
        assertNotNull(cardapio.renomearCategoria(barraca, categoria.id, "Nome antigo atrasado", categoria.versao))
        assertEquals("Novo nome", cardapio.categorias(barraca).single().nome)
    }
}
