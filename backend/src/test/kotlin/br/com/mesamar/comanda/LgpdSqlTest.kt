package br.com.mesamar.comanda

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.staff.StaffApp
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O procedimento de exclusão de dados (`deploy/lgpd/anonimizar-cliente.sql`) é um arquivo fora do código da aplicação.
 * Este teste roda o arquivo de verdade contra o esquema real: se uma migração mudar uma tabela, ele quebra aqui, e não
 * no dia em que alguém pedir a exclusão dos seus dados.
 */
@IntegrationTest
class LgpdSqlTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val comandas: ComandaRepository,
    private val clientes: ClienteRepository,
    private val jdbc: JdbcTemplate,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    /** A raiz do repositório: o teste roda em `backend/`, e o script mora em `deploy/`. */
    private fun raiz(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve("deploy/lgpd/anonimizar-cliente.sql"))) dir = dir.parent
        return requireNotNull(dir) { "não achei deploy/lgpd/anonimizar-cliente.sql" }
    }

    /** Roda o arquivo como o psql roda, trocando `:'cliente'::uuid` por um parâmetro. */
    private fun executar(cliente: UUID) {
        val sql =
            Files
                .readAllLines(raiz().resolve("deploy/lgpd/anonimizar-cliente.sql"))
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
        val comandos = sql.split(";").map { it.trim() }.filter { it.isNotEmpty() && it != "BEGIN" && it != "COMMIT" }
        assertTrue(comandos.size >= 6, "o arquivo deveria ter os comandos de limpeza: ${comandos.size}")
        for (comando in comandos) {
            val parametros = Regex(Regex.escape(":'cliente'::uuid")).findAll(comando).count()
            val pronto = comando.replace(":'cliente'::uuid", "?::uuid")
            jdbc.update(pronto, *Array(parametros) { cliente.toString() })
        }
    }

    @Test
    fun `o script de exclusao anonimiza so o cliente pedido, limpa o texto livre e troca o link`() {
        val q = apoio.quiosque("Barraca LGPD Script")
        val app = app(q)
        val ana = apoio.novaComanda(q, "Ana Silva")
        val beto = apoio.novaComanda(q, "Beto")
        jdbc.update("UPDATE cliente SET whatsapp_e164 = '+5513999998888'")
        val pedidoAna = UUID.randomUUID()
        val pedidoBeto = UUID.randomUUID()
        assertEquals(
            201,
            apoio.pedir(apoio.celular(), ana, pedidoAna, Item(q.coco.id, 1, "para o filho do Marcos"), Item(q.pastel.id, 1)).statusCode(),
        )
        assertEquals(
            201,
            apoio.pedir(apoio.celular(), beto, pedidoBeto, Item(q.coco.id, 1, "sem gelo do Beto"), Item(q.pastel.id, 1)).statusCode(),
        )
        for ((pedido, token) in listOf(pedidoAna to ana, pedidoBeto to beto)) {
            val item =
                jdbc.queryForObject(
                    "SELECT id FROM item_pedido WHERE pedido_id = ? AND nome_snapshot = 'Pastel'",
                    UUID::class.java,
                    pedido,
                )
            assertEquals(
                200,
                app.post("/api/staff/pedidos/$pedido/itens/$item/cancelar", """{"motivo":"OUTRO","detalhe":"Marcos saiu"}""").statusCode(),
            )
            assertEquals(
                200,
                app
                    .post(
                        "/api/staff/comandas/${comandas.findByTokenAcesso(token)!!.id}/fechamento-forcado",
                        """{"motivo":"Marcos Silva saiu"}""",
                    ).statusCode(),
            )
        }
        val clienteAna = comandas.findByTokenAcesso(ana)!!.clienteId
        val comandaAna = comandas.findByTokenAcesso(ana)!!.id
        jdbc.update(
            "INSERT INTO problema_reportado (id, barraca_id, origem, comanda_id, tela, mensagem, conexao, criado_em) VALUES (?, ?, 'CLIENTE', ?, 'conta', 'Sou o Marcos', 'online', now())",
            UUID.randomUUID(),
            q.barracaId,
            comandaAna,
        )

        executar(clienteAna)
        executar(clienteAna) // idempotente

        val depois = clientes.findById(clienteAna).orElseThrow()
        assertEquals("Cliente", depois.nome)
        assertNull(depois.whatsappE164)
        assertNotNull(depois.anonimizadoEm)
        assertEquals(
            emptyList(),
            jdbc.queryForList(
                "SELECT observacao FROM item_pedido WHERE pedido_id = ? AND observacao IS NOT NULL",
                String::class.java,
                pedidoAna,
            ),
        )
        assertEquals(
            listOf("Outro"),
            jdbc.queryForList(
                "SELECT motivo_cancelamento FROM item_pedido WHERE pedido_id = ? AND cancelado",
                String::class.java,
                pedidoAna,
            ),
        )
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM problema_reportado WHERE comanda_id = ?", Int::class.java, comandaAna))
        assertEquals(
            "Removido (LGPD)",
            jdbc.queryForObject("SELECT fechamento_forcado_motivo FROM comanda WHERE id = ?", String::class.java, comandaAna),
        )
        assertNotEquals(
            ana,
            jdbc.queryForObject("SELECT token_acesso FROM comanda WHERE id = ?", String::class.java, comandaAna),
            "o link mudou",
        )
        assertEquals(404, apoio.celular().get("/api/c/$ana").statusCode(), "o link antigo não abre mais")
        assertEquals(
            1200L,
            jdbc.queryForObject(
                "SELECT sum(preco_snapshot_centavos) FROM item_pedido WHERE pedido_id = ? AND NOT cancelado",
                Long::class.java,
                pedidoAna,
            ),
            "os valores dos pedidos ficam",
        )

        // O Beto, que não pediu nada, não é tocado
        val b = clientes.findById(comandas.findByTokenAcesso(beto)!!.clienteId).orElseThrow()
        assertEquals("Beto", b.nome)
        assertEquals("+5513999998888", b.whatsappE164)
        assertEquals(
            listOf("sem gelo do Beto"),
            jdbc.queryForList(
                "SELECT observacao FROM item_pedido WHERE pedido_id = ? AND observacao IS NOT NULL",
                String::class.java,
                pedidoBeto,
            ),
        )
    }

    @Test
    fun `a versao do texto de consentimento do app e a do servidor sao a mesma, e estao documentadas`() {
        val ts = Files.readString(raiz().resolve("web-staff/src/consentimento.ts"))
        val noApp = assertNotNull(Regex("""CONSENTIMENTO_VERSAO = '([^']+)'""").find(ts)).groupValues[1]
        assertEquals(CheckinService.CONSENTIMENTO_VERSAO_ATUAL, noApp, "mudou o texto? mude as duas versões e documente")
        assertTrue(Files.readString(raiz().resolve("docs/consentimento.md")).contains("### `$noApp`"), "falta documentar a versão $noApp")
    }
}
