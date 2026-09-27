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

    fun get(path: String): HttpResponse<String> = client.send(request(path), HttpResponse.BodyHandlers.ofString())

    fun getBytes(path: String): HttpResponse<ByteArray> = client.send(request(path), HttpResponse.BodyHandlers.ofByteArray())

    private fun request(path: String): HttpRequest =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port$path"))
            .GET()
            .build()
}

fun HttpResponse<*>.header(name: String): String = headers().firstValue(name).orElse("")
