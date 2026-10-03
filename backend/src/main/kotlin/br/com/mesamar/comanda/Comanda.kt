package br.com.mesamar.comanda

import br.com.mesamar.pagamento.MetodoPagamento
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Quem fez o check-in no quiosque: cadastro breve, com consentimento (LGPD). */
@Entity
@Table(name = "cliente")
class Cliente(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "barraca_id", nullable = false)
    val barracaId: UUID,
    @Column(nullable = false, length = 40)
    var nome: String,
    @Column(name = "whatsapp_e164", length = 16)
    var whatsappE164: String? = null,
    @Column(name = "consentimento_em", nullable = false)
    val consentimentoEm: Instant,
    /** Versão do texto de consentimento que o atendente leu para o cliente (docs/consentimento.md). `null`: anterior ao registro. */
    @Column(name = "consentimento_versao", length = 30)
    val consentimentoVersao: String? = null,
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
    @Column(name = "anonimizado_em")
    var anonimizadoEm: Instant? = null,
)

enum class StatusComanda {
    ABERTA,
    AGUARDANDO_PAGAMENTO,
    FECHADA,
    CANCELADA,
    BLOQUEADA,
    ;

    companion object {
        /** Comandas que ainda aparecem na lista de clientes do app. */
        val ATIVAS = listOf(ABERTA, AGUARDANDO_PAGAMENTO, BLOQUEADA)
    }
}

/**
 * A conta de um cliente na barraca. O `tokenAcesso` vai no QR e é a única credencial do cliente:
 * quem tem o link acessa a comanda, por isso ele é aleatório (128 bits) e nunca sequencial.
 */
@Entity
@Table(name = "comanda")
class Comanda(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "barraca_id", nullable = false)
    val barracaId: UUID,
    @Column(name = "cliente_id", nullable = false)
    val clienteId: UUID,
    @Column(name = "guarda_sol_id")
    var guardaSolId: UUID? = null,
    @Column(nullable = false)
    val dia: LocalDate,
    @Column(name = "numero_chegada", nullable = false)
    val numeroChegada: Int,
    @Column(name = "token_acesso", nullable = false, length = 43, unique = true)
    var tokenAcesso: String,
    /** O link de antes de um "QR novo": não abre mais a comanda (só ajuda a página antiga a conferir um pedido pendente). */
    @Column(name = "token_anterior", length = 43)
    var tokenAnterior: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var status: StatusComanda = StatusComanda.ABERTA,
    @Column(name = "aberta_por_usuario_id")
    val abertaPorUsuarioId: UUID? = null,
    @Column(name = "aberta_por_atendente_id")
    val abertaPorAtendenteId: UUID? = null,
    @Column(name = "aberta_em", nullable = false)
    val abertaEm: Instant,
    @Column(name = "fechada_em")
    var fechadaEm: Instant? = null,
    /** Como o cliente escolheu pagar ao fechar a conta. Só tem valor enquanto a comanda aguarda o pagamento. */
    @Enumerated(EnumType.STRING)
    @Column(name = "metodo_pagamento_pretendido", length = 10)
    var metodoPagamentoPretendido: MetodoPagamento? = null,
    /** Dinheiro: "vou pagar com R$ 100,00", para o dono separar o troco. */
    @Column(name = "troco_para_centavos")
    var trocoParaCentavos: Long? = null,
    /** Quando o cliente avisou que está indo pagar (ou que já pagou o Pix). Ordena a aba Pagamentos. */
    @Column(name = "aviso_pagamento_em")
    var avisoPagamentoEm: Instant? = null,
    /** Conta encerrada pelo dono com saldo pendente: por quê, quanto ficou sem pagar e quem fechou (auditoria). */
    @Column(name = "fechamento_forcado_motivo", length = 120)
    var fechamentoForcadoMotivo: String? = null,
    @Column(name = "saldo_perdoado_centavos")
    var saldoPerdoadoCentavos: Long? = null,
    @Column(name = "fechada_por_usuario_id")
    var fechadaPorUsuarioId: UUID? = null,
)

interface ClienteRepository : JpaRepository<Cliente, UUID>

interface ComandaRepository : JpaRepository<Comanda, UUID> {
    fun findByIdAndBarracaId(
        id: UUID,
        barracaId: UUID,
    ): Comanda?

    fun findByTokenAcesso(tokenAcesso: String): Comanda?

    fun findByTokenAnterior(tokenAnterior: String): Comanda?

    /**
     * Trava a linha da comanda até o fim da transação: dois pedidos da mesma comanda ao mesmo tempo
     * entram um por vez, então o limite de saldo nunca é furado por uma corrida. Devolve também o
     * status mais recente (a comanda lida antes pode ter mudado).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Comanda c where c.id = :id")
    fun travar(
        @Param("id") id: UUID,
    ): Comanda?

    /** Ordem de chegada: horário do servidor; no empate, o número de chegada. */
    @Query(
        "select c from Comanda c where c.barracaId = :barracaId and c.status in :status order by c.abertaEm, c.numeroChegada, c.id",
    )
    fun findAtivas(
        @Param("barracaId") barracaId: UUID,
        @Param("status") status: Collection<StatusComanda>,
        pageable: org.springframework.data.domain.Pageable,
    ): List<Comanda>

    fun countByBarracaId(barracaId: UUID): Long
}

enum class TipoContador { CHEGADA, PEDIDO }

/**
 * Números sequenciais por barraca e dia (nº de chegada, nº do pedido). O incremento é atômico no
 * banco: o UPSERT trava a linha até o fim da transação, então dois check-ins ao mesmo tempo nunca
 * pegam o mesmo número, e um check-in que falha devolve o número (rollback).
 */
@Component
class ContadorDiario(
    private val jdbc: JdbcTemplate,
) {
    fun proximo(
        barracaId: UUID,
        dia: LocalDate,
        tipo: TipoContador,
    ): Int =
        requireNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO contador_diario (barraca_id, dia, tipo, ultimo) VALUES (?, ?, ?, 1)
                ON CONFLICT (barraca_id, dia, tipo) DO UPDATE SET ultimo = contador_diario.ultimo + 1
                RETURNING ultimo
                """.trimIndent(),
                Int::class.java,
                barracaId,
                dia,
                tipo.name,
            ),
        )
}
