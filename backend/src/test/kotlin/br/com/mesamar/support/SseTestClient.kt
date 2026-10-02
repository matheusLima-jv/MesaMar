package br.com.mesamar.support

import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * Lê um fluxo SSE numa thread à parte e guarda as linhas, para o teste esperar um evento (ou a falta dele).
 * Fechar encerra a conexão, como um celular que sai da página.
 */
class SseTestClient(
    private val resposta: HttpResponse<Stream<String>>,
) : AutoCloseable {
    private val linhas = LinkedBlockingQueue<String>()

    @Volatile
    private var aberto = true

    private val leitor =
        Thread {
            try {
                resposta.body().forEach { linhas.add(it) }
            } catch (e: Exception) {
                // a conexão foi fechada pelo teste ou pelo servidor
            }
        }.apply {
            isDaemon = true
            start()
        }

    val status: Int get() = resposta.statusCode()

    /** Espera uma linha que comece com [prefixo] (ex.: `event:fila`, `data:`, `:ping`, o formato que o Spring escreve). `null` se não vier a tempo. */
    fun esperar(
        prefixo: String,
        ms: Long = 5_000,
    ): String? {
        val fim = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (true) {
            val restante = TimeUnit.NANOSECONDS.toMillis(fim - System.nanoTime())
            if (restante <= 0) return null
            val linha = linhas.poll(restante, TimeUnit.MILLISECONDS) ?: return null
            if (linha.startsWith(prefixo)) return linha
        }
    }

    /** `true` se NENHUMA linha com [prefixo] chegar durante [ms]. */
    fun nada(
        prefixo: String,
        ms: Long = 1_500,
    ): Boolean = esperar(prefixo, ms) == null

    override fun close() {
        if (aberto) {
            aberto = false
            runCatching { resposta.body().close() }
            leitor.interrupt()
        }
    }
}
