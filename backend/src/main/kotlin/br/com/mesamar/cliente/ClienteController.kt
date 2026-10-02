package br.com.mesamar.cliente

import br.com.mesamar.barraca.Barraca
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.comanda.ClienteRepository
import br.com.mesamar.comanda.Comanda
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.common.Fuso
import br.com.mesamar.common.RateLimiter
import br.com.mesamar.eventos.HubEventos
import br.com.mesamar.guardasol.GuardaSolRepository
import br.com.mesamar.pagamento.Extrato
import br.com.mesamar.pagamento.FechamentoService
import br.com.mesamar.pagamento.MetodoPagamento
import br.com.mesamar.pagamento.ResultadoConta
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
import jakarta.servlet.http.HttpServletResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
    /** Barraca de treino: a página mostra um aviso de que nada ali é de verdade. */
    val demo: Boolean,
    val cardapio: List<SecaoView>,
)

/** "Meus pedidos": do mais novo para o mais antigo, com o saldo atual da conta. */
data class MeusPedidosView(
    val saldoCentavos: Long,
    val pedidos: List<PedidoView>,
)

data class ItemPedidoView(
    /** Identifica a linha na página (para o aviso de cancelamento aparecer uma vez só). */
    val id: UUID,
    val produtoId: UUID,
    val nome: String,
    val precoCentavos: Long,
    val quantidade: Int,
    val observacao: String?,
    val subtotalCentavos: Long,
    /** Item que o quiosque cancelou: continua na lista, mas não entra no total nem no saldo. */
    val cancelado: Boolean,
    /** Por que o quiosque cancelou (ex.: "Esgotou"). */
    val motivoCancelamento: String?,
)

data class PedidoView(
    val id: UUID,
    val numero: Int,
    val status: String,
    val hora: String,
    val itens: List<ItemPedidoView>,
    val totalCentavos: Long,
    /** Quando o pedido todo foi cancelado, por quê. */
    val motivoCancelamento: String?,
) {
    companion object {
        val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(Fuso.ZONA)

        fun of(d: PedidoDetalhe) =
            PedidoView(
                id = d.pedido.id,
                numero = d.pedido.numeroPedido,
                status = d.pedido.status.name,
                hora = HORA.format(d.pedido.criadoEm),
                itens =
                    d.itens.map {
                        ItemPedidoView(
                            it.id,
                            it.produtoId,
                            it.nomeSnapshot,
                            it.precoSnapshotCentavos,
                            it.quantidade,
                            it.observacao,
                            it.subtotalCentavos,
                            it.cancelado,
                            it.motivoCancelamento,
                        )
                    },
                totalCentavos = d.totalCentavos,
                motivoCancelamento = d.pedido.motivoCancelamento,
            )
    }
}

data class PixView(
    /** O "copia e cola" do Pix; o mesmo texto vira o QR na página. */
    val codigo: String,
    val chave: String,
    val nomeRecebedor: String,
    val cidade: String,
    val valorCentavos: Long,
)

data class PagamentoView(
    val metodo: String,
    val rotulo: String,
    val valorCentavos: Long,
    /** Dinheiro: quanto o cliente entregou, para mostrar o troco no extrato. */
    val valorRecebidoCentavos: Long?,
    val hora: String,
)

/** O extrato da conta e em que ponto do fechamento ela está. */
data class ContaView(
    /** `ABERTA`, `AGUARDANDO_PAGAMENTO`, `FECHADA`, `CANCELADA` ou `BLOQUEADA`. */
    val status: String,
    val pedidoCentavos: Long,
    val pagoCentavos: Long,
    val saldoCentavos: Long,
    /** A forma que o cliente escolheu ao fechar (`PIX`, `DINHEIRO` ou `CARTAO`); `null` com a conta aberta. */
    val metodo: String?,
    val trocoParaCentavos: Long?,
    /** Hora em que o cliente avisou o quiosque (dinheiro e cartão: ao fechar; Pix: no "já paguei"). */
    val avisadoEm: String?,
    /** Só no Pix aguardando pagamento. `null` se o quiosque não tem Pix configurado. */
    val pix: PixView?,
    /** O quiosque tem Pix configurado? A página só oferece Pix se sim. */
    val aceitaPix: Boolean,
    val pedidos: List<PedidoView>,
    val pagamentos: List<PagamentoView>,
) {
    companion object {
        fun of(
            e: Extrato,
            aceitaPix: Boolean,
        ) = ContaView(
            status = e.comanda.status.name,
            pedidoCentavos = e.pedidoCentavos,
            pagoCentavos = e.pagoCentavos,
            saldoCentavos = e.saldoCentavos,
            metodo = e.comanda.metodoPagamentoPretendido?.name,
            trocoParaCentavos = e.comanda.trocoParaCentavos,
            avisadoEm = e.comanda.avisoPagamentoEm?.let { PedidoView.HORA.format(it) },
            pix = e.pix?.let { PixView(it.codigo, it.chave, it.nomeRecebedor, it.cidade, it.valorCentavos) },
            aceitaPix = aceitaPix,
            pedidos = e.pedidos.map(PedidoView::of),
            pagamentos =
                e.pagamentos.map {
                    PagamentoView(
                        it.metodo.name,
                        it.metodo.rotulo,
                        it.valorCentavos,
                        it.valorRecebidoCentavos,
                        PedidoView.HORA.format(it.confirmadoEm),
                    )
                },
        )
    }
}

/** O que o cliente manda ao fechar a conta. */
data class FecharContaRequest(
    val metodo: String? = null,
    /** Dinheiro: "vou pagar com R$ ___" (opcional). */
    val trocoParaCentavos: Long? = null,
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
    private val conta: FechamentoService,
    private val hub: HubEventos,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    @GetMapping
    fun comanda(
        @PathVariable token: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val comanda = comandaVigente(token) ?: return recusado(request)
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
                demo = barraca.demo,
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
        val comanda = comandaVigente(token) ?: return recusado(request)
        if (!rateLimiter.tryAcquire("c-geral:${comanda.id}", LIMITE_GERAL, JANELA_COMANDA)) return muitas()
        return ResponseEntity.ok(
            MeusPedidosView(
                saldoCentavos = pedidos.saldoCentavos(comanda.id),
                pedidos = pedidos.meusPedidos(comanda).reversed().map(PedidoView::of),
            ),
        )
    }

    /**
     * Tempo real da comanda: avisa (evento `pedido`) quando um pedido dela muda de status ou tem item cancelado, e a
     * página busca "Meus pedidos" de novo. Token inexistente ou uso demais: sem corpo, para o navegador parar de insistir.
     */
    @GetMapping("/eventos")
    fun eventos(
        @PathVariable token: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<SseEmitter> {
        val comanda = comandaVigente(token)
        if (comanda == null) {
            val dentro = rateLimiter.tryAcquire("c-invalido:${request.remoteAddr}", LIMITE_TOKEN_INVALIDO, JANELA_TOKEN_INVALIDO)
            return ResponseEntity.status(if (dentro) HttpStatus.NOT_FOUND else HttpStatus.TOO_MANY_REQUESTS).build()
        }
        if (!rateLimiter.tryAcquire("c-eventos:${comanda.id}", LIMITE_EVENTOS, JANELA_COMANDA)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "30").build()
        }
        response.setHeader("X-Accel-Buffering", "no")
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(hub.assinarComanda(comanda.id, comanda.barracaId))
    }

    /** O extrato e o ponto do fechamento: itens, pagamentos, saldo e, no Pix aguardando, o código para pagar. */
    @GetMapping("/conta")
    fun extrato(
        @PathVariable token: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val comanda = comandaVigente(token) ?: return recusado(request)
        if (!rateLimiter.tryAcquire("c-geral:${comanda.id}", LIMITE_GERAL, JANELA_COMANDA)) return muitas()
        return respostaConta(ResultadoConta.Ok(conta.extrato(comanda)))
    }

    /** "Fechar conta": a comanda passa a aguardar o pagamento e novos pedidos ficam bloqueados. Pode repetir para trocar a forma. */
    @PostMapping("/conta/fechar")
    fun fechar(
        @PathVariable token: String,
        @RequestBody body: FecharContaRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val comanda = comandaVigente(token) ?: return recusado(request)
        if (!rateLimiter.tryAcquire("c-conta:${comanda.id}", LIMITE_CONTA, JANELA_COMANDA)) return muitas()
        val metodo = MetodoPagamento.fromCode(body.metodo)
        if (metodo == null && body.metodo != null) {
            val mensagem = "Forma de pagamento desconhecida."
            return ResponseEntity.badRequest().body(ErroValidacaoView("validacao", mensagem, mapOf("metodo" to mensagem)))
        }
        return respostaConta(conta.fechar(comanda, metodo, body.trocoParaCentavos))
    }

    /** Pix: "já paguei". Avisa o quiosque para conferir no app do banco. */
    @PostMapping("/conta/pix-pago")
    fun pixPago(
        @PathVariable token: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val comanda = comandaVigente(token) ?: return recusado(request)
        if (!rateLimiter.tryAcquire("c-conta:${comanda.id}", LIMITE_CONTA, JANELA_COMANDA)) return muitas()
        return respostaConta(conta.jaPaguei(comanda))
    }

    /** "Voltar e pedir mais": desiste do fechamento, desde que nenhum pagamento tenha sido confirmado. */
    @PostMapping("/conta/reabrir")
    fun reabrir(
        @PathVariable token: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val comanda = comandaVigente(token) ?: return recusado(request)
        if (!rateLimiter.tryAcquire("c-conta:${comanda.id}", LIMITE_CONTA, JANELA_COMANDA)) return muitas()
        return respostaConta(conta.reabrir(comanda))
    }

    private fun respostaConta(r: ResultadoConta): ResponseEntity<Any> =
        when (r) {
            is ResultadoConta.Ok -> {
                val aceitaPix = barracas.findById(r.extrato.comanda.barracaId).orElseThrow().temPix
                ResponseEntity.ok(ContaView.of(r.extrato, aceitaPix))
            }
            is ResultadoConta.Invalido ->
                ResponseEntity.badRequest().body(ErroValidacaoView("validacao", r.mensagem, mapOf(r.campo to r.mensagem)))
            is ResultadoConta.Recusado -> ResponseEntity.status(HttpStatus.CONFLICT).body(ErroView(r.codigo, r.mensagem))
        }

    @PostMapping("/pedidos")
    fun pedir(
        @PathVariable token: String,
        @RequestBody body: PedidoRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val comanda = comandaVigente(token) ?: return recusado(request)
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
                ResponseEntity.status(if (resultado.nova) HttpStatus.CREATED else HttpStatus.OK).body(PedidoView.of(resultado.detalhe))
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

    /**
     * A comanda do token, ou `null` se o token não existe **ou o extrato venceu**: depois de [EXTRATO_DIAS] dias da
     * conta paga, o link deixa de valer e dá o mesmo 404 de um token que nunca existiu.
     */
    private fun comandaVigente(token: String): Comanda? {
        val comanda = comandas.findByTokenAcesso(token) ?: return null
        val fechadaEm = comanda.fechadaEm
        if (comanda.status == StatusComanda.FECHADA &&
            fechadaEm != null &&
            fechadaEm.plus(Duration.ofDays(EXTRATO_DIAS)) < Instant.now(clock)
        ) {
            return null
        }
        return comanda
    }

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

    companion object {
        const val LIMITE_GERAL = 90
        const val LIMITE_PEDIDOS = 20
        const val LIMITE_CONTA = 20

        /** Por quantos dias, depois de paga, a conta continua abrindo para o cliente rever o extrato. */
        const val EXTRATO_DIAS = 7L
        const val LIMITE_EVENTOS = 30
        const val LIMITE_TOKEN_INVALIDO = 30
        val JANELA_COMANDA: Duration = Duration.ofMinutes(1)
        val JANELA_TOKEN_INVALIDO: Duration = Duration.ofMinutes(10)
    }
}
