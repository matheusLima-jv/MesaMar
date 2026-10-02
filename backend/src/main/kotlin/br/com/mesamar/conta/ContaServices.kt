package br.com.mesamar.conta

import br.com.mesamar.common.EmailGateway
import br.com.mesamar.common.SecureTokens
import br.com.mesamar.config.SiteProperties
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Regras de senha: comprimento é o que mais protege; sem exigências que ninguém lembra. */
object SenhaRules {
    const val MIN = 10
    const val MAX = 72 // limite do BCrypt

    fun problema(
        senha: String,
        email: String,
    ): String? =
        when {
            senha.length < MIN -> "A senha precisa ter pelo menos $MIN caracteres."
            senha.length > MAX -> "A senha pode ter no máximo $MAX caracteres."
            senha.equals(email, ignoreCase = true) -> "A senha não pode ser igual ao e-mail."
            senha.isBlank() -> "A senha não pode ser só espaços."
            else -> null
        }
}

data class NovoConvite(
    val convite: Convite,
    val token: String,
)

@Service
class ConviteService(
    private val repository: ConviteRepository,
    private val clock: Clock,
) {
    @Transactional
    fun criar(
        leadId: UUID?,
        observacao: String?,
    ): NovoConvite {
        val token = SecureTokens.generate()
        val now = Instant.now(clock)
        val convite =
            repository.save(
                Convite(
                    tokenHash = SecureTokens.hash(token),
                    leadId = leadId,
                    observacao = observacao?.trim()?.take(80)?.ifBlank { null },
                    criadoEm = now,
                    expiraEm = now.plus(VALIDADE),
                ),
            )
        return NovoConvite(convite, token)
    }

    fun buscarValido(token: String?): Convite? {
        if (token.isNullOrBlank()) return null
        return repository.findByTokenHash(SecureTokens.hash(token))?.takeIf { it.isValid(Instant.now(clock)) }
    }

    companion object {
        val VALIDADE: Duration = Duration.ofDays(14)
    }
}

sealed interface CadastroResultado {
    data class Ok(
        val usuario: Usuario,
    ) : CadastroResultado

    data object ConviteInvalido : CadastroResultado

    data object EmailEmUso : CadastroResultado
}

@Service
class ContaService(
    private val usuarios: UsuarioRepository,
    private val convites: ConviteRepository,
    private val conviteService: ConviteService,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun cadastrar(
        token: String?,
        nome: String,
        email: String,
        senha: String,
    ): CadastroResultado {
        val convite = conviteService.buscarValido(token) ?: return CadastroResultado.ConviteInvalido
        val emailNormalizado = email.trim().lowercase()
        if (usuarios.findByEmailIgnoreCase(emailNormalizado) != null) return CadastroResultado.EmailEmUso

        val now = Instant.now(clock)
        val usuario =
            usuarios.save(
                Usuario(
                    nome = nome.trim(),
                    email = emailNormalizado,
                    senhaHash = requireNotNull(passwordEncoder.encode(senha)),
                    criadoEm = now,
                ),
            )
        convite.usadoEm = now
        convite.usadoPor = usuario.id
        convites.save(convite)
        log.info("Conta criada: usuario={} convite={}", usuario.id, convite.id)
        return CadastroResultado.Ok(usuario)
    }
}

@Service
class RecuperacaoSenhaService(
    private val usuarios: UsuarioRepository,
    private val resets: ResetSenhaRepository,
    private val passwordEncoder: PasswordEncoder,
    private val email: EmailGateway,
    private val site: SiteProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Sempre "funciona" do ponto de vista de quem pede: a resposta na tela é a mesma
     * exista ou não a conta, para ninguém descobrir quais e-mails estão cadastrados.
     */
    @Transactional
    fun solicitar(emailInformado: String) {
        val usuario = usuarios.findByEmailIgnoreCase(emailInformado.trim()) ?: return
        val now = Instant.now(clock)
        resets.findAllByUsuarioIdAndUsadoEmIsNull(usuario.id).forEach { it.usadoEm = now } // invalida links antigos

        val token = SecureTokens.generate()
        resets.save(
            ResetSenha(
                usuarioId = usuario.id,
                tokenHash = SecureTokens.hash(token),
                criadoEm = now,
                expiraEm = now.plus(VALIDADE),
            ),
        )
        val link = site.absoluteUrl("/painel/nova-senha?token=$token")
        email.send(
            to = usuario.email,
            subject = "MesaMar: troca de senha",
            body =
                """
                Olá, ${usuario.nome}!

                Para criar uma senha nova no painel do MesaMar, abra o link abaixo (vale por 1 hora):

                $link

                Se não foi você que pediu, pode ignorar este e-mail: a sua senha continua a mesma.
                """.trimIndent(),
        )
        log.info("Troca de senha solicitada para usuario={}", usuario.id)
    }

    fun tokenValido(token: String?): Boolean = buscar(token) != null

    @Transactional
    fun redefinir(
        token: String?,
        novaSenha: String,
    ): Boolean {
        val reset = buscar(token) ?: return false
        val usuario = usuarios.findById(reset.usuarioId).orElse(null) ?: return false
        usuario.senhaHash = requireNotNull(passwordEncoder.encode(novaSenha))
        // Celular perdido ou senha vazada: quem estava logado com a senha antiga cai na próxima ação
        usuario.credenciaisEm = Instant.now(clock)
        reset.usadoEm = Instant.now(clock)
        log.info("Senha redefinida para usuario={}", usuario.id)
        return true
    }

    fun emailDoToken(token: String?): String? = buscar(token)?.let { usuarios.findById(it.usuarioId).orElse(null)?.email }

    private fun buscar(token: String?): ResetSenha? {
        if (token.isNullOrBlank()) return null
        return resets.findByTokenHash(SecureTokens.hash(token))?.takeIf { it.isValid(Instant.now(clock)) }
    }

    companion object {
        val VALIDADE: Duration = Duration.ofHours(1)
    }
}
