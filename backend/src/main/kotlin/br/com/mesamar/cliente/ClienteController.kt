package br.com.mesamar.cliente

import br.com.mesamar.barraca.Barraca
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.comanda.ClienteRepository
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.common.Fuso
import br.com.mesamar.common.RateLimiter
import br.com.mesamar.guardasol.GuardaSolRepository
import br.com.mesamar.pedido.Bloqueio
import br.com.mesamar.pedido.PedidoDetalhe
import br.com.mesamar.pedido.PedidoRequest
import br.com.mesamar.pedido.PedidoService
import br.com.mesamar.pedido.ResultadoPedido
import br.com.mesamar.staff.ErroValidacaoView
import br.com.mesamar.staff.ErroView
import br.com.mesamar.staff.ProdutoView
import br.com.mesamar.staff.SecaoView
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.UUID

data class BloqueioView(
    val codigo: String,
    val mensagem: String,
) {
    companion object {
        fun of(b: Bloqueio) = BloqueioView(b.codigo, b.mensagem)
    }
}

/** Tudo que a página do cliente precisa para abrir: quem é, onde está, o cardápio e se dá para pedir. */
data class ComandaClienteView(
    val barraca: String,
    val nome: String,
    val guardaSol: Int?,
    /** `null` quando a comanda aceita pedidos. */
    val bloqueio: BloqueioView?,
    /** Quanto a conta já soma (itens não cancelados), em centavos. */
    val saldoCentavos: Long,
    /** `FINAL` (paga ao fechar a conta) ou `POR_PEDIDO` (o pedido só entra na fila depois de pago). */
    val modoPagamento: String,
    val cardapio: List<SecaoView>,
)

/** "Meus pedidos": do mais novo para o mais antigo, com o saldo atual da conta. */
data class MeusPedidosView(
    val saldoCentavos: Long,
    val pedidos: List<PedidoView>,
)

data class ItemPedidoView(
    val produtoId: UUID,
    val nome: String,
    val precoCentavos: Long,
    val quantidade: Int,
    val observacao: String?,
    val subtotalCentavos: Long,
    /** Item que o quiosque cancelou: continua na lista, mas não entra no total nem no saldo. */
    val cancelado: Boolean,
)

data class PedidoView(
    val id: UUID,
    val numero: Int,
    val status: String,
    val hora: String,
    val itens: List<ItemPedidoView>,
    val totalCentavos: Long,
)

/** Erro de pedido com a lista de produtos que não dá para pedir (a página tira do carrinho). */
data class ErroIndisponivelView(
    val erro: String,
    val mensagem: String,
    val produtos: List<UUID>,
)

/**
 * API da página do cliente (/api/c/{token}). **Sem sessão e sem cookies:** o token do QR é a única
 * credencial. A comanda, e por ela a barraca, vem sempre do token; nenhum endpoint recebe id de
 * barraca ou de comanda. Token desconhecido dá 404, igual para qualquer valor (não revela nada).
 */
@RestController
@RequestMapping("/api/c/{token}")
class ClienteController(
    private val comandas: ComandaRepository,
    private val clientes: ClienteRepository,
    private val guardaSois: GuardaSolRepository,
    private val barracas: BarracaRepository,
    private val pedidos: PedidoService,
    private val rateLimiter: RateLimiter,
) {
    @GetMapping
    fun comanda(
        @PathVariable token: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val comanda = comandas.findByTokenAcesso(token) ?: return recusado(request)
        if (!rateLimiter.tryAcquire("c-geral:${comanda.id}", LIMITE_GERAL, JANELA_COMANDA)) return muitas()
        val barraca = barracas.findById(comanda.barracaId).orElseThrow()
        val cliente = clientes.findById(comanda.clienteId).orElseThrow()
        val guardaSol = comanda.guardaSolId?.let { guardaSois.findById(it).orElse(null) }
        val saldo = pedidos.saldoCentavos(comanda.id)
        return ResponseEntity.ok(
            ComandaClienteView(
                barraca = barraca.nome,
                nome = cliente.nome,
                guardaSol = guardaSol?.numero,
                bloqueio = pedidos.bloqueio(barraca, comanda, saldo)?.let(BloqueioView::of),
                saldoCentavos = saldo,
                modoPagamento = barraca.modoPagamento.name,
                cardapio = secoes(barraca),
            ),
        )
    }

    /** Os pedidos da comanda do token e o saldo. A página consulta isto a cada 15 s na tela "Meus pedidos". */
    @GetMapping("/pedidos")
    fun meusPedidos(
        @PathVariable token: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val comanda = comandas.findByTokenAcesso(token) ?: return recusado(request)
        if (!rateLimiter.tryAcquire("c-geral:${comanda.id}", LIMITE_GERAL, JANELA_COMANDA)) return muitas()
        return ResponseEntity.ok(
            MeusPedidosView(
                saldoCentavos = pedidos.saldoCentavos(comanda.id),
                pedidos = pedidos.meusPedidos(comanda).reversed().map(::view),
            ),
        )
    }

    @PostMapping("/pedidos")
    fun pedir(
        @PathVariable token: String,
        @RequestBody body: PedidoRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val comanda = comandas.findByTokenAcesso(token) ?: return recusado(request)
        if (!rateLimiter.tryAcquire("c-pedido:${comanda.id}", LIMITE_PEDIDOS, JANELA_COMANDA)) return muitas()
        val resultado =
            try {
                pedidos.criar(comanda, body)
            } catch (e: DataIntegrityViolationException) {
                // Dois envios do mesmo pedido ao mesmo tempo: o segundo encontra o primeiro.
                if (body.id == null) throw e
                pedidos.criar(comanda, body)
            }
        return when (resultado) {
            is ResultadoPedido.Ok ->
                ResponseEntity.status(if (resultado.nova) HttpStatus.CREATED else HttpStatus.OK).body(view(resultado.detalhe))
            is ResultadoPedido.Invalido ->
                ResponseEntity.badRequest().body(ErroValidacaoView("validacao", resultado.mensagem, resultado.campos))
            is ResultadoPedido.Bloqueado ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(ErroView(resultado.bloqueio.codigo, resultado.bloqueio.mensagem))
            is ResultadoPedido.Indisponivel ->
                ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErroIndisponivelView("indisponivel", resultado.mensagem, resultado.produtoIds))
            ResultadoPedido.Conflito ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(ErroView("conflito", "Não deu para enviar esse pedido. Tente de novo."))
        }
    }

    /** JSON malformado ou de tipo errado (ex.: quantidade "abc"): 400 no mesmo formato dos outros erros. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun jsonInvalido(): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ErroView("validacao", "Não entendemos esse pedido. Atualize a página e tente de novo."))

    /** Token que não existe: 404 igual para qualquer valor, e quem insiste de um mesmo IP é freado. */
    private fun recusado(request: HttpServletRequest): ResponseEntity<Any> {
        if (!rateLimiter.tryAcquire("c-invalido:${request.remoteAddr}", LIMITE_TOKEN_INVALIDO, JANELA_TOKEN_INVALIDO)) return muitas()
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErroView("comanda", "Esse link não vale mais. Peça um QR novo ao atendente."))
    }

    private fun muitas(): ResponseEntity<Any> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "30")
            .body(ErroView("limite", "Muitas tentativas seguidas. Espere um pouco e tente de novo."))

    private fun secoes(barraca: Barraca): List<SecaoView> =
        pedidos.cardapioDoCliente(barraca).map { s ->
            SecaoView(s.categoria.id, s.categoria.nome, s.noCardapio.map(ProdutoView::of))
        }

    private fun view(d: PedidoDetalhe) =
        PedidoView(
            id = d.pedido.id,
            numero = d.pedido.numeroPedido,
            status = d.pedido.status.name,
            hora = HORA.format(d.pedido.criadoEm),
            itens =
                d.itens.map {
                    ItemPedidoView(
                        it.produtoId,
                        it.nomeSnapshot,
                        it.precoSnapshotCentavos,
                        it.quantidade,
                        it.observacao,
                        it.subtotalCentavos,
                        it.cancelado,
                    )
                },
            totalCentavos = d.totalCentavos,
        )

    companion object {
        const val LIMITE_GERAL = 90
        const val LIMITE_PEDIDOS = 20
        const val LIMITE_TOKEN_INVALIDO = 30
        val JANELA_COMANDA: Duration = Duration.ofMinutes(1)
        val JANELA_TOKEN_INVALIDO: Duration = Duration.ofMinutes(10)
        private val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(Fuso.ZONA)
    }
}
