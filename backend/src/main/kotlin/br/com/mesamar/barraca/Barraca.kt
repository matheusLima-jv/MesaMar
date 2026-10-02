package br.com.mesamar.barraca

import br.com.mesamar.common.Praia
import br.com.mesamar.common.TipoChavePix
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

enum class ModoPagamento(
    val label: String,
    val descricao: String,
) {
    FINAL("No final", "O cliente pede à vontade e paga tudo ao fechar a conta."),
    POR_PEDIDO("A cada pedido", "O pedido só entra na fila depois que você confirma o pagamento dele."),
}

@Entity
@Table(name = "barraca")
class Barraca(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 80)
    var nome: String,
    @Column(nullable = false, length = 60, unique = true)
    var slug: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var praia: Praia,
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_chave_pix", length = 12)
    var tipoChavePix: TipoChavePix? = null,
    @Column(name = "chave_pix", length = 77)
    var chavePix: String? = null,
    @Column(name = "nome_recebedor_pix", length = 25)
    var nomeRecebedorPix: String? = null,
    @Column(name = "cidade_pix", length = 15)
    var cidadePix: String? = null,
    @Column(name = "limite_guarda_sois", nullable = false)
    var limiteGuardaSois: Int = 30,
    @Enumerated(EnumType.STRING)
    @Column(name = "modo_pagamento", nullable = false, length = 12)
    var modoPagamento: ModoPagamento = ModoPagamento.FINAL,
    @Column(name = "limite_saldo_aberto_centavos")
    var limiteSaldoAbertoCentavos: Long? = null,
    @Column(name = "tempo_alerta_pedido_min", nullable = false)
    var tempoAlertaPedidoMin: Int = 10,
    @Column(name = "pedidos_pausados", nullable = false)
    var pedidosPausados: Boolean = false,
    /** Recado opcional que o cliente vê enquanto os pedidos estão pausados (ex.: "Voltamos em 10 minutos"). */
    @Column(name = "mensagem_pausa", length = 80)
    var mensagemPausa: String? = null,
    @Column(nullable = false)
    var ativa: Boolean = true,
    /** A barraca de demonstração (treino): nada nela é de verdade, e o painel, o app e a página do cliente avisam. */
    @Column(nullable = false)
    val demo: Boolean = false,
    @Column(name = "criada_em", nullable = false)
    val criadaEm: Instant,
    @Column(name = "atualizada_em", nullable = false)
    var atualizadaEm: Instant,
) {
    val temPix: Boolean get() = chavePix != null
}

interface BarracaRepository : JpaRepository<Barraca, UUID> {
    fun existsBySlug(slug: String): Boolean

    fun findBySlugAndAtivaTrue(slug: String): Barraca?
}
