package br.com.mesamar.config

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.common.RateLimiter
import br.com.mesamar.conta.OwnerLoginSuccessHandler
import br.com.mesamar.conta.OwnerUserDetailsService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.staff.StaffPrincipalCheckFilter
import br.com.mesamar.staff.StaffSessao
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

/** Acesso ao painel /admin (dono da plataforma). Sem senha configurada, ninguém entra. */
@ConfigurationProperties(prefix = "mesamar.admin")
data class AdminProperties(
    val username: String = "admin",
    val password: String = "",
)

/**
 * Três áreas com regras próprias:
 *  1. /admin  — dono da plataforma (usuário único do .env)
 *  2. /painel — donos de barraca (contas no banco)
 *  3. resto   — site público, sem login e sem cookies
 * Cada área tem o seu próprio login: a senha do admin não entra no painel e vice-versa.
 */
@Configuration
class SecurityConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    @Order(1)
    fun adminChain(
        http: HttpSecurity,
        admin: AdminProperties,
        encoder: PasswordEncoder,
        rateLimiter: RateLimiter,
    ): SecurityFilterChain {
        http
            .securityMatcher("/admin/**")
            .securityContext { it.securityContextRepository(contextRepository("MESAMAR_ADMIN")) }
            .authenticationManager(authenticationManager(adminUsers(admin, encoder), encoder))
            .authorizeHttpRequests {
                it
                    .requestMatchers("/admin/login")
                    .permitAll()
                    .anyRequest()
                    .hasRole("ADMIN")
            }.formLogin {
                it
                    .loginPage("/admin/login")
                    .loginProcessingUrl("/admin/login")
                    .defaultSuccessUrl("/admin", true)
                    .failureUrl("/admin/login?erro")
                    .permitAll()
            }.logout {
                it
                    .logoutUrl("/admin/logout")
                    .logoutSuccessUrl("/admin/login?saiu")
            }.addFilterBefore(LoginRateLimitFilter(rateLimiter, "/admin/login"), UsernamePasswordAuthenticationFilter::class.java)
        applyHeaders(http)
        return http.build()
    }

    @Bean
    @Order(2)
    fun painelChain(
        http: HttpSecurity,
        owners: OwnerUserDetailsService,
        loginSuccess: OwnerLoginSuccessHandler,
        encoder: PasswordEncoder,
        rateLimiter: RateLimiter,
    ): SecurityFilterChain {
        http
            .securityMatcher("/painel/**")
            .securityContext { it.securityContextRepository(contextRepository("MESAMAR_PAINEL")) }
            .authenticationManager(authenticationManager(owners, encoder))
            .authorizeHttpRequests {
                it
                    .requestMatchers(*PAINEL_PUBLICO)
                    .permitAll()
                    .anyRequest()
                    .hasRole("OWNER")
            }.formLogin {
                it
                    .loginPage("/painel/entrar")
                    .loginProcessingUrl("/painel/entrar")
                    .usernameParameter("email")
                    .passwordParameter("senha")
                    .successHandler(loginSuccess)
                    .failureUrl("/painel/entrar?erro")
                    .permitAll()
            }.logout {
                it
                    .logoutUrl("/painel/sair")
                    .logoutSuccessUrl("/painel/entrar?saiu")
            }.addFilterBefore(LoginRateLimitFilter(rateLimiter, "/painel/entrar"), UsernamePasswordAuthenticationFilter::class.java)
        applyHeaders(http)
        return http.build()
    }

    /**
     * API do app do quiosque (/staff). Login por JSON (StaffController), sessão com chave própria,
     * CSRF no cabeçalho X-CSRF-TOKEN (o app pega o token em GET /api/staff/sessao).
     * Sem redirects para páginas de login: quem não está logado recebe 401 em JSON.
     */
    @Bean
    @Order(3)
    fun staffChain(
        http: HttpSecurity,
        staffSessao: StaffSessao,
        atendentes: AtendenteRepository,
        usuarios: UsuarioRepository,
        barracas: BarracaRepository,
    ): SecurityFilterChain {
        http
            .securityMatcher("/api/staff/**")
            .securityContext { it.securityContextRepository(staffSessao.repository) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(*STAFF_PUBLICO)
                    .permitAll()
                    .anyRequest()
                    .hasRole("STAFF")
            }.csrf {
                it
                    .csrfTokenRepository(HttpSessionCsrfTokenRepository())
                    // Token "cru" (sem a máscara XOR): o app envia de volta o mesmo valor que recebeu.
                    .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
            }.exceptionHandling {
                it
                    .authenticationEntryPoint { _, response, _ -> jsonErro(response, 401, "sessao", "Entre de novo no app.") }
                    .accessDeniedHandler { _, response, _ -> jsonErro(response, 403, "csrf", "Sessão expirada. Tente de novo.") }
            }.addFilterBefore(StaffPrincipalCheckFilter(atendentes, usuarios, barracas), AnonymousAuthenticationFilter::class.java)
            .requestCache { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
        applyHeaders(http)
        return http.build()
    }

    @Bean
    @Order(4)
    fun publicChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .csrf {
                // Formulários públicos e anônimos: sem sessão para proteger, e a landing fica sem
                // cookies (promessa da política de privacidade). Admin e painel continuam com CSRF.
                it.ignoringRequestMatchers("/lista-de-espera", "/api/teste-sinal/**")
            }
        applyHeaders(http)
        return http.build()
    }

    private fun applyHeaders(http: HttpSecurity) {
        http.headers { headers ->
            headers
                .contentSecurityPolicy { it.policyDirectives(CONTENT_SECURITY_POLICY) }
                .referrerPolicy { it.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN) }
        }
    }

    /**
     * Cada área guarda o login numa chave própria da sessão: entrar no painel não derruba
     * o login do admin (e vice-versa), e um login nunca vale na área do outro.
     */
    private fun contextRepository(key: String) = HttpSessionSecurityContextRepository().apply { setSpringSecurityContextKey(key) }

    private fun jsonErro(
        response: HttpServletResponse,
        status: Int,
        codigo: String,
        mensagem: String,
    ) {
        response.status = status
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("""{"erro":"$codigo","mensagem":"$mensagem"}""")
    }

    private fun authenticationManager(
        users: UserDetailsService,
        encoder: PasswordEncoder,
    ): AuthenticationManager {
        val provider = DaoAuthenticationProvider(users).apply { setPasswordEncoder(encoder) }
        return ProviderManager(provider)
    }

    private fun adminUsers(
        admin: AdminProperties,
        encoder: PasswordEncoder,
    ): UserDetailsService {
        if (admin.password.isBlank()) {
            log.warn("MESAMAR_ADMIN_PASSWORD não configurada: o painel /admin fica trancado.")
            return InMemoryUserDetailsManager()
        }
        val user =
            User
                .withUsername(admin.username)
                .password(encoder.encode(admin.password)!!)
                .roles("ADMIN")
                .build()
        return InMemoryUserDetailsManager(user)
    }

    companion object {
        val STAFF_PUBLICO =
            arrayOf("/api/staff/sessao", "/api/staff/entrar/**", "/api/staff/barracas/*/atendentes", "/api/staff/sair")

        val PAINEL_PUBLICO =
            arrayOf("/painel/entrar", "/painel/cadastro", "/painel/esqueci-senha", "/painel/nova-senha")

        /**
         * Só recursos do próprio domínio, mais os dois serviços opcionais da Cloudflare
         * (Web Analytics e Turnstile). Sem scripts inline.
         */
        const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; " +
                "script-src 'self' https://static.cloudflareinsights.com https://challenges.cloudflare.com; " +
                "connect-src 'self' https://cloudflareinsights.com; " +
                "frame-src https://challenges.cloudflare.com; " +
                "img-src 'self' data:; " +
                "style-src 'self'; " +
                "font-src 'self'; " +
                "form-action 'self'; " +
                "frame-ancestors 'none'; " +
                "base-uri 'self'; " +
                "object-src 'none'"
    }
}

/** Freia tentativas de adivinhar senha: 10 tentativas de login por IP a cada 10 minutos. */
class LoginRateLimitFilter(
    private val rateLimiter: RateLimiter,
    private val loginPath: String,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !(request.method == "POST" && request.servletPath == loginPath)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!rateLimiter.tryAcquire("login:$loginPath:${request.remoteAddr}", LIMIT, WINDOW)) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "text/plain;charset=UTF-8"
            response.writer.write("Muitas tentativas de login. Espere alguns minutos.")
            return
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val LIMIT = 10
        val WINDOW: Duration = Duration.ofMinutes(10)
    }
}
