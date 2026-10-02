package br.com.mesamar.barraca

import br.com.mesamar.cardapio.Categoria
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.Produto
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.comanda.CheckinRequest
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ResultadoCheckin
import br.com.mesamar.common.Praia
import br.com.mesamar.common.TipoChavePix
import br.com.mesamar.conta.Usuario
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.Atendente
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.equipe.PinRules
import br.com.mesamar.guardasol.GuardaSol
import br.com.mesamar.guardasol.GuardaSolRepository
import br.com.mesamar.pedido.ItemRequest
import br.com.mesamar.pedido.PedidoRequest
import br.com.mesamar.pedido.PedidoService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** O que o treino precisa para entrar: mostrado uma vez, logo depois de criar ou reiniciar. As senhas não ficam em claro em lugar nenhum. */
data class DemoCredenciais(
    val barraca: String,
    val codigoBarraca: String,
    val emailDono: String,
    val senhaDono: String,
    val atendente: String,
    val pinAtendente: String,
) : Serializable

/**
 * A barraca de demonstração (Fase 7 · checkpoint 7.2): uma barraca fictícia, com cardápio, guarda-sóis, um dono, uma
 * atendente e dois clientes de exemplo, para o dono e a equipe treinarem **antes** de operar de verdade. É marcada como
 * `demo` no banco, e o painel, o app e a página do cliente mostram um aviso de "treino".
 *
 * "Criar ou reiniciar" apaga tudo o que o treino gerou e recria os dados de exemplo, com **senha e PIN novos** (por isso
 * só o projeto, pelo /admin, faz isso). A chave Pix é fictícia: um QR dessa barraca não leva dinheiro a lugar nenhum.
 */
@Service
class DemoService(
    private val barracas: BarracaRepository,
    private val usuarios: UsuarioRepository,
    private val atendentes: AtendenteRepository,
    private val guardaSois: GuardaSolRepository,
    private val categorias: CategoriaRepository,
    private val produtos: ProdutoRepository,
    private val checkin: CheckinService,
    private val pedidos: PedidoService,
    private val encoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val aleatorio = SecureRandom()

    fun existe(): Boolean = barracas.findAll().any { it.demo }

    @Transactional
    fun criarOuReiniciar(): DemoCredenciais {
        val barraca = barracas.findAll().firstOrNull { it.demo } ?: criarBarraca()
        limpar(barraca)
        val agora = Instant.now(clock)

        // Dono e atendente com credenciais novas a cada vez
        val senha = senhaAleatoria()
        val dono =
            usuarios.findByEmailIgnoreCase(EMAIL_DONO)?.also {
                it.senhaHash = requireNotNull(encoder.encode(senha))
                it.credenciaisEm = agora
                usuarios.save(it)
            } ?: usuarios.save(
                Usuario(
                    barracaId = barraca.id,
                    nome = "Dono Demo",
                    email = EMAIL_DONO,
                    senhaHash = requireNotNull(encoder.encode(senha)),
                    criadoEm = agora,
                ),
            )
        check(dono.barracaId == barraca.id) { "o e-mail $EMAIL_DONO já é de outra conta" }
        val pin = pinAleatorio()
        atendentes.save(
            Atendente(
                barracaId = barraca.id,
                nome = NOME_ATENDENTE,
                pinHash = requireNotNull(encoder.encode(pin)),
                criadoEm = agora,
                atualizadoEm = agora,
            ),
        )

        // Guarda-sóis de 1 a 20
        val umbrellas = guardaSois.saveAll((1..20).map { GuardaSol(barracaId = barraca.id, numero = it, criadoEm = agora) })

        // Cardápio
        val bebidas = categorias.save(Categoria(barracaId = barraca.id, nome = "Bebidas", ordem = 1, criadaEm = agora))
        val petiscos = categorias.save(Categoria(barracaId = barraca.id, nome = "Petiscos", ordem = 2, criadaEm = agora))

        fun produto(
            categoria: Categoria,
            nome: String,
            centavos: Long,
            ordem: Int,
        ) = produtos.save(
            Produto(
                barracaId = barraca.id,
                categoriaId = categoria.id,
                nome = nome,
                precoCentavos = centavos,
                ordem = ordem,
                criadoEm = agora,
                atualizadoEm = agora,
            ),
        )
        val coco = produto(bebidas, "Água de coco", 1200, 1)
        produto(bebidas, "Cerveja long neck", 1400, 2)
        produto(bebidas, "Refrigerante lata", 800, 3)
        val pastel = produto(petiscos, "Pastel de camarão", 2500, 1)
        produto(petiscos, "Porção de batata frita", 3800, 2)
        produto(petiscos, "Isca de peixe", 6900, 3)

        // Dois clientes de exemplo, já com pedido, para a fila e a aba Pagamentos não começarem vazias
        val quem = AbertaPor(dono.id, null)
        exemplo(barraca, quem, "Ana (treino)", umbrellas.first { it.numero == 3 }.id, ItemRequest(coco.id, 2), ItemRequest(pastel.id, 1))
        exemplo(barraca, quem, "Beto (treino)", umbrellas.first { it.numero == 7 }.id, ItemRequest(pastel.id, 2))

        log.info("Barraca de demonstração pronta: barraca={}", barraca.id)
        return DemoCredenciais(barraca.nome, barraca.slug, EMAIL_DONO, senha, NOME_ATENDENTE, pin)
    }

    private fun exemplo(
        barraca: Barraca,
        quem: AbertaPor,
        nome: String,
        guardaSolId: UUID,
        vararg itens: ItemRequest,
    ) {
        val resultado = checkin.checkin(barraca.id, quem, CheckinRequest(nome = nome, guardaSolId = guardaSolId, consentimento = true))
        val comanda = (resultado as ResultadoCheckin.Ok).detalhe.comanda
        pedidos.criar(comanda, PedidoRequest(id = UUID.randomUUID(), itens = itens.toList()))
    }

    private fun criarBarraca(): Barraca {
        check(!barracas.existsBySlug(SLUG)) { "Já existe uma barraca com o endereço $SLUG que não é a demonstração." }
        val agora = Instant.now(clock)
        val barraca =
            Barraca(
                nome = NOME_BARRACA,
                slug = SLUG,
                praia = Praia.ENSEADA,
                tipoChavePix = TipoChavePix.ALEATORIA,
                chavePix = CHAVE_PIX_FICTICIA,
                nomeRecebedorPix = "BARRACA DEMO",
                cidadePix = "GUARUJA",
                demo = true,
                criadaEm = agora,
                atualizadaEm = agora,
            )
        return barracas.save(barraca)
    }

    /** Apaga o que o treino gerou (na ordem das chaves estrangeiras) e volta as regras ao padrão. A barraca e o dono ficam. */
    private fun limpar(barraca: Barraca) {
        val id = barraca.id
        listOf(
            "DELETE FROM push_subscription WHERE barraca_id = ?",
            "DELETE FROM pagamento WHERE barraca_id = ?",
            "DELETE FROM item_pedido WHERE barraca_id = ?",
            "DELETE FROM pedido WHERE barraca_id = ?",
            "DELETE FROM comanda WHERE barraca_id = ?",
            "DELETE FROM cliente WHERE barraca_id = ?",
            "DELETE FROM contador_diario WHERE barraca_id = ?",
            "DELETE FROM atendente WHERE barraca_id = ?",
            "DELETE FROM guarda_sol WHERE barraca_id = ?",
            "DELETE FROM produto WHERE barraca_id = ?",
            "DELETE FROM categoria WHERE barraca_id = ?",
        ).forEach { jdbc.update(it, id) }
        jdbc.update("DELETE FROM reset_senha WHERE usuario_id IN (SELECT id FROM usuario WHERE barraca_id = ?)", id)
        // As regras que o treino pode ter mudado voltam ao padrão
        barraca.modoPagamento = ModoPagamento.FINAL
        barraca.limiteSaldoAbertoCentavos = null
        barraca.tempoAlertaPedidoMin = 10
        barraca.limiteGuardaSois = 30
        barraca.pedidosPausados = false
        barraca.mensagemPausa = null
        barraca.ativa = true
        barraca.atualizadaEm = Instant.now(clock)
        barracas.save(barraca)
    }

    /** 12 caracteres sem os que se confundem (0/O, 1/l/I): vai ser lida em voz alta ou digitada no celular. */
    private fun senhaAleatoria(): String = (1..12).map { ALFABETO[aleatorio.nextInt(ALFABETO.length)] }.joinToString("")

    private fun pinAleatorio(): String {
        while (true) {
            val pin = (1..4).map { aleatorio.nextInt(10) }.joinToString("")
            if (PinRules.problema(pin) == null) return pin
        }
    }

    companion object {
        const val NOME_BARRACA = "Barraca Demo (treino)"
        const val SLUG = "barraca-demo-treino"
        const val EMAIL_DONO = "demo@treino.invalid"
        const val NOME_ATENDENTE = "Carla (treino)"

        /** Uma chave aleatória (EVP) que não pertence a ninguém: o QR Pix da demonstração não leva dinheiro a lugar nenhum. */
        const val CHAVE_PIX_FICTICIA = "00000000-0000-4000-8000-000000000000"
        private const val ALFABETO = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }
}
