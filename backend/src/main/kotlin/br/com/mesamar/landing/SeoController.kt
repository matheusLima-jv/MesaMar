package br.com.mesamar.landing

import br.com.mesamar.config.SiteProperties
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * robots.txt e sitemap.xml gerados pela aplicação, porque precisam da URL
 * pública absoluta (que muda entre local e produção).
 */
@RestController
class SeoController(
    private val site: SiteProperties,
) {
    @GetMapping("/robots.txt", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun robots(): String =
        """
        |User-agent: *
        |Allow: /
        |Disallow: /staff/
        |Disallow: /cliente/
        |Disallow: /actuator/
        |
        |Sitemap: ${site.absoluteUrl("/sitemap.xml")}
        |
        """.trimMargin()

    @GetMapping("/sitemap.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    fun sitemap(): String {
        val urls =
            PUBLIC_PAGES.joinToString("\n") { (path, priority) ->
                "  <url><loc>${site.absoluteUrl(path)}</loc><priority>$priority</priority></url>"
            }
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
            |$urls
            |</urlset>
            |
            """.trimMargin()
    }

    companion object {
        val PUBLIC_PAGES = listOf("/" to "1.0", "/privacidade" to "0.3")
    }
}
