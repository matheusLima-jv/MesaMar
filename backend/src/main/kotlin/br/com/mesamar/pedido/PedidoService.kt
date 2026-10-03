package br.com.mesamar.pedido

import br.com.mesamar.barraca.Barraca
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.barraca.ModoPagamento
import br.com.mesamar.cardapio.CardapioService
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.cardapio.SecaoCardapio
import br.com.mesamar.comanda.Comanda
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.ContadorDiario
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.comanda.TipoContador
import br.com.mesamar.common.Fuso
import br.com.mesamar.common.Money
import br.com.mesamar.eventos.PublicadorEventos
import br.com.mesamar.pagamento.PagamentoRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ItemRequest(
    val produtoId: UUID? = null,
    val quantidade: Int = 0,
    val observacao: String? = null,
    /**
     * O preço que a pessoa viu na tela ao montar o pedido. Se o produto mudou de preço desde então, o pedido é recusado
     * para ela conferir o total novo. Opcional: sem ele (página antiga em cache), vale o preço atual.
     */
    val precoCentavos: Long? = null,
)

data class PedidoRequest(
    /** Gerado no celular: reenviar o mesmo pedido devolve o que já foi criado. */
    val id: UUID? = null,
    val itens: List<ItemRequest> = emptyList(),
    /**
     * A página do cliente já tentou este pedido e não teve resposta. Enquanto ela tentava, o cliente pode ter chamado
     * um atendente, que lançou o mesmo pedido pelo app: nesse caso o servidor devolve o pedido da equipe em vez de
     * criar um segundo (ver [PedidoService.lancadoPelaEquipe]).
     */
    val reenvio: Boolean = false,
)

/** O pedido com as suas linhas. */
data class PedidoDetalhe(
    val pedido: Pedido,
    val itens: List<ItemPedido>,
) {
    val totalCentavos: Long get() = itens.filter { !it.cancelado }.sumOf { it.subtotalCentavos }
}

/** Quem lança o pedido pela equipe: o dono ou um atendente (exatamente um dos dois). */
data class LancadoPor(
    val usuarioId: UUID?,
    val atendenteId: UUID?,
)

/** Motivo pelo qual a comanda não aceita pedido agora, com a mensagem pronta para o cliente. */
data class Bloqueio(
    val codigo: String,
    val mensagem: String,
)

sealed interface ResultadoPedido {
    data class Ok(
        val detalhe: PedidoDetalhe,
        val nova: Boolean,
    ) : ResultadoPedido

    data class Invalido(
        val mensagem: String,
        val campos: Map<String, String>,
    ) : ResultadoPedido

    data class Bloqueado(
        val bloqueio: Bloqueio,
    ) : ResultadoPedido

    /** Algum produto esgotou, saiu do cardápio ou nem é desta barraca. */
    data class Indisponivel(
        val produtoIds: List<UUID>,
        val mensagem: String,
    ) : ResultadoPedido

    /** O preço de algum produto mudou depois que a pessoa montou o pedido: ela confere o total novo e envia de novo. */
    data class PrecoMudou(
        val produtoIds: List<UUID>,
        val mensagem: String,
    ) : ResultadoPedido

    /** O id enviado já é de um pedido de outra comanda (na prática, nunca acontece com UUID aleatório). */
    data object Conflito : ResultadoPedido
}

/**
 * Pedidos feitos pelo cliente. A comanda vem sempre do token (nunca de um id enviado pelo cliente),
 * e todo produto é conferido contra a barraca dela: um id de produto de outra barraca é tratado
 * como se não existisse.
 */
@Service
class PedidoService(
    private val autorHistorico: br.com.mesamar.common.AutorHistorico,
    private val pedidos: PedidoRepository,
    private val itens: ItemPedidoRepository,
    private val produtos: ProdutoRepository,
    private val barracas: BarracaRepository,
    private val comandas: ComandaRepository,
    private val cardapio: CardapioService,
    private val contador: ContadorDiario,
    private val pagamentos: PagamentoRepository,
    private val eventos: ApplicationEventPublisher,
    private val tempoReal: PublicadorEventos,
    private val clock: Clock,
) {
    @Transactional
    fun criar(
        comandaLida: Comanda,
        req: PedidoRequest,
        /** `null`: o cliente pediu pela página dele. Preenchido: a equipe lançou (origem ATENDENTE). */
        equipe: LancadoPor? = null,
    ): ResultadoPedido {
        autorHistorico.marcar()
        require(equipe == null || (equipe.usuarioId == null) != (equipe.atendenteId == null)) { "pedido sem autor ou com dois autores" }
        val id =
            req.id ?: return "Não deu para identificar o pedido. Atualize a página e tente de novo.".let {
                ResultadoPedido.Invalido(it, mapOf("id" to it))
            }

        // Um pedido por vez em cada comanda: status e saldo são conferidos com a comanda travada, então
        // dois pedidos ao mesmo tempo nunca furam o limite de saldo. A comanda lida pelo token pode estar velha.
        val comanda = comandas.travar(comandaLida.id) ?: return ResultadoPedido.Conflito

        // Reenvio do mesmo pedido: devolve o que já existe, mesmo que a comanda tenha mudado desde então.
        pedidos.findById(id).orElse(null)?.let { existente ->
            return if (existente.comandaId == comanda.id) ResultadoPedido.Ok(detalhe(existente), nova = false) else ResultadoPedido.Conflito
        }

        // O cliente chamou um atendente enquanto a página reenviava: o pedido da equipe vale pelos dois
        if (equipe == null &&
            req.reenvio
        ) {
            lancadoPelaEquipe(comanda.id, req.itens)?.let { return ResultadoPedido.Ok(detalhe(it), nova = false) }
        }

        val barraca = barracas.findById(comanda.barracaId).orElseThrow()
        bloqueio(barraca, comanda, saldoCentavos(comanda.id), pelaEquipe = equipe != null)?.let { return ResultadoPedido.Bloqueado(it) }

        val linhas =
            when (val v = validarLinhas(req.itens)) {
                is Linhas.Invalidas -> return ResultadoPedido.Invalido(v.mensagem, v.campos)
                is Linhas.Validas -> v.itens
            }

        val idsPedidos = linhas.map { it.produtoId }.distinct()
        val achados = produtos.findAllById(idsPedidos).filter { it.barracaId == barraca.id }.associateBy { it.id }
        val indisponiveis = idsPedidos.filter { achados[it]?.let { p -> p.ativo && !p.esgotado } != true }
        if (indisponiveis.isNotEmpty()) {
            val nomes = indisponiveis.mapNotNull { achados[it]?.nome }
            val mensagem =
                if (nomes.isEmpty()) {
                    "Um item do pedido saiu do cardápio. Confira o carrinho e envie de novo."
                } else {
                    "Acabou ou saiu do cardápio: ${nomes.joinToString(", ")}. Tire do pedido e envie de novo."
                }
            return ResultadoPedido.Indisponivel(indisponiveis, mensagem)
        }
        // O preço gravado é o de agora (snapshot): se mudou desde que a pessoa viu, ela confirma o total novo antes
        val precoMudou =
            linhas
                .filter { it.precoVisto != null && it.precoVisto != achados.getValue(it.produtoId).precoCentavos }
                .map { it.produtoId }
                .distinct()
        if (precoMudou.isNotEmpty()) {
            val nomes = precoMudou.map { achados.getValue(it).nome }
            return ResultadoPedido.PrecoMudou(
                precoMudou,
                "O preço mudou: ${nomes.joinToString(", ")}. Confira o total novo e envie de novo.",
            )
        }

        val agora = Instant.now(clock)
        val dia = Fuso.hoje(clock)
        val pedido =
            pedidos.save(
                Pedido(
                    id = id,
                    barracaId = barraca.id,
                    comandaId = comanda.id,
                    dia = dia,
                    numeroPedido = contador.proximo(barraca.id, dia, TipoContador.PEDIDO),
                    origem = if (equipe == null) OrigemPedido.CLIENTE else OrigemPedido.ATENDENTE,
                    // "Pagar a cada pedido": só entra na fila depois que o dono confirmar o pagamento (Fase 5)
                    status =
                        if (barraca.modoPagamento == ModoPagamento.POR_PEDIDO) StatusPedido.AGUARDANDO_PAGAMENTO else StatusPedido.RECEBIDO,
                    criadoEm = agora,
                    criadoPorUsuarioId = equipe?.usuarioId,
                    criadoPorAtendenteId = equipe?.atendenteId,
                ),
            )
        val gravados =
            itens.saveAll(
                linhas.mapIndexed { i, linha ->
                    val produto = achados.getValue(linha.produtoId)
                    ItemPedido(
                        pedidoId = pedido.id,
                        barracaId = barraca.id,
                        produtoId = produto.id,
                        linha = i + 1,
                        nomeSnapshot = produto.nome,
                        precoSnapshotCentavos = produto.precoCentavos,
                        quantidade = linha.quantidade,
                        observacao = linha.observacao,
                    )
                },
            )
        // Avisa o app do quiosque (fila) e a página do cliente. O NOTIFY só sai se esta transação der commit.
        tempoReal.pedidoMudou(barraca.id, comanda.id, pedido.id)
        eventos.publishEvent(PedidoCriado(pedido.id, barraca.id, comanda.id, pedido.numeroPedido, pedido.status))
        return ResultadoPedido.Ok(PedidoDetalhe(pedido, gravados), nova = true)
    }

    /** O pedido de uma comanda pelo id, se existir (usado para conferir um reenvio feito pelo link antigo, depois de um "QR novo"). */
    @Transactional(readOnly = true)
    fun pedidoDaComanda(
        comandaId: UUID,
        pedidoId: UUID,
    ): PedidoDetalhe? =
        pedidos
            .findById(pedidoId)
            .orElse(null)
            ?.takeIf { it.comandaId == comandaId }
            ?.let { detalhe(it) }

    /** Cardápio que o cliente vê: só o que está no cardápio (ativo); o esgotado aparece, mas não dá para pedir. */
    @Transactional(readOnly = true)
    fun cardapioDoCliente(barraca: Barraca): List<SecaoCardapio> = cardapio.secoes(barraca).filter { it.noCardapio.isNotEmpty() }

    /** Quanto a comanda ainda deve: o que pediu (itens não cancelados) menos o que o quiosque já confirmou de pagamento. */
    @Transactional(readOnly = true)
    fun saldoCentavos(comandaId: UUID): Long = itens.totalCentavos(comandaId) - pagamentos.totalCentavos(comandaId)

    /** Os pedidos da comanda, do mais antigo para o mais novo, com os itens. */
    @Transactional(readOnly = true)
    fun meusPedidos(comanda: Comanda): List<PedidoDetalhe> {
        val lista = pedidos.findAllByComandaIdOrderByCriadoEmAscNumeroPedidoAsc(comanda.id)
        val itensPorPedido = itens.findAllByPedidoIdInOrderByPedidoIdAscLinhaAsc(lista.map { it.id }).groupBy { it.pedidoId }
        return lista.map { PedidoDetalhe(it, itensPorPedido[it.id].orEmpty()) }
    }

    /**
     * Por que esta comanda não aceita pedido agora (ou `null`, se aceita).
     * Quando é a equipe que lança (`pelaEquipe`), a pausa e o limite de saldo não valem: quem está no balcão decide,
     * e o lançamento feito sem sinal não pode ser recusado horas depois (ADR 0017). O resto continua valendo.
     */
    fun bloqueio(
        barraca: Barraca,
        comanda: Comanda,
        saldoCentavos: Long,
        pelaEquipe: Boolean = false,
    ): Bloqueio? {
        val limite = barraca.limiteSaldoAbertoCentavos
        return when {
            !barraca.ativa -> Bloqueio("barraca-inativa", "Esta barraca não está atendendo pelo MesaMar agora. Fale com um atendente.")
            // Comanda de um dia anterior (o cliente foi embora devendo): não aceita pedido novo, mas o quiosque ainda recebe o que ela deve
            (comanda.status == StatusComanda.ABERTA || comanda.status == StatusComanda.AGUARDANDO_PAGAMENTO) &&
                comanda.dia < Fuso.hoje(clock) ->
                Bloqueio(
                    "comanda-de-outro-dia",
                    "Esta comanda é de outro dia e já não aceita pedidos. Se ainda há conta a pagar, fale com um atendente.",
                )
            comanda.status == StatusComanda.AGUARDANDO_PAGAMENTO ->
                Bloqueio(
                    "aguardando-pagamento",
                    "Sua conta está sendo fechada. Para pedir mais, abra a aba Conta e toque em Voltar e pedir mais.",
                )
            comanda.status == StatusComanda.BLOQUEADA ->
                Bloqueio("comanda-bloqueada", "Sua comanda está bloqueada. Fale com um atendente.")
            comanda.status == StatusComanda.FECHADA ->
                Bloqueio("comanda-fechada", "Esta comanda foi encerrada. Obrigado pela visita!")
            comanda.status == StatusComanda.CANCELADA -> Bloqueio("comanda-cancelada", "Esta comanda foi cancelada. Fale com um atendente.")
            barraca.pedidosPausados && !pelaEquipe ->
                Bloqueio(
                    "pedidos-pausados",
                    barraca.mensagemPausa?.let { "O quiosque pausou os pedidos: $it" }
                        ?: "O quiosque pausou os pedidos por agora. Chame um atendente ou tente de novo daqui a pouco.",
                )
            // Limite atingido: a conta chegou ao valor que o dono definiu, então paga uma parte antes de pedir mais
            limite != null && saldoCentavos >= limite && !pelaEquipe ->
                Bloqueio("limite-saldo", "Sua conta chegou a ${Money.format(limite)}. Para pedir mais, pague uma parte com um atendente.")
            else -> null
        }
    }

    /**
     * Um pedido lançado pela equipe nesta comanda, na última [JANELA_CONCILIACAO], com exatamente os mesmos produtos
     * e quantidades (a observação não conta), e que não foi cancelado. Só vale para reenvios: um pedido igual feito de
     * propósito pelo cliente, que chega na primeira tentativa, nunca é confundido com o da equipe.
     */
    private fun lancadoPelaEquipe(
        comandaId: UUID,
        recebidos: List<ItemRequest>,
    ): Pedido? {
        if (recebidos.any { it.produtoId == null }) return null
        val quer = recebidos.groupBy { it.produtoId!! }.mapValues { (_, l) -> l.sumOf { it.quantidade } }
        if (quer.isEmpty()) return null
        val desde = Instant.now(clock).minus(JANELA_CONCILIACAO)
        return pedidos
            .findAllByComandaIdOrderByCriadoEmAscNumeroPedidoAsc(comandaId)
            .filter { it.origem == OrigemPedido.ATENDENTE && it.status != StatusPedido.CANCELADO && !it.criadoEm.isBefore(desde) }
            .lastOrNull { p ->
                itens.findAllByPedidoIdOrderByLinha(p.id).groupBy { it.produtoId }.mapValues { (_, l) -> l.sumOf { it.quantidade } } == quer
            }
    }

    private fun detalhe(p: Pedido) = PedidoDetalhe(p, itens.findAllByPedidoIdOrderByLinha(p.id))

    private data class Linha(
        val produtoId: UUID,
        val quantidade: Int,
        val observacao: String?,
        val precoVisto: Long?,
    )

    private sealed interface Linhas {
        data class Validas(
            val itens: List<Linha>,
        ) : Linhas

        data class Invalidas(
            val mensagem: String,
            val campos: Map<String, String>,
        ) : Linhas
    }

    private fun validarLinhas(recebidas: List<ItemRequest>): Linhas {
        fun erro(
            campo: String,
            mensagem: String,
        ) = Linhas.Invalidas(mensagem, mapOf(campo to mensagem))
        if (recebidas.isEmpty()) return erro("itens", "Escolha pelo menos um item.")
        if (recebidas.size > MAX_LINHAS) return erro("itens", "São itens demais num pedido só. Envie em dois pedidos.")
        val validas = mutableListOf<Linha>()
        for ((i, r) in recebidas.withIndex()) {
            val produtoId = r.produtoId ?: return erro("itens[$i]", "Item sem identificação. Atualize a página e tente de novo.")
            if (r.quantidade !in
                1..MAX_QUANTIDADE
            ) {
                return erro("itens[$i].quantidade", "Peça de 1 a $MAX_QUANTIDADE unidades de cada item.")
            }
            val observacao =
                r.observacao
                    ?.trim()
                    ?.replace(Regex("\\s+"), " ")
                    ?.ifEmpty { null }
            if ((observacao?.length ?: 0) >
                OBSERVACAO_MAX
            ) {
                return erro("itens[$i].observacao", "A observação pode ter até $OBSERVACAO_MAX letras.")
            }
            validas += Linha(produtoId, r.quantidade, observacao, r.precoCentavos)
        }
        if (validas.sumOf { it.quantidade } >
            MAX_UNIDADES
        ) {
            return erro("itens", "São unidades demais num pedido só. Envie em dois pedidos.")
        }
        return Linhas.Validas(validas)
    }

    companion object {
        /** Até quanto tempo depois um reenvio do cliente ainda é reconhecido como o pedido que a equipe lançou. */
        val JANELA_CONCILIACAO: java.time.Duration = java.time.Duration.ofMinutes(60)
        const val MAX_LINHAS = 30
        const val MAX_QUANTIDADE = 20
        const val MAX_UNIDADES = 60
        const val OBSERVACAO_MAX = 80
    }
}
