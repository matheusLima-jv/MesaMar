package br.com.mesamar.config

import br.com.mesamar.common.RateLimiter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

/** Acesso ao painel /admin. Sem senha configurada, ninguém entra (o painel fica trancado). */
@ConfigurationProperties(prefix = "mesamar.admin")
data class AdminProperties(
    val username: String = "admin",
    val password: String = "",
)

@Configuration
class SecurityConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        rateLimiter: RateLimiter,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests {
                it
                    .requestMatchers("/admin/login")
                    .permitAll()
                    .requestMatchers("/admin", "/admin/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll()
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
            }.csrf {
                // Formulários públicos e anônimos: sem sessão para proteger, e manter a landing
                // sem cookies (promessa da política de privacidade). O admin continua com CSRF.
                it.ignoringRequestMatchers("/lista-de-espera", "/api/teste-sinal/**")
            }.headers { headers ->
                headers
                    .contentSecurityPolicy { it.policyDirectives(CONTENT_SECURITY_POLICY) }
                    .referrerPolicy { it.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN) }
            }.addFilterBefore(LoginRateLimitFilter(rateLimiter), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun userDetailsService(
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

/** Freia tentativas de adivinhar a senha: 10 tentativas de login por IP a cada 10 minutos. */
class LoginRateLimitFilter(
    private val rateLimiter: RateLimiter,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !(request.method == "POST" && request.servletPath == "/admin/login")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!rateLimiter.tryAcquire("login:${request.remoteAddr}", LIMIT, WINDOW)) {
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
