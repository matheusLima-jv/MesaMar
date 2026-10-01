package br.com.mesamar.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Serve o index.html de cada front quando a raiz dele é acessada
 * (ex.: /staff/ → /staff/index.html). O Spring só faz isso sozinho para "/".
 */
@Configuration
class FrontendRoutesConfig : WebMvcConfigurer {
    override fun addViewControllers(registry: ViewControllerRegistry) {
        FRONTEND_ROUTES.forEach { route ->
            registry.addViewController("/$route").setViewName("redirect:/$route/")
            registry.addViewController("/$route/").setViewName("forward:/$route/index.html")
        }
        // Link do QR do cliente: a página do cliente lê o token da própria URL.
        registry.addViewController("/c/{token}").setViewName("forward:/cliente/index.html")
        // O service worker fica em /c/ para controlar essa página (o escopo de um SW é a pasta dele).
        registry.addViewController("/c/sw.js").setViewName("forward:/cliente/sw.js")
    }

    companion object {
        val FRONTEND_ROUTES = listOf("staff", "cliente")
    }
}
