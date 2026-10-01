package br.com.mesamar.cardapio

import br.com.mesamar.common.Money
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "categoria")
class Categoria(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "barraca_id", nullable = false)
    val barracaId: UUID,
    @Column(nullable = false, length = 40)
    var nome: String,
    @Column(nullable = false)
    var ordem: Int,
    @Column(name = "criada_em", nullable = false)
    val criadaEm: Instant,
)

@Entity
@Table(name = "produto")
class Produto(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "barraca_id", nullable = false)
    val barracaId: UUID,
    @Column(name = "categoria_id", nullable = false)
    var categoriaId: UUID,
    @Column(nullable = false, length = 60)
    var nome: String,
    @Column(length = 140)
    var descricao: String? = null,
    @Column(name = "preco_centavos", nullable = false)
    var precoCentavos: Long,
    @Column(nullable = false)
    var ativo: Boolean = true,
    @Column(nullable = false)
    var esgotado: Boolean = false,
    @Column(nullable = false)
    var ordem: Int,
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
    @Column(name = "atualizado_em", nullable = false)
    var atualizadoEm: Instant,
) {
    val preco: String get() = Money.format(precoCentavos)
}

interface CategoriaRepository : JpaRepository<Categoria, UUID> {
    fun findAllByBarracaIdOrderByOrdem(barracaId: UUID): List<Categoria>

    fun findByIdAndBarracaId(
        id: UUID,
        barracaId: UUID,
    ): Categoria?

    @Query("select count(c) > 0 from Categoria c where c.barracaId = :barracaId and lower(c.nome) = lower(:nome) and c.id <> :exceto")
    fun existsNome(
        barracaId: UUID,
        nome: String,
        exceto: UUID,
    ): Boolean
}

interface ProdutoRepository : JpaRepository<Produto, UUID> {
    fun findAllByBarracaIdOrderByOrdem(barracaId: UUID): List<Produto>

    fun findAllByBarracaIdAndCategoriaIdOrderByOrdem(
        barracaId: UUID,
        categoriaId: UUID,
    ): List<Produto>

    fun findByIdAndBarracaId(
        id: UUID,
        barracaId: UUID,
    ): Produto?

    fun countByBarracaId(barracaId: UUID): Int

    fun countByBarracaIdAndCategoriaId(
        barracaId: UUID,
        categoriaId: UUID,
    ): Int

    fun countByBarracaIdAndAtivoTrue(barracaId: UUID): Int
}
