package br.com.mesamar.config

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.CssLinkResourceTransformer
import org.springframework.web.servlet.resource.HttpResource
import org.springframework.web.servlet.resource.ResourceTransformer
import org.springframework.web.servlet.resource.ResourceTransformerChain
import org.springframework.web.servlet.resource.VersionResourceResolver
import java.time.Duration

/**
 * Cache longo só para arquivos com hash no nome.
 *
 * - CSS, imagens e fontes da landing (pastas css, img, fonts e js): o hash é gerado aqui
 *   (VersionResourceResolver) e o Thymeleaf escreve a URL já versionada
 *   (ex.: /css/landing-3f2a….css).
 * - Pastas assets dos fronts (staff e cliente): o Vite já gera nomes com hash.
 *
 * Obs.: não escrever padrões com barra seguida de dois asteriscos dentro deste
 * comentário, porque o Kotlin interpreta como início de comentário aninhado.
 *
 * Os index.html dos fronts e as páginas da landing NÃO entram aqui, para que
 * uma versão nova apareça no próximo acesso.
 */
@Configuration
class StaticCacheConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val oneYear = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()

        listOf("css", "img", "fonts", "js").forEach { dir ->
            registry
                .addResourceHandler("/$dir/**")
                .addResourceLocations("classpath:/static/$dir/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(VersionResourceResolver().addContentVersionStrategy("/**"))
                // Envolve também o CSS já transformado, para conservar os headers da versão.
                .addTransformer(CacheVersionado(oneYear))
                // Reescreve os url(...) de dentro do CSS (ex.: a fonte) para as versões com hash.
                .addTransformer(CssLinkResourceTransformer())
        }

        FrontendRoutesConfig.FRONTEND_ROUTES.forEach { route ->
            registry
                .addResourceHandler("/$route/assets/**")
                .addResourceLocations("classpath:/static/$route/assets/")
                .setCacheControl(oneYear)
        }
    }
}

/** O resolver só entrega uma URL com hash se ele conferir com o conteúdo; nomes sem hash revalidam. */
private class CacheVersionado(
    private val longo: CacheControl,
) : ResourceTransformer {
    override fun transform(
        request: HttpServletRequest,
        resource: Resource,
        transformerChain: ResourceTransformerChain,
    ): Resource {
        val arquivo = transformerChain.transform(request, resource)
        val versionado = Regex(".*-[a-f0-9]{32}\\.[^/]+$").matches(request.requestURI)
        return object : HttpResource, Resource by arquivo {
            override fun getResponseHeaders(): HttpHeaders =
                HttpHeaders().apply {
                    if (arquivo is HttpResource) putAll(arquivo.responseHeaders)
                    cacheControl = (if (versionado) longo else CacheControl.noCache()).headerValue
                }
        }
    }
}
