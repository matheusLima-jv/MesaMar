package br.com.mesamar.support

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Cliente HTTP mínimo para chamar a aplicação real nos testes de integração.
 *
 * `ip` vira o cabeçalho X-Forwarded-For, que o Spring usa como IP do cliente
 * (server.forward-headers-strategy=framework). Assim cada teste tem o seu "aparelho"
 * e os limites por IP de um teste não atrapalham os outros.
 * `cookies = true` guarda os cookies entre as chamadas (necessário para o login do painel).
 */
class HttpTestClient(
    private val port: Int,
    private val ip: String? = null,
    cookies: Boolean = false,
) {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .apply { if (cookies) cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)) }
            .build()

    fun get(path: String): HttpResponse<String> = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString())

    fun getBytes(path: String): HttpResponse<ByteArray> = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    fun postForm(
        path: String,
        fields: Map<String, String>,
    ): HttpResponse<String> {
        val body =
            fields.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8)
            }
        val request =
            request(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    fun postJson(
        path: String,
        json: String,
    ): HttpResponse<String> {
        val request =
            request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun request(path: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        ip?.let { builder.header("X-Forwarded-For", it) }
        return builder
    }
}

fun HttpResponse<*>.header(name: String): String = headers().firstValue(name).orElse("")

/** Gera um IP de teste único (faixa de documentação 198.51.100.0/24 + contador). */
object TestIps {
    private var counter = 0

    @Synchronized
    fun next(): String {
        counter++
        return "198.51.${100 + counter / 250}.${counter % 250 + 1}"
    }
}

/**
 * Como assertContains, mas quando falha mostra só o trecho relevante do HTML
 * (a partir de `anchor`), em vez da página inteira.
 */
fun assertHtmlContains(
    html: String,
    expected: String,
    anchor: String = "<form",
) {
    if (!html.contains(expected)) {
        val excerpt = html.substringAfter(anchor, html).take(4000)
        kotlin.test.fail("Não encontrado no HTML: «$expected»\n--- trecho a partir de $anchor ---\n$excerpt")
    }
}
