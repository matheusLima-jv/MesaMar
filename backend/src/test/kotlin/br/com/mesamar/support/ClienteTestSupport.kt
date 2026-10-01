package br.com.mesamar.support

import br.com.mesamar.cardapio.Categoria
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.Produto
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.comanda.CheckinRequest
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ResultadoCheckin
import br.com.mesamar.conta.UsuarioRepository
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.test.assertNotNull

/** Uma barraca com a categoria "Bebidas" e dois produtos: Água de coco (R$ 12,00) e Pastel (R$ 25,00). */
class Quiosque(
    val barracaId: UUID,
    val usuarioId: UUID,
    /** E-mail do dono (a senha é `PainelTestSupport.SENHA`), para entrar no app do quiosque. */
    val email: String,
    val coco: Produto,
    val pastel: Produto,
)

/** Uma linha do pedido, como o celular do cliente envia. */
class Item(
    val produtoId: UUID,
    val quantidade: Int,
    val observacao: String? = null,
)

/** Monta barracas, comandas e pedidos para os testes da API do cliente (`/api/c/{token}`). */
class ClienteTestSupport(
    private val port: Int,
    private val painel: PainelTestSupport,
    private val usuarios: UsuarioRepository,
    private val categorias: CategoriaRepository,
    private val produtos: ProdutoRepository,
    private val checkin: CheckinService,
) {
    /** Um "celular" novo: IP próprio (para os limites por IP de um teste não pesarem nos outros) e sem cookies. */
    fun celular() = HttpTestClient(port, TestIps.next())

    fun quiosque(nome: String): Quiosque {
        val email = painel.novoEmail("pedido")
        painel.donoComBarraca(nomeBarraca = nome, email = email)
        val dono = assertNotNull(usuarios.findByEmailIgnoreCase(email))
        val barracaId = assertNotNull(dono.barracaId)
        val agora = Instant.now()
        val categoria = categorias.save(Categoria(barracaId = barracaId, nome = "Bebidas", ordem = 1, criadaEm = agora))

        fun produto(
            nomeProduto: String,
            centavos: Long,
            ordem: Int,
        ) = produtos.save(
            Produto(
                barracaId = barracaId,
                categoriaId = categoria.id,
                nome = nomeProduto,
                precoCentavos = centavos,
                ordem = ordem,
                criadoEm = agora,
                atualizadoEm = agora,
            ),
        )
        return Quiosque(barracaId, dono.id, email, produto("Água de coco", 1200, 1), produto("Pastel", 2500, 2))
    }

    /** Faz o check-in e devolve o token do QR (o que o cliente tem no celular). */
    fun novaComanda(
        q: Quiosque,
        nome: String = "Ana",
    ): String {
        val resultado = checkin.checkin(q.barracaId, AbertaPor(q.usuarioId, null), CheckinRequest(nome = nome, consentimento = true))
        return (resultado as ResultadoCheckin.Ok).detalhe.comanda.tokenAcesso
    }

    fun corpo(
        id: UUID?,
        vararg linhas: Item,
    ): String {
        val itensJson =
            linhas.joinToString(",", "[", "]") {
                val obs = it.observacao?.let { o -> ",\"observacao\":\"$o\"" }.orEmpty()
                """{"produtoId":"${it.produtoId}","quantidade":${it.quantidade}$obs}"""
            }
        return "{" + listOfNotNull(id?.let { "\"id\":\"$it\"" }, "\"itens\":$itensJson").joinToString(",") + "}"
    }

    fun pedir(
        http: HttpTestClient,
        token: String,
        id: UUID?,
        vararg linhas: Item,
    ): HttpResponse<String> = http.postJson("/api/c/$token/pedidos", corpo(id, *linhas))

    fun numero(body: String): Int = assertNotNull(Regex(""""numero":(\d+)""").find(body)).groupValues[1].toInt()
}
