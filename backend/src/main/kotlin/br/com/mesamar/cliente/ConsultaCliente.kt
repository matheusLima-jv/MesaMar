package br.com.mesamar.cliente

import br.com.mesamar.barraca.Barraca
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.comanda.ClienteRepository
import br.com.mesamar.comanda.Comanda
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.guardasol.GuardaSolRepository
import br.com.mesamar.pagamento.FechamentoService
import br.com.mesamar.pedido.PedidoService
import br.com.mesamar.staff.ProdutoView
import br.com.mesamar.staff.SecaoView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Uma tela, um snapshot: validação do link e todos os componentes compartilham a mesma transação. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class ConsultaCliente(
    private val comandas: ComandaRepository,
    private val clientes: ClienteRepository,
    private val guardaSois: GuardaSolRepository,
    private val barracas: BarracaRepository,
    private val pedidos: PedidoService,
    private val fechamento: FechamentoService,
    private val clock: Clock,
) {
    fun vigente(token: String): Comanda? = comandas.findByTokenAcesso(token)?.takeUnless(::extratoVencido)

    /** A comanda cujo QR foi trocado, pelo link antigo, com o mesmo prazo do extrato: depois dele, nem o link antigo abre. */
    fun vigentePeloLinkAntigo(token: String): Comanda? = comandas.findByTokenAnterior(token)?.takeUnless(::extratoVencido)

    private fun extratoVencido(c: Comanda): Boolean =
        c.status == StatusComanda.FECHADA &&
            c.fechadaEm?.plus(Duration.ofDays(ClienteController.EXTRATO_DIAS))?.isBefore(Instant.now(clock)) == true

    fun comanda(token: String): ComandaClienteView? {
        val comanda = vigente(token) ?: return null
        val barraca = barracas.findById(comanda.barracaId).orElseThrow()
        val cliente = clientes.findById(comanda.clienteId).orElseThrow()
        val guardaSol = comanda.guardaSolId?.let { guardaSois.findById(it).orElse(null) }
        val saldo = pedidos.saldoCentavos(comanda.id)
        return ComandaClienteView(
            barraca = barraca.nome,
            nome = cliente.nome,
            guardaSol = guardaSol?.numero,
            bloqueio = pedidos.bloqueio(barraca, comanda, saldo)?.let(BloqueioView::of),
            saldoCentavos = saldo,
            modoPagamento = barraca.modoPagamento.name,
            demo = barraca.demo,
            cardapio = secoes(barraca),
        )
    }

    fun pedidos(token: String): MeusPedidosView? {
        val c = vigente(token) ?: return null
        return MeusPedidosView(pedidos.saldoCentavos(c.id), pedidos.meusPedidos(c).reversed().map(PedidoView::of))
    }

    fun conta(token: String): ContaView? {
        val c = vigente(token) ?: return null
        val barraca = barracas.findById(c.barracaId).orElseThrow()
        return ContaView.of(fechamento.extrato(c), barraca.temPix)
    }

    private fun secoes(barraca: Barraca): List<SecaoView> =
        pedidos.cardapioDoCliente(barraca).map { s ->
            SecaoView(s.categoria.id, s.categoria.nome, s.noCardapio.map(ProdutoView::of))
        }
}
