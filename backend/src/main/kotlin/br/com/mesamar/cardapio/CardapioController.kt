package br.com.mesamar.cardapio

import br.com.mesamar.barraca.PainelPage
import br.com.mesamar.barraca.barraca
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.util.UUID

/**
 * Cardápio no /painel. Ações de um toque (esgotado, ordem, tirar do cardápio) voltam para
 * a mesma posição da página (âncora do produto), para o dono não perder o lugar na lista.
 */
@Controller
@PainelPage
class CardapioController(
    private val service: CardapioService,
) {
    @GetMapping("/painel/cardapio")
    fun cardapio(model: Model): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val secoes = service.secoes(barraca)
        model.addAttribute("secoes", secoes)
        model.addAttribute("totalNoCardapio", secoes.sumOf { it.noCardapio.size })
        model.addAttribute("foraDoCardapio", secoes.flatMap { s -> s.produtos.filter { !it.ativo }.map { s.categoria.nome to it } })
        return "painel/cardapio"
    }

    // ---------- Categorias ----------

    @PostMapping("/painel/cardapio/categorias")
    fun criarCategoria(
        @RequestParam(defaultValue = "") nome: String,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val erro = service.criarCategoria(barraca, nome)
        if (erro != null) {
            redirect.addFlashAttribute("erro", erro)
            redirect.addFlashAttribute("nomeCategoria", nome.take(40))
        } else {
            redirect.addFlashAttribute("ok", "Categoria “${nome.trim()}” criada. Agora adicione os produtos dela.")
        }
        return "redirect:/painel/cardapio"
    }

    @PostMapping("/painel/cardapio/categorias/{id}/nome")
    fun renomear(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "") nome: String,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        resultado(redirect, service.renomearCategoria(barraca, id, nome), "Categoria renomeada.")
        return "redirect:/painel/cardapio#categoria-$id"
    }

    @PostMapping("/painel/cardapio/categorias/{id}/mover")
    fun moverCategoria(
        @PathVariable id: UUID,
        @RequestParam direcao: Direcao,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        service.moverCategoria(barraca, id, direcao)?.let { redirect.addFlashAttribute("erro", it) }
        return "redirect:/painel/cardapio#categoria-$id"
    }

    @PostMapping("/painel/cardapio/categorias/{id}/excluir")
    fun excluirCategoria(
        @PathVariable id: UUID,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        resultado(redirect, service.excluirCategoria(barraca, id), "Categoria excluída.")
        return "redirect:/painel/cardapio"
    }

    // ---------- Produtos ----------

    @GetMapping("/painel/cardapio/produtos/novo")
    fun novoProduto(
        @RequestParam(required = false) categoria: UUID?,
        model: Model,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val categorias = service.categorias(barraca)
        if (categorias.isEmpty()) return "redirect:/painel/cardapio"
        val escolhida = categorias.firstOrNull { it.id == categoria } ?: categorias.first()
        model.addAttribute("produtoForm", ProdutoForm(categoriaId = escolhida.id.toString()))
        return renderForm(model, produtoId = null)
    }

    @PostMapping("/painel/cardapio/produtos")
    fun criarProduto(
        @ModelAttribute("produtoForm") form: ProdutoForm,
        bindingResult: BindingResult,
        @RequestParam(required = false) outro: String?,
        model: Model,
        response: HttpServletResponse,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val produto = service.criarProduto(barraca, form, bindingResult)
        if (produto == null) {
            response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            return renderForm(model, produtoId = null)
        }
        redirect.addFlashAttribute("ok", "${produto.nome} (${produto.preco}) entrou no cardápio.")
        // "Salvar e adicionar outro": volta para o formulário já na mesma categoria.
        return if (outro != null) {
            "redirect:/painel/cardapio/produtos/novo?categoria=${produto.categoriaId}"
        } else {
            "redirect:/painel/cardapio#produto-${produto.id}"
        }
    }

    @GetMapping("/painel/cardapio/produtos/{id}")
    fun editar(
        @PathVariable id: UUID,
        model: Model,
        response: HttpServletResponse,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val produto = service.produto(barraca, id) ?: return naoEncontrado(response)
        model.addAttribute("produtoForm", ProdutoForm.of(produto))
        return renderForm(model, produtoId = id)
    }

    @PostMapping("/painel/cardapio/produtos/{id}")
    fun salvar(
        @PathVariable id: UUID,
        @ModelAttribute("produtoForm") form: ProdutoForm,
        bindingResult: BindingResult,
        model: Model,
        response: HttpServletResponse,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        service.produto(barraca, id) ?: return naoEncontrado(response)
        val produto = service.editarProduto(barraca, id, form, bindingResult)
        if (produto == null) {
            response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            return renderForm(model, produtoId = id)
        }
        redirect.addFlashAttribute("ok", "${produto.nome} salvo.")
        return "redirect:/painel/cardapio#produto-${produto.id}"
    }

    @PostMapping("/painel/cardapio/produtos/{id}/esgotado")
    fun esgotado(
        @PathVariable id: UUID,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val produto = service.alternarEsgotado(barraca, id)
        if (produto == null) {
            redirect.addFlashAttribute("erro", CardapioService.NAO_ENCONTRADO)
            return "redirect:/painel/cardapio"
        }
        redirect.addFlashAttribute("ok", if (produto.esgotado) "${produto.nome}: esgotado." else "${produto.nome} voltou a ser vendido.")
        return "redirect:/painel/cardapio#produto-${produto.id}"
    }

    @PostMapping("/painel/cardapio/produtos/{id}/no-cardapio")
    fun noCardapio(
        @PathVariable id: UUID,
        @RequestParam ativo: Boolean,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val produto = service.definirNoCardapio(barraca, id, ativo)
        if (produto == null) {
            redirect.addFlashAttribute("erro", CardapioService.NAO_ENCONTRADO)
            return "redirect:/painel/cardapio"
        }
        redirect.addFlashAttribute("ok", if (ativo) "${produto.nome} voltou para o cardápio." else "${produto.nome} saiu do cardápio.")
        return "redirect:/painel/cardapio" + if (ativo) "#produto-${produto.id}" else "#fora"
    }

    @PostMapping("/painel/cardapio/produtos/{id}/mover")
    fun moverProduto(
        @PathVariable id: UUID,
        @RequestParam direcao: Direcao,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        service.moverProduto(barraca, id, direcao)?.let { redirect.addFlashAttribute("erro", it) }
        return "redirect:/painel/cardapio#produto-$id"
    }

    private fun renderForm(
        model: Model,
        produtoId: UUID?,
    ): String {
        model.addAttribute("categorias", service.categorias(model.barraca()!!))
        model.addAttribute("produtoId", produtoId)
        return "painel/produto"
    }

    private fun naoEncontrado(response: HttpServletResponse): String {
        response.status = HttpStatus.NOT_FOUND.value()
        return "painel/nao-encontrado"
    }

    private fun resultado(
        redirect: RedirectAttributes,
        erro: String?,
        ok: String,
    ) {
        if (erro != null) redirect.addFlashAttribute("erro", erro) else redirect.addFlashAttribute("ok", ok)
    }
}
