package br.com.mesamar.config

import br.com.mesamar.common.MailProperties
import br.com.mesamar.push.Base64Url
import br.com.mesamar.push.PushProperties
import br.com.mesamar.push.Vapid
import br.com.mesamar.push.WebPushCripto
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.net.URI

/** Falha antes de aceitar tráfego; a mensagem identifica a variável, nunca seu valor. */
@Component
@Profile("prod")
class ProducaoValidation(
    private val env: Environment,
    private val site: SiteProperties,
    private val mail: MailProperties,
    private val push: PushProperties,
    private val jdbc: JdbcTemplate,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        validar(env, site, mail, push)
        val esquema = jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Int::class.java)
        require(esquema == 19) { "Esta imagem exige esquema V19; implantação/rollback deve validar compatibilidade" }
        val privilegios = jdbc.queryForMap("SELECT rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = current_user")
        require(privilegios.values.none { it == true }) { "DB_USER deve ser uma conta de execução sem poderes administrativos" }
        val dono =
            jdbc.queryForObject(
                "SELECT pg_get_userbyid(datdba) = current_user FROM pg_database WHERE datname = current_database()",
                Boolean::class.java,
            )
        require(dono != true) { "DB_USER não pode ser o proprietário do banco" }
        require(
            jdbc.queryForObject(
                """SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'S')
                    AND pg_has_role(current_user, c.relowner, 'USAGE'))""",
                Boolean::class.java,
            ) != true,
        ) { "DB_USER não pode ser proprietário das tabelas nem herdar a conta de migração" }
        require(jdbc.queryForObject("SELECT has_schema_privilege(current_user, 'public', 'CREATE')", Boolean::class.java) != true) {
            "DB_USER não pode criar objetos no schema public"
        }
    }

    companion object {
        fun placeholder(valor: String): Boolean =
            valor.isBlank() ||
                valor.lowercase() in setOf("mesamar", "admin", "senha", "password", "123456", "...", "xxx", "todo") ||
                Regex(
                    "(?i)(troque|substitua|change[-_]?me|replace[-_]?me|placeholder|gere-com-openssl|seu[_-]|sua[_-]|your[_-]|exemplo|example\\.com|localhost)",
                ).containsMatchIn(valor) ||
                valor.startsWith("<") ||
                valor.contains("\${")

        fun validar(
            env: Environment,
            site: SiteProperties,
            mail: MailProperties,
            push: PushProperties,
        ) {
            listOf(
                "DB_USER",
                "DB_PASSWORD",
                "mesamar.admin.password",
                "spring.mail.host",
                "spring.mail.username",
                "spring.mail.password",
            ).forEach { chave ->
                require(!placeholder(env.getProperty(chave).orEmpty())) { "Configuração de produção ausente ou placeholder: $chave" }
            }
            require(!env.getProperty("spring.flyway.enabled", Boolean::class.java, true)) {
                "Execute as migrações com a conta separada; Flyway deve estar desligado na aplicação"
            }
            val url = runCatching { URI(site.publicBaseUrl) }.getOrNull()
            val host = url?.host.orEmpty().lowercase()
            require(
                url?.scheme == "https" &&
                    host.contains('.') &&
                    !placeholder(host) &&
                    !host.endsWith(".local") &&
                    !host.endsWith(".internal") &&
                    listOf(".test", ".invalid", ".example").none { host.endsWith(it) } &&
                    !Regex("[0-9.]+").matches(host) &&
                    url.userInfo == null &&
                    url.query == null &&
                    url.fragment == null &&
                    url.path.isNullOrEmpty() &&
                    (url.port == -1 || url.port == 443),
            ) { "MESAMAR_PUBLIC_URL deve ser uma origem HTTPS pública sem caminho" }
            val remetenteValido =
                runCatching {
                    jakarta.mail.internet.InternetAddress
                        .parse(mail.from, true)
                        .single()
                        .validate()
                    true
                }.getOrDefault(false)
            require(!mail.logarCorpoSemSmtp && !placeholder(mail.from) && remetenteValido) {
                "Configure MESAMAR_MAIL_FROM e desative logs de corpo de e-mail"
            }
            require(env.getProperty("spring.mail.port", Int::class.java, 587) in 1..65535) { "Porta SMTP inválida" }
            require(!(push.publicKey.isBlank() xor push.privateKey.isBlank())) { "Configure as duas chaves VAPID ou deixe ambas vazias" }
            require(!push.permitirHttp && push.hostsExtras.isEmpty()) { "Push de produção não aceita destinos de teste" }
            if (push.habilitado) {
                require(!placeholder(push.publicKey) && !placeholder(push.privateKey)) { "Chaves VAPID contêm placeholders" }
                require(
                    runCatching {
                        val publica = Base64Url.decodificar(push.publicKey)
                        require(publica.size == 65 && Base64Url.decodificar(push.privateKey).size == 32)
                        val jwt =
                            Vapid(
                                push.publicKey,
                                push.privateKey,
                                site.publicBaseUrl,
                            ).jwt(site.publicBaseUrl, java.time.Instant.now())
                        java.security.Signature
                            .getInstance("SHA256withECDSAinP1363Format")
                            .apply {
                                initVerify(WebPushCripto.publicaDeBytes(publica))
                                update(jwt.substringBeforeLast('.').toByteArray())
                            }.verify(Base64Url.decodificar(jwt.substringAfterLast('.')))
                    }.getOrDefault(false),
                ) { "Par de chaves VAPID inválido ou incompatível" }
                if (push.subject.isNotBlank()) {
                    val contato = runCatching { URI(push.subject) }.getOrNull()
                    require(
                        contato != null &&
                            (
                                contato.scheme == "mailto" &&
                                    contato.schemeSpecificPart.contains('@') ||
                                    contato.scheme == "https" &&
                                    !contato.host.isNullOrBlank()
                            ) &&
                            !placeholder(push.subject),
                    ) { "Contato VAPID inválido" }
                }
            }
        }
    }
}
