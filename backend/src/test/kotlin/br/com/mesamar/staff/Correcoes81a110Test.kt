package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaForm
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.barraca.BarracaService
import br.com.mesamar.barraca.RegrasForm
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.common.SaudeOperacional
import br.com.mesamar.conta.CadastroResultado
import br.com.mesamar.conta.ContaService
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.RecuperacaoSenhaService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.guardasol.GuardaSolService
import br.com.mesamar.guardasol.LoteForm
import br.com.mesamar.pagamento.ConfirmacaoPagamento
import br.com.mesamar.pagamento.MetodoPagamento
import br.com.mesamar.pagamento.PagamentoService
import br.com.mesamar.pagamento.ResultadoQuiosque
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.header
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.TestPropertySource
import org.springframework.validation.BeanPropertyBindingResult
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@IntegrationTest
@TestPropertySource(properties = ["mesamar.entregas.intervalo=PT24H", "mesamar.retencao.intervalo=PT24H"])
class Correcoes81a110Test(
    @Value("\${local.server.port}") port: Int,
    private val convites: ConviteService,
    private val contas: ContaService,
    private val usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val barracaService: BarracaService,
    private val guardaSois: GuardaSolService,
    private val pagamentos: PagamentoService,
    private val recuperacao: RecuperacaoSenhaService,
    private val saude: SaudeOperacional,
    private val jdbc: JdbcTemplate,
    private val dataSource: DataSource,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun <T> juntos(vararg tarefas: () -> T): List<T> {
        val inicio = CountDownLatch(1)
        return Executors.newFixedThreadPool(tarefas.size).use { executor ->
            val futuros =
                tarefas.map { tarefa ->
                    executor.submit<T> {
                        inicio.await()
                        tarefa()
                    }
                }
            inicio.countDown()
            futuros.map { it.get(20, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `criacao simultanea preserva uma barraca por dono e slugs distintos entre donos`() {
        val email = painel.novoEmail("criacao")
        painel.donoLogado(email)
        val dono = requireNotNull(usuarios.findByEmailIgnoreCase(email))
        val criadas =
            juntos(
                *List(3) {
                    {
                        val form = BarracaForm("Criação simultânea", "ENSEADA")
                        requireNotNull(barracaService.criar(dono, form, BeanPropertyBindingResult(form, "form"))).id
                    }
                }.toTypedArray(),
            )
        assertEquals(1, criadas.distinct().size)
        assertEquals(criadas[0], usuarios.findById(dono.id).orElseThrow().barracaId)
        val outros =
            List(2) {
                val e = painel.novoEmail("slug")
                painel.donoLogado(e)
                requireNotNull(usuarios.findByEmailIgnoreCase(e))
            }
        val slugs =
            juntos(
                *outros
                    .map { u ->
                        {
                            val f = BarracaForm("Mesmo nome simultâneo", "ENSEADA")
                            requireNotNull(barracaService.criar(u, f, BeanPropertyBindingResult(f, "form"))).slug
                        }
                    }.toTypedArray(),
            )
        assertEquals(2, slugs.distinct().size)
    }

    @Test
    fun `cadastros e mudanca de limite simultaneos preservam capacidade da barraca`() {
        repeat(4) {
            val q = apoio.quiosque("Capacidade concorrente")
            val b = barracas.findById(q.barracaId).orElseThrow()
            juntos({
                val f = LoteForm("1", "20")
                guardaSois.criarLote(b, f, BeanPropertyBindingResult(f, "form"))
            }, {
                val f = RegrasForm(limiteGuardaSois = "10")
                barracaService.atualizarRegras(b, f, BeanPropertyBindingResult(f, "form"))
            })
            val atual = barracas.findById(q.barracaId).orElseThrow()
            assertTrue(guardaSois.ativos(atual) <= atual.limiteGuardaSois)
        }
    }

    @Test
    fun `convite concorrente cria uma conta e email concorrente preserva convite recusado`() {
        val token = convites.criar(null, "Concorrência").token
        val r =
            juntos({
                contas.cadastrar(token, "Ana", painel.novoEmail(), PainelTestSupport.SENHA)
            }, { contas.cadastrar(token, "Bia", painel.novoEmail(), PainelTestSupport.SENHA) })
        assertEquals(1, r.count { it is CadastroResultado.Ok })
        assertEquals(1, r.count { it is CadastroResultado.ConviteInvalido })
        val email = painel.novoEmail("disputa")
        val tokens = List(2) { convites.criar(null, null).token }
        val emails = juntos(*tokens.map { t -> { contas.cadastrar(t, "Dono", email, PainelTestSupport.SENHA) } }.toTypedArray())
        assertEquals(1, emails.count { it is CadastroResultado.Ok })
        assertEquals(1, emails.count { it is CadastroResultado.EmailEmUso })
        assertEquals(1, tokens.count { convites.buscarValido(it) != null })
    }

    @Test
    fun `resets simultaneos deixam um link valido e pedido cruzado com uso nao trava`() {
        val q = apoio.quiosque("Reset cruzado")
        juntos(*Array(4) { { recuperacao.solicitar(q.email) } })
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM reset_senha WHERE usuario_id=? AND usado_em IS NULL", Int::class.java, q.usuarioId),
        )
        val conteudo =
            jdbc.queryForObject(
                "SELECT conteudo FROM entrega_pendente WHERE tipo='EMAIL' AND destino=?",
                String::class.java,
                q.usuarioId,
            )!!
        val token = Regex("nova-senha\\?token=([A-Za-z0-9_-]+)").find(conteudo)!!.groupValues[1]
        juntos({
            recuperacao.redefinir(token, "senha nova comprida")
            true
        }, {
            recuperacao.solicitar(q.email)
            true
        })
        assertFalse(recuperacao.tokenValido(token))
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM reset_senha WHERE usuario_id=? AND usado_em IS NULL", Int::class.java, q.usuarioId),
        )
    }

    @Test
    fun `pagamentos parciais simultaneos nao perdem valores`() {
        val q = apoio.quiosque("Parciais concorrentes")
        val token = apoio.novaComanda(q)
        assertEquals(201, apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
        val id = jdbc.queryForObject("SELECT id FROM comanda WHERE token_acesso=?", UUID::class.java, token)!!
        val r =
            juntos(
                *Array(2) {
                    {
                        pagamentos.confirmar(
                            q.barracaId,
                            id,
                            ConfirmacaoPagamento(UUID.randomUUID(), MetodoPagamento.PIX, 600),
                            AbertaPor(q.usuarioId, null),
                        )
                    }
                },
            )
        assertTrue(r.all { it is ResultadoQuiosque.Ok })
        assertEquals(1200L, jdbc.queryForObject("SELECT sum(valor_centavos) FROM pagamento WHERE comanda_id=?", Long::class.java, id))
    }

    @Test
    fun `fechamento forcado disputando pagamento preserva soma financeira`() {
        repeat(4) {
            val q = apoio.quiosque("Fechamento cruzado $it")
            val token = apoio.novaComanda(q)
            assertEquals(201, apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
            val id = jdbc.queryForObject("SELECT id FROM comanda WHERE token_acesso=?", UUID::class.java, token)!!
            juntos({
                pagamentos.confirmar(
                    q.barracaId,
                    id,
                    ConfirmacaoPagamento(UUID.randomUUID(), MetodoPagamento.PIX, 600),
                    AbertaPor(q.usuarioId, null),
                )
            }, { pagamentos.fecharSemPagamento(q.barracaId, id, "Cliente foi embora", q.usuarioId) })
            assertEquals("FECHADA", jdbc.queryForObject("SELECT status FROM comanda WHERE id=?", String::class.java, id))
            val total =
                jdbc.queryForObject(
                    "SELECT coalesce((SELECT sum(valor_centavos) FROM pagamento WHERE comanda_id=c.id),0)+coalesce(saldo_perdoado_centavos,0) FROM comanda c WHERE id=?",
                    Long::class.java,
                    id,
                )
            assertEquals(1200L, total)
        }
    }

    @Test
    fun `medicao sem dado nao vira zero e falhas sao preservadas sem duplicar`() {
        val http = apoio.celular()
        val id = UUID.randomUUID()
        val corpo = """{"id":"$id","praia":"TOMBO","carregamentoMs":null,"pingMs":null,"metrica":"PEDIDO",
            "sucesso":false,"amostras":[{"duracaoMs":10000,"sucesso":false}]}"""
        repeat(2) { assertEquals(201, http.postJson("/api/teste-sinal/medicoes", corpo).statusCode()) }
        val linha = jdbc.queryForMap("SELECT * FROM medicao_sinal WHERE id=?", id)
        assertEquals(null, linha["carregamento_ms"])
        assertEquals(null, linha["ping_ms"])
        assertEquals(false, linha["sucesso"])
        assertTrue(linha["amostras"].toString().contains("10000"))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM medicao_sinal WHERE id=?", Int::class.java, id))
    }

    @Test
    fun `diagnostico correlaciona sem divulgar metricas ao publico`() {
        val http = apoio.celular()
        val res = http.get("/api/staff/fila")
        assertEquals(401, res.statusCode())
        assertTrue(UUID.fromString(res.header("X-Request-ID")).toString().isNotBlank())
        assertTrue(http.get("/actuator/metrics").statusCode() in listOf(401, 403))
        saude.registrar("ensaio", true)
        saude.registrar("ensaio", false)
        val linha = saude.consultar().first { it["tarefa"] == "ensaio" }
        assertTrue(linha["ultimo_sucesso"] != null && linha["ultima_falha"] != null)
    }

    @Test
    fun `upgrade V17 preserva conta pedido itens pagamento e credenciais`() {
        val q = apoio.quiosque("Upgrade com valores")
        val token = apoio.novaComanda(q)
        assertEquals(201, apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.coco.id, 2)).statusCode())
        val id = jdbc.queryForObject("SELECT id FROM comanda WHERE token_acesso=?", UUID::class.java, token)!!
        assertTrue(
            pagamentos.confirmar(
                q.barracaId,
                id,
                ConfirmacaoPagamento(UUID.randomUUID(), MetodoPagamento.PIX, 600),
                AbertaPor(q.usuarioId, null),
            ) is ResultadoQuiosque.Ok,
        )
        val schema = "upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        val anterior =
            Flyway
                .configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("17")
                .load()
        anterior.migrate()
        try {
            dataSource.connection.use { conn ->
                conn.schema = schema
                val alvo = JdbcTemplate(SingleConnectionDataSource(conn, true))
                val tabelas =
                    listOf("barraca", "usuario", "categoria", "produto", "cliente", "comanda", "pedido", "item_pedido", "pagamento")
                for (tabela in tabelas) {
                    val colunas =
                        jdbc
                            .queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position",
                                String::class.java,
                                schema,
                                tabela,
                            ).joinToString(",")
                    val filtro = if (tabela == "barraca") "id" else "barraca_id"
                    alvo.update("INSERT INTO $schema.$tabela ($colunas) SELECT $colunas FROM public.$tabela WHERE $filtro=?", q.barracaId)
                }
                val protegidas = listOf("usuario", "cliente", "comanda", "pedido", "item_pedido", "pagamento")
                val antes =
                    protegidas.associateWith { t ->
                        alvo.queryForList("SELECT to_jsonb(t)::text FROM $schema.$t t ORDER BY id", String::class.java)
                    }
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load()
                    .migrate()
                for (t in protegidas) {
                    assertEquals(
                        antes[t],
                        alvo.queryForList("SELECT to_jsonb(t)::text FROM $schema.$t t ORDER BY id", String::class.java),
                        "Valores de $t preservados",
                    )
                }
                assertEquals(600L, alvo.queryForObject("SELECT sum(valor_centavos) FROM $schema.pagamento", Long::class.java))
                assertEquals(19, alvo.queryForObject("SELECT max(version::int) FROM $schema.flyway_schema_history", Int::class.java))
                conn.schema = "public"
            }
        } finally {
            jdbc.execute("DROP SCHEMA $schema CASCADE")
        }
    }
}
