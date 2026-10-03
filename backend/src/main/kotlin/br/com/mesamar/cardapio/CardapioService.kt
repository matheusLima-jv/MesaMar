package br.com.mesamar.cardapio

import br.com.mesamar.barraca.Barraca
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.common.Money
import br.com.mesamar.eventos.PublicadorEventos
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.Errors
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ProdutoForm(
    var categoriaId: String = "",
    var nome: String = "",
    var descricao: String = "",
    /** Em reais: "12", "12,50", "1.200,00". */
    var preco: String = "",
    var versao: Long? = null,
) {
    companion object {
        fun of(p: Produto) = ProdutoForm(p.categoriaId.toString(), p.nome, p.descricao.orEmpty(), Money.toInput(p.precoCentavos), p.versao)
    }
}

/** Uma categoria com os seus produtos, na ordem em que o cliente vai ver. */
data class SecaoCardapio(
    val categoria: Categoria,
    val produtos: List<Produto>,
) {
    val noCardapio: List<Produto> get() = produtos.filter { it.ativo }
}

enum class Direcao { CIMA, BAIXO }

/**
 * Cardápio da barraca. Produto nunca é apagado de verdade: "tirar do cardápio" só esconde,
 * porque os pedidos antigos continuam apontando para ele (e o relatório também).
 */
@Service
class CardapioService(
    private val autorHistorico: br.com.mesamar.common.AutorHistorico,
    private val categorias: CategoriaRepository,
    private val produtos: ProdutoRepository,
    private val barracas: BarracaRepository,
    private val tempoReal: PublicadorEventos,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    fun secoes(barraca: Barraca): List<SecaoCardapio> {
        val porCategoria = produtos.findAllByBarracaIdOrderByOrdem(barraca.id).groupBy { it.categoriaId }
        return categorias.findAllByBarracaIdOrderByOrdem(barraca.id).map { SecaoCardapio(it, porCategoria[it.id].orEmpty()) }
    }

    fun categorias(barraca: Barraca): List<Categoria> = categorias.findAllByBarracaIdOrderByOrdem(barraca.id)

    fun produto(
        barraca: Barraca,
        id: UUID,
    ): Produto? = produtos.findByIdAndBarracaId(id, barraca.id)

    fun produtosNoCardapio(barraca: Barraca): Int = produtos.countByBarracaIdAndAtivoTrue(barraca.id)

    // ---------- Categorias ----------

    @Transactional
    fun criarCategoria(
        barraca: Barraca,
        nomeBruto: String,
    ): String? {
        autorHistorico.marcar()
        // Avisa os aparelhos abertos (só sai no commit; se nada mudou, eles só buscam o mesmo cardápio)
        barracas.travar(barraca.id)
        tempoReal.cardapioMudou(barraca.id)
        val nome = limpar(nomeBruto)
        // Uma criação por vez na barraca: o limite e o nome repetido são conferidos sem corrida
        problemaNomeCategoria(barraca, nome, exceto = null)?.let { return it }
        val existentes = categorias.findAllByBarracaIdOrderByOrdem(barraca.id)
        if (existentes.size >= MAX_CATEGORIAS) return "Limite de $MAX_CATEGORIAS categorias."
        categorias.save(
            Categoria(barracaId = barraca.id, nome = nome, ordem = (existentes.maxOfOrNull { it.ordem } ?: 0) + 1, criadaEm = agora()),
        )
        return null
    }

    @Transactional
    fun renomearCategoria(
        barraca: Barraca,
        id: UUID,
        nomeBruto: String,
        versao: Long? = null,
    ): String? {
        autorHistorico.marcar()
        // Avisa os aparelhos abertos (só sai no commit; se nada mudou, eles só buscam o mesmo cardápio)
        barracas.travar(barraca.id)
        tempoReal.cardapioMudou(barraca.id)
        val categoria = categorias.findByIdAndBarracaId(id, barraca.id) ?: return NAO_ENCONTRADA
        if (versao != null && versao != categoria.versao) {
            return "Esta categoria mudou em outro aparelho. Atualize a tela e confira o nome."
        }
        val nome = limpar(nomeBruto)
        problemaNomeCategoria(barraca, nome, exceto = id)?.let { return it }
        categoria.nome = nome
        categorias.save(categoria)
        return null
    }

    /** Só apaga categoria vazia: um produto nunca fica sem categoria. */
    @Transactional
    fun excluirCategoria(
        barraca: Barraca,
        id: UUID,
    ): String? {
        autorHistorico.marcar()
        // Avisa os aparelhos abertos (só sai no commit; se nada mudou, eles só buscam o mesmo cardápio)
        barracas.travar(barraca.id)
        tempoReal.cardapioMudou(barraca.id)
        val categoria = categorias.findByIdAndBarracaId(id, barraca.id) ?: return NAO_ENCONTRADA
        val qtd = produtos.countByBarracaIdAndCategoriaId(barraca.id, id)
        if (qtd > 0) {
            val itens = if (qtd == 1) "produto" else "produtos"
            return "“${categoria.nome}” ainda tem $qtd $itens. Mova-os para outra categoria antes de excluir."
        }
        categorias.delete(categoria)
        return null
    }

    @Transactional
    fun moverCategoria(
        barraca: Barraca,
        id: UUID,
        direcao: Direcao,
    ): String? {
        autorHistorico.marcar()
        // Avisa os aparelhos abertos (só sai no commit; se nada mudou, eles só buscam o mesmo cardápio)
        barracas.travar(barraca.id)
        tempoReal.cardapioMudou(barraca.id)
        val lista = categorias.findAllByBarracaIdOrderByOrdem(barraca.id)
        if (lista.none { it.id == id }) return NAO_ENCONTRADA
        trocar(lista, id, direcao, { it.id }, { it.ordem }, { c, o -> c.ordem = o })
        categorias.saveAll(lista)
        return null
    }

    // ---------- Produtos ----------

    @Transactional
    fun criarProduto(
        barraca: Barraca,
        form: ProdutoForm,
        errors: Errors,
    ): Produto? {
        autorHistorico.marcar()
        // Avisa os aparelhos abertos (só sai no commit; se nada mudou, eles só buscam o mesmo cardápio)
        barracas.travar(barraca.id)
        tempoReal.cardapioMudou(barraca.id)
        val dados = validar(barraca, form, errors) ?: return null
        if (produtos.countByBarracaId(barraca.id) >= MAX_PRODUTOS) {
            errors.reject("limite", "Limite de $MAX_PRODUTOS produtos por barraca.")
            return null
        }
        val ultimo = produtos.findAllByBarracaIdAndCategoriaIdOrderByOrdem(barraca.id, dados.categoria.id).maxOfOrNull { it.ordem } ?: 0
        val now = agora()
        val produto =
            produtos.save(
                Produto(
                    barracaId = barraca.id,
                    categoriaId = dados.categoria.id,
                    nome = dados.nome,
                    descricao = dados.descricao,
                    precoCentavos = dados.preco,
                    ordem = ultimo + 1,
                    criadoEm = now,
                    atualizadoEm = now,
                ),
            )
        log.info("Produto criado: barraca={} produto={}", barraca.id, produto.id)
        return produto
    }

    /** O preço novo vale para os próximos pedidos; os já feitos guardam o preço da hora (Fase 3). */
    @Transactional
    fun editarProduto(
        barraca: Barraca,
        id: UUID,
        form: ProdutoForm,
        errors: Errors,
    ): Produto? {
        autorHistorico.marcar()
        // Avisa os aparelhos abertos (só sai no commit; se nada mudou, eles só buscam o mesmo cardápio)
        barracas.travar(barraca.id)
        tempoReal.cardapioMudou(barraca.id)
        val produto = produtos.findByIdAndBarracaId(id, barraca.id) ?: return null
        if (form.versao != null && form.versao != produto.versao) {
            errors.reject("conflito", "Este produto mudou em outro aparelho. Reabra a edição e confira os dados.")
            return null
        }
        val dados = validar(barraca, form, errors) ?: return null
        if (produto.categoriaId != dados.categoria.id) {
            produto.categoriaId = dados.categoria.id
            produto.ordem =
                (produtos.findAllByBarracaIdAndCategoriaIdOrderByOrdem(barraca.id, dados.categoria.id).maxOfOrNull { it.ordem } ?: 0) + 1
        }
        produto.nome = dados.nome
        produto.descricao = dados.descricao
        produto.precoCentavos = dados.preco
        return salvar(produto)
    }

    /** O "1 toque" do dia a dia: acabou o coco, marca esgotado; chegou, desmarca. */
    @Transactional
    fun alternarEsgotado(
        barraca: Barraca,
        id: UUID,
    ): Produto? =
        produtoTravado(barraca, id)
            ?.also { it.esgotado = !it.esgotado }
            ?.let(::salvar)
            ?.also { tempoReal.cardapioMudou(barraca.id) }

    /** Versão "definir" (não alternar) para o app: repetir a mesma chamada não desfaz a ação. */
    @Transactional
    fun definirEsgotado(
        barraca: Barraca,
        id: UUID,
        esgotado: Boolean,
    ): Produto? =
        produtoTravado(barraca, id)
            ?.also { it.esgotado = esgotado }
            ?.let(::salvar)
            ?.also { tempoReal.cardapioMudou(barraca.id) }

    @Transactional
    fun definirNoCardapio(
        barraca: Barraca,
        id: UUID,
        ativo: Boolean,
    ): Produto? =
        produtoTravado(barraca, id)
            ?.also { it.ativo = ativo }
            ?.let(::salvar)
            ?.also { tempoReal.cardapioMudou(barraca.id) }

    @Transactional
    fun moverProduto(
        barraca: Barraca,
        id: UUID,
        direcao: Direcao,
    ): String? {
        autorHistorico.marcar()
        // Avisa os aparelhos abertos (só sai no commit; se nada mudou, eles só buscam o mesmo cardápio)
        barracas.travar(barraca.id)
        tempoReal.cardapioMudou(barraca.id)
        val produto = produtos.findByIdAndBarracaId(id, barraca.id) ?: return NAO_ENCONTRADO
        val lista = produtos.findAllByBarracaIdAndCategoriaIdOrderByOrdem(barraca.id, produto.categoriaId)
        trocar(lista, id, direcao, { it.id }, { it.ordem }, { p, o -> p.ordem = o })
        produtos.saveAll(lista)
        return null
    }

    // ---------- Apoio ----------

    private data class DadosProduto(
        val categoria: Categoria,
        val nome: String,
        val descricao: String?,
        val preco: Long,
    )

    private fun validar(
        barraca: Barraca,
        form: ProdutoForm,
        errors: Errors,
    ): DadosProduto? {
        // A categoria precisa ser DESTA barraca: um id de outra barraca é tratado como inexistente.
        val categoria =
            form.categoriaId
                .let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }?.let { categorias.findByIdAndBarracaId(it, barraca.id) }
        if (categoria == null) errors.rejectValue("categoriaId", "obrigatorio", "Escolha a categoria.")

        val nome = limpar(form.nome)
        when {
            nome.isEmpty() -> errors.rejectValue("nome", "obrigatorio", "Qual é o nome do produto?")
            nome.length > NOME_MAX -> errors.rejectValue("nome", "tamanho", "Use no máximo $NOME_MAX letras.")
        }
        val descricao = limpar(form.descricao).ifEmpty { null }
        if ((descricao?.length ?: 0) > DESCRICAO_MAX) errors.rejectValue("descricao", "tamanho", "Use no máximo $DESCRICAO_MAX letras.")

        val preco = Money.parseReais(form.preco)
        when {
            form.preco.isBlank() -> errors.rejectValue("preco", "obrigatorio", "Informe o preço.")
            preco == null || preco <= 0 -> errors.rejectValue("preco", "formato", "Use só números, como 12 ou 12,50.")
            preco > PRECO_MAX -> errors.rejectValue("preco", "faixa", "O preço pode ser de no máximo ${Money.format(PRECO_MAX)}.")
        }
        if (errors.hasErrors()) return null
        return DadosProduto(categoria!!, nome, descricao, preco!!)
    }

    private fun problemaNomeCategoria(
        barraca: Barraca,
        nome: String,
        exceto: UUID?,
    ): String? =
        when {
            nome.isEmpty() -> "Dê um nome para a categoria (ex.: Bebidas, Porções)."
            nome.length > CATEGORIA_MAX -> "Use no máximo $CATEGORIA_MAX letras."
            categorias.existsNome(barraca.id, nome, exceto ?: UUID(0, 0)) -> "Já existe a categoria “$nome”."
            else -> null
        }

    /** Troca a posição do item com o vizinho e renumera 1..n (corrige buracos na sequência). */
    private fun <T> trocar(
        lista: List<T>,
        id: UUID,
        direcao: Direcao,
        idDe: (T) -> UUID,
        ordemDe: (T) -> Int,
        setOrdem: (T, Int) -> Unit,
    ) {
        val ordenada = lista.sortedBy(ordemDe).toMutableList()
        val i = ordenada.indexOfFirst { idDe(it) == id }
        val j = if (direcao == Direcao.CIMA) i - 1 else i + 1
        if (i >= 0 && j in ordenada.indices) {
            val tmp = ordenada[i]
            ordenada[i] = ordenada[j]
            ordenada[j] = tmp
        }
        ordenada.forEachIndexed { idx, item -> setOrdem(item, idx + 1) }
    }

    private fun produtoTravado(
        barraca: Barraca,
        id: UUID,
    ): Produto? {
        autorHistorico.marcar()
        barracas.travar(barraca.id)
        return produtos.findByIdAndBarracaId(id, barraca.id)
    }

    private fun salvar(produto: Produto): Produto {
        produto.atualizadoEm = agora()
        return produtos.save(produto)
    }

    private fun limpar(texto: String) = texto.trim().replace(Regex("\\s+"), " ")

    private fun agora() = Instant.now(clock)

    companion object {
        const val NOME_MAX = 60
        const val DESCRICAO_MAX = 140
        const val CATEGORIA_MAX = 40
        const val MAX_CATEGORIAS = 30
        const val MAX_PRODUTOS = 300
        const val PRECO_MAX = 1_000_000L // R$ 10.000,00
        const val NAO_ENCONTRADA = "Categoria não encontrada."
        const val NAO_ENCONTRADO = "Produto não encontrado."
    }
}
