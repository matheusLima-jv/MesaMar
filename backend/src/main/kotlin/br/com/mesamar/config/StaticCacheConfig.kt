package br.com.mesamar.config

import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.VersionResourceResolver
import java.time.Duration

/**
 * Cache longo só para arquivos com hash no nome.
 *
 * - /css/** e /img/** da landing: o hash é gerado aqui (VersionResourceResolver) e o
 *   Thymeleaf escreve a URL já versionada (ex.: /css/landing-3f2a….css).
 * - /staff/assets/** e /cliente/assets/**: o Vite já gera nomes com hash.
 *
 * Os index.html dos fronts e as páginas da landing NÃO entram aqui, para que
 * uma versão nova apareça no próximo acesso.
 */
@Configuration
class StaticCacheConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val oneYear = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()

        listOf("css", "img").forEach { dir ->
            registry
                .addResourceHandler("/$dir/**")
                .addResourceLocations("classpath:/static/$dir/")
                .setCacheControl(oneYear)
                .resourceChain(true)
                .addResolver(VersionResourceResolver().addContentVersionStrategy("/**"))
        }

        FrontendRoutesConfig.FRONTEND_ROUTES.forEach { route ->
            registry
                .addResourceHandler("/$route/assets/**")
                .addResourceLocations("classpath:/static/$route/assets/")
                .setCacheControl(oneYear)
        }
    }
}
