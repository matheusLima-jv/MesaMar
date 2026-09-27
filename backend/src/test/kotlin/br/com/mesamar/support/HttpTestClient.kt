package br.com.mesamar.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Cliente HTTP mínimo para chamar a aplicação real nos testes de integração. */
class HttpTestClient(
    private val port: Int,
) {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    fun get(path: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .GET()
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
