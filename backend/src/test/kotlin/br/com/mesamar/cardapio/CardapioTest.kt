package br.com.mesamar.cardapio

import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.assertHtmlContains
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.net.URI
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@IntegrationTest
class CardapioTest(
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    private val categorias: CategoriaRepository,
    private val produtos: ProdutoRepository,
) {
    private val painel = PainelTestSupport(port, convites)

    private fun barracaId(email: String): UUID = assertNotNull(usuarios.findByEmailIgnoreCase(email)?.barracaId)

    /**
     * Faz o que o navegador faz depois de um POST: abre a página do redirect (o que consome a
     * mensagem "flash"). Sem isso, a mensagem de um passo de preparação sobraria na sessão
     * e apareceria no lugar da mensagem do passo testado.
     */
    private fun seguir(
        http: HttpTestClient,
        response: HttpResponse<String>,
    ): HttpResponse<String> {
        if (response.statusCode() == 302) {
            http.get(URI.create(response.header("Location")).rawPath)
        }
        return response
    }

    private fun categoria(
        http: HttpTestClient,
        email: String,
        nome: String,
    ): Categoria {
        seguir(http, painel.post(http, "/painel/cardapio", "/painel/cardapio/categorias", mapOf("nome" to nome)))
        return categorias.findAllByBarracaIdOrderByOrdem(barracaId(email)).first { it.nome == nome }
    }

    private fun novoProduto(
        http: HttpTestClient,
        categoria: Categoria,
        nome: String,
        preco: String = "12,00",
        descricao: String = "",
        seguirRedirect: Boolean = true,
    ): HttpResponse<String> {
        val response =
            painel.post(
                http,
                "/painel/cardapio/produtos/novo?categoria=${categoria.id}",
                "/painel/cardapio/produtos",
                mapOf("categoriaId" to categoria.id.toString(), "nome" to nome, "preco" to preco, "descricao" to descricao),
            )
        return if (seguirRedirect) seguir(http, response) else response
    }

    private fun produto(
        email: String,
        nome: String,
    ): Produto = produtos.findAllByBarracaIdOrderByOrdem(barracaId(email)).first { it.nome == nome }

    private fun nomesNaOrdem(
        email: String,
        categoria: Categoria,
    ) = produtos.findAllByBarracaIdAndCategoriaIdOrderByOrdem(barracaId(email), categoria.id).filter { it.ativo }.map { it.nome }

    @Test
    fun `cria categoria e produtos, em reais, e mostra no painel`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        val bebidas = categoria(http, email, "Bebidas")

        val response =
            novoProduto(http, bebidas, "  Água   de coco ", preco = "1.012,5", descricao = "Gelada, no coco", seguirRedirect = false)

        assertEquals(302, response.statusCode(), response.body())
        val agua = produto(email, "Água de coco")
        assertEquals(101250L, agua.precoCentavos)
        assertEquals("Gelada, no coco", agua.descricao)
        assertContains(response.header("Location"), "/painel/cardapio#produto-${agua.id}")
        val pagina = http.get("/painel/cardapio").body()
        assertHtmlContains(pagina, "Água de coco (R$ 1.012,50) entrou no cardápio.", anchor = "<main")
        assertHtmlContains(pagina, "1 item no cardápio", anchor = "<main")
        assertHtmlContains(http.get("/painel").body(), "1 item para o cliente pedir", anchor = "<main")
    }

    @Test
    fun `salvar e cadastrar outro volta para o formulario na mesma categoria`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        categoria(http, email, "Bebidas")
        val porcoes = categoria(http, email, "Porções")

        val response =
            painel.post(
                http,
                "/painel/cardapio/produtos/novo?categoria=${porcoes.id}",
                "/painel/cardapio/produtos",
                mapOf("categoriaId" to porcoes.id.toString(), "nome" to "Batata frita", "preco" to "35", "outro" to "1"),
            )

        assertContains(response.header("Location"), "/painel/cardapio/produtos/novo?categoria=${porcoes.id}")
        val form = http.get("/painel/cardapio/produtos/novo?categoria=${porcoes.id}").body()
        val opcao = assertNotNull(Regex("<option[^>]*${porcoes.id}[^>]*>").find(form), "opção da categoria").value
        assertContains(opcao, "selected")
    }

    @Test
    fun `validacoes do produto e da categoria`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        val bebidas = categoria(http, email, "Bebidas")

        val invalido = novoProduto(http, bebidas, "", preco = "doze")
        assertEquals(422, invalido.statusCode())
        assertHtmlContains(invalido.body(), "Qual é o nome do produto?")
        assertHtmlContains(invalido.body(), "Use só números, como 12 ou 12,50.")

        assertHtmlContains(novoProduto(http, bebidas, "Zero", preco = "0").body(), "Use só números")
        assertHtmlContains(novoProduto(http, bebidas, "Caro", preco = "10.000,01").body(), "no máximo R$ 10.000,00")
        assertHtmlContains(novoProduto(http, bebidas, "Sem preço", preco = "").body(), "Informe o preço.")

        painel.post(http, "/painel/cardapio", "/painel/cardapio/categorias", mapOf("nome" to "BEBIDAS"))
        assertHtmlContains(http.get("/painel/cardapio").body(), "Já existe a categoria “BEBIDAS”.", anchor = "<main")
        assertEquals(1, categorias.findAllByBarracaIdOrderByOrdem(barracaId(email)).size)
    }

    @Test
    fun `esgotado com um toque, e volta`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        novoProduto(http, categoria(http, email, "Bebidas"), "Coco")
        val coco = produto(email, "Coco")

        val esgotou = painel.post(http, "/painel/cardapio", "/painel/cardapio/produtos/${coco.id}/esgotado", emptyMap())

        assertEquals(302, esgotou.statusCode())
        assertContains(esgotou.header("Location"), "#produto-${coco.id}")
        assertTrue(produtos.findById(coco.id).orElseThrow().esgotado)
        val pagina = http.get("/painel/cardapio").body()
        assertHtmlContains(pagina, "Coco: esgotado.", anchor = "<main")
        assertHtmlContains(pagina, "aria-pressed=\"true\"", anchor = "menu-items")

        painel.post(http, "/painel/cardapio", "/painel/cardapio/produtos/${coco.id}/esgotado", emptyMap())
        assertFalse(produtos.findById(coco.id).orElseThrow().esgotado)
    }

    @Test
    fun `ordem dos produtos e das categorias`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        val bebidas = categoria(http, email, "Bebidas")
        val porcoes = categoria(http, email, "Porções")
        listOf("A", "B", "C").forEach { novoProduto(http, bebidas, it) }
        assertEquals(listOf("A", "B", "C"), nomesNaOrdem(email, bebidas))

        painel.post(http, "/painel/cardapio", "/painel/cardapio/produtos/${produto(email, "C").id}/mover", mapOf("direcao" to "CIMA"))
        assertEquals(listOf("A", "C", "B"), nomesNaOrdem(email, bebidas))
        painel.post(http, "/painel/cardapio", "/painel/cardapio/produtos/${produto(email, "A").id}/mover", mapOf("direcao" to "CIMA"))
        assertEquals(listOf("A", "C", "B"), nomesNaOrdem(email, bebidas), "o primeiro não sobe mais")

        painel.post(http, "/painel/cardapio", "/painel/cardapio/categorias/${porcoes.id}/mover", mapOf("direcao" to "CIMA"))
        assertEquals(listOf("Porções", "Bebidas"), categorias.findAllByBarracaIdOrderByOrdem(barracaId(email)).map { it.nome })
        val pagina = http.get("/painel/cardapio").body()
        assertTrue(pagina.indexOf("Porções</h2>") < pagina.indexOf("Bebidas</h2>"), "a página segue a ordem nova")
    }

    @Test
    fun `tirar do cardapio esconde, voltar devolve, e editar muda preco e categoria`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        val bebidas = categoria(http, email, "Bebidas")
        val drinks = categoria(http, email, "Drinks")
        novoProduto(http, bebidas, "Caipirinha", preco = "20")
        val caipi = produto(email, "Caipirinha")

        painel.post(http, "/painel/cardapio", "/painel/cardapio/produtos/${caipi.id}/no-cardapio", mapOf("ativo" to "false"))
        val fora = http.get("/painel/cardapio").body()
        assertHtmlContains(fora, "Fora do cardápio (1)", anchor = "<main")
        assertHtmlContains(fora, "0 itens no cardápio", anchor = "<main")

        painel.post(http, "/painel/cardapio", "/painel/cardapio/produtos/${caipi.id}/no-cardapio", mapOf("ativo" to "true"))
        assertTrue(produtos.findById(caipi.id).orElseThrow().ativo)

        val editar = "/painel/cardapio/produtos/${caipi.id}"
        assertHtmlContains(http.get(editar).body(), "value=\"20,00\"")
        painel.post(
            http,
            editar,
            editar,
            mapOf(
                "categoriaId" to drinks.id.toString(),
                "nome" to "Caipirinha de limão",
                "preco" to "25,50",
                "descricao" to "",
            ),
        )
        val depois = produtos.findById(caipi.id).orElseThrow()
        assertEquals("Caipirinha de limão", depois.nome)
        assertEquals(2550L, depois.precoCentavos)
        assertEquals(drinks.id, depois.categoriaId)
    }

    @Test
    fun `so exclui categoria vazia`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        val bebidas = categoria(http, email, "Bebidas")
        val vazia = categoria(http, email, "Sobremesas")
        novoProduto(http, bebidas, "Coco")

        painel.post(http, "/painel/cardapio", "/painel/cardapio/categorias/${bebidas.id}/excluir", emptyMap())
        assertHtmlContains(http.get("/painel/cardapio").body(), "ainda tem 1 produto", anchor = "<main")
        assertTrue(categorias.existsById(bebidas.id))

        painel.post(http, "/painel/cardapio", "/painel/cardapio/categorias/${vazia.id}/excluir", emptyMap())
        assertFalse(categorias.existsById(vazia.id))
    }

    @Test
    fun `dono de outra barraca nao le nem altera o cardapio alheio`() {
        val a = painel.novoEmail()
        val httpA = painel.donoComBarraca(email = a)
        val bebidasA = categoria(httpA, a, "Bebidas A")
        novoProduto(httpA, bebidasA, "Coco do A", preco = "12")
        val cocoA = produto(a, "Coco do A")

        val b = painel.novoEmail()
        val intruso = painel.donoComBarraca(email = b)
        val categoriaB = categoria(intruso, b, "Bebidas B")

        // Leitura: nem a lista nem a edição mostram nada de A.
        assertTrue(!intruso.get("/painel/cardapio").body().contains("Coco do A"))
        val edicao = intruso.get("/painel/cardapio/produtos/${cocoA.id}")
        assertEquals(404, edicao.statusCode())
        assertTrue(!edicao.body().contains("Coco do A"))
        assertTrue(!intruso.get("/painel/cardapio/produtos/novo?categoria=${bebidasA.id}").body().contains("Bebidas A"))

        // Escrita: cada ação com ids de A.
        val acoes =
            listOf(
                "/painel/cardapio/produtos/${cocoA.id}" to
                    mapOf("categoriaId" to categoriaB.id.toString(), "nome" to "Roubado", "preco" to "1", "descricao" to ""),
                "/painel/cardapio/produtos/${cocoA.id}/esgotado" to emptyMap(),
                "/painel/cardapio/produtos/${cocoA.id}/no-cardapio" to mapOf("ativo" to "false"),
                "/painel/cardapio/produtos/${cocoA.id}/mover" to mapOf("direcao" to "BAIXO"),
                "/painel/cardapio/categorias/${bebidasA.id}/nome" to mapOf("nome" to "Hackeada"),
                "/painel/cardapio/categorias/${bebidasA.id}/mover" to mapOf("direcao" to "BAIXO"),
                "/painel/cardapio/categorias/${bebidasA.id}/excluir" to emptyMap(),
            )
        acoes.forEach { (acao, campos) -> painel.post(intruso, "/painel/cardapio", acao, campos) }

        // Criar produto de B apontando para a categoria de A é recusado como "categoria inexistente".
        val cruzado =
            painel.post(
                intruso,
                "/painel/cardapio/produtos/novo",
                "/painel/cardapio/produtos",
                mapOf("categoriaId" to bebidasA.id.toString(), "nome" to "Infiltrado", "preco" to "5"),
            )
        assertEquals(422, cruzado.statusCode())
        assertHtmlContains(cruzado.body(), "Escolha a categoria.")

        val cocoDepois = produtos.findById(cocoA.id).orElseThrow()
        assertEquals("Coco do A", cocoDepois.nome)
        assertEquals(1200L, cocoDepois.precoCentavos)
        assertFalse(cocoDepois.esgotado)
        assertTrue(cocoDepois.ativo)
        assertEquals(bebidasA.id, cocoDepois.categoriaId)
        assertEquals("Bebidas A", categorias.findById(bebidasA.id).orElseThrow().nome)
        assertTrue(produtos.findAllByBarracaIdOrderByOrdem(barracaId(a)).none { it.nome == "Infiltrado" })
        assertTrue(produtos.findAllByBarracaIdOrderByOrdem(barracaId(b)).none { it.nome == "Infiltrado" })
    }
}
