package br.com.mesamar.support

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Um "serviço de push" falso (como o Google FCM ou o da Mozilla) na própria máquina: guarda o que o servidor
 * mandou e responde o código que o teste quiser (201 normal, 410 para inscrição que não existe mais…).
 */
class FakePushServer : AutoCloseable {
    class Recebido(
        val caminho: String,
        val cabecalhos: Map<String, String>,
        val corpo: ByteArray,
    )

    private val servidor = HttpServer.create(InetSocketAddress(0), 0)
    val recebidos = CopyOnWriteArrayList<Recebido>()
    private val respostas = ConcurrentHashMap<String, Int>()

    val porta: Int get() = servidor.address.port

    init {
        servidor.executor = Executors.newCachedThreadPool()
        servidor.createContext("/") { troca ->
            val corpo = troca.requestBody.readAllBytes()
            val cabecalhos = troca.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            recebidos += Recebido(troca.requestURI.path, cabecalhos, corpo)
            troca.sendResponseHeaders(respostas[troca.requestURI.path] ?: 201, -1)
            troca.close()
        }
        servidor.start()
    }

    /** O endereço de uma inscrição (o `endpoint` que o navegador entregaria). */
    fun endpoint(caminho: String) = "http://localhost:$porta$caminho"

    fun responder(
        caminho: String,
        codigo: Int,
    ) {
        respostas[caminho] = codigo
    }

    fun em(caminho: String): List<Recebido> = recebidos.filter { it.caminho == caminho }

    /** Espera chegarem [quantidade] envios no [caminho]. */
    fun esperar(
        caminho: String,
        quantidade: Int = 1,
        ms: Long = 5_000,
    ): Boolean {
        val fim = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < fim) {
            if (em(caminho).size >= quantidade) return true
            Thread.sleep(25)
        }
        return em(caminho).size >= quantidade
    }

    /** `true` se NADA chegar ao [caminho] durante [ms]. */
    fun nada(
        caminho: String,
        ms: Long = 1_500,
    ): Boolean {
        Thread.sleep(ms)
        return em(caminho).isEmpty()
    }

    override fun close() {
        servidor.stop(0)
        (servidor.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }
}
