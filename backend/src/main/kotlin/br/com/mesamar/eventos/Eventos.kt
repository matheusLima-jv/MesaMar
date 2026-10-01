package br.com.mesamar.eventos

import com.zaxxer.hikari.HikariDataSource
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.sql.DataSource

/** O canal do PostgreSQL por onde passam os avisos de "um pedido mudou". */
const val CANAL_PEDIDOS = "mesamar_pedidos"

/** Um pedido nasceu ou mudou. Leva só ids: quem recebe busca o estado novo pela API (que confere a barraca). */
data class EventoPedido(
    val barracaId: UUID,
    val comandaId: UUID,
    val pedidoId: UUID,
)

/**
 * Publica os eventos no PostgreSQL (`NOTIFY`) em vez de falar direto com os assinantes em memória.
 * Duas vantagens, pelo mesmo custo: o aviso só sai se a transação **der commit** (o `NOTIFY` é entregue no
 * commit, então ninguém é avisado de uma mudança que foi desfeita), e com mais de um servidor todos recebem.
 */
@Component
class PublicadorEventos(
    private val jdbc: JdbcTemplate,
) {
    fun pedidoMudou(
        barracaId: UUID,
        comandaId: UUID,
        pedidoId: UUID,
    ) {
        jdbc.queryForList("SELECT pg_notify(?, ?)", CANAL_PEDIDOS, """{"b":"$barracaId","c":"$comandaId","p":"$pedidoId"}""")
    }
}

/** Quem está ouvindo (um celular). Os envios são serializados: o `SseEmitter` não aceita duas escritas ao mesmo tempo. */
class Assinante(
    private val emitter: SseEmitter,
) {
    @Synchronized
    fun enviar(evento: SseEmitter.SseEventBuilder): Boolean =
        try {
            emitter.send(evento)
            true
        } catch (e: Exception) {
            false
        }

    fun encerrar() {
        runCatching { emitter.complete() }
    }
}

/**
 * Registro dos assinantes de tempo real (SSE), por barraca (app do quiosque) e por comanda (página do cliente).
 * Um evento de uma barraca só chega aos assinantes **dela**.
 */
@Component
class HubEventos {
    private val log = LoggerFactory.getLogger(javaClass)
    private val porBarraca = ConcurrentHashMap<UUID, CopyOnWriteArrayList<Assinante>>()
    private val porComanda = ConcurrentHashMap<UUID, CopyOnWriteArrayList<Assinante>>()

    // Cada envio vai numa thread virtual: um celular lento não segura a entrega para os outros.
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    fun assinarBarraca(barracaId: UUID): SseEmitter = assinar(porBarraca, barracaId, LIMITE_POR_BARRACA)

    fun assinarComanda(comandaId: UUID): SseEmitter = assinar(porComanda, comandaId, LIMITE_POR_COMANDA)

    fun distribuir(e: EventoPedido) {
        val dados = """{"pedido":"${e.pedidoId}"}"""
        porBarraca[e.barracaId]?.let { enviarA(porBarraca, e.barracaId, it) { SseEmitter.event().name("fila").data(dados) } }
        porComanda[e.comandaId]?.let { enviarA(porComanda, e.comandaId, it) { SseEmitter.event().name("pedido").data(dados) } }
    }

    /** Depois de uma queda da conexão com o banco, eventos podem ter se perdido: manda todo mundo buscar o estado de novo. */
    fun ressincronizar() {
        todos().forEach { (mapa, chave, lista) -> enviarA(mapa, chave, lista) { SseEmitter.event().name("ressincronizar").data("{}") } }
    }

    /**
     * Linha de comentário a cada 25 s: mantém a conexão viva atrás do proxy e da Cloudflare (que cortam conexão
     * parada) e faz o servidor notar o celular que sumiu.
     */
    @Scheduled(fixedDelayString = "\${mesamar.sse.batimento:PT25S}")
    fun batimento() {
        todos().forEach { (mapa, chave, lista) -> enviarA(mapa, chave, lista) { SseEmitter.event().comment("ping") } }
    }

    /** Assinantes agora, só para os testes e o diagnóstico. */
    fun total(): Int = todos().sumOf { it.lista.size }

    private data class Grupo(
        val mapa: ConcurrentHashMap<UUID, CopyOnWriteArrayList<Assinante>>,
        val chave: UUID,
        val lista: CopyOnWriteArrayList<Assinante>,
    )

    private fun todos(): List<Grupo> =
        porBarraca.map { (k, v) -> Grupo(porBarraca, k, v) } + porComanda.map { (k, v) -> Grupo(porComanda, k, v) }

    private fun assinar(
        mapa: ConcurrentHashMap<UUID, CopyOnWriteArrayList<Assinante>>,
        chave: UUID,
        limite: Int,
    ): SseEmitter {
        val emitter = SseEmitter(TEMPO_MAXIMO_MS)
        val assinante = Assinante(emitter)
        val lista = mapa.compute(chave) { _, atual -> (atual ?: CopyOnWriteArrayList()).also { it += assinante } }!!
        // Abas esquecidas abertas: passou do limite, a mais antiga cai (o navegador reconecta sozinho se ainda precisar)
        while (lista.size > limite) lista.removeFirstOrNull()?.encerrar()
        val sair = Runnable { retirar(mapa, chave, assinante) }
        emitter.onCompletion(sair)
        emitter.onError { sair.run() }
        // Fim do tempo máximo: encerra de propósito, e o EventSource do navegador reconecta sozinho
        emitter.onTimeout { emitter.complete() }
        assinante.enviar(SseEmitter.event().comment("conectado"))
        return emitter
    }

    private fun retirar(
        mapa: ConcurrentHashMap<UUID, CopyOnWriteArrayList<Assinante>>,
        chave: UUID,
        assinante: Assinante,
    ) {
        mapa.computeIfPresent(chave) { _, lista ->
            lista.remove(assinante)
            if (lista.isEmpty()) null else lista
        }
    }

    private fun enviarA(
        mapa: ConcurrentHashMap<UUID, CopyOnWriteArrayList<Assinante>>,
        chave: UUID,
        lista: List<Assinante>,
        evento: () -> SseEmitter.SseEventBuilder,
    ) {
        lista.forEach { assinante ->
            executor.execute {
                if (!assinante.enviar(evento())) {
                    log.debug("Assinante de tempo real caiu; removendo")
                    retirar(mapa, chave, assinante)
                    assinante.encerrar()
                }
            }
        }
    }

    companion object {
        /** 30 minutos: depois disso a conexão é encerrada e o navegador reconecta sozinho. */
        const val TEMPO_MAXIMO_MS = 30L * 60 * 1000
        const val LIMITE_POR_BARRACA = 40
        const val LIMITE_POR_COMANDA = 4
    }
}

/**
 * Mantém uma conexão própria com o PostgreSQL ouvindo o canal (`LISTEN`) e repassa cada aviso ao [HubEventos].
 * A conexão é separada do pool, porque fica presa para sempre. Se cair, tenta de novo (1 s, 2 s… até 30 s) e,
 * ao voltar, manda os assinantes buscarem o estado de novo, pois avisos podem ter se perdido.
 */
@Component
class ReceptorEventos(
    private val hub: HubEventos,
    private val dataSource: DataSource,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var rodando = false
    private var thread: Thread? = null

    @Volatile
    private var conexao: Connection? = null

    override fun start() {
        rodando = true
        thread = Thread(::ouvir, "pg-listen").apply { isDaemon = true }.also { it.start() }
    }

    override fun stop() {
        rodando = false
        runCatching { conexao?.close() }
        thread?.interrupt()
    }

    override fun isRunning(): Boolean = rodando

    private fun ouvir() {
        var espera = ESPERA_INICIAL_MS
        var primeiraVez = true
        while (rodando) {
            try {
                abrir().use { conn ->
                    conexao = conn
                    conn.createStatement().use { it.execute("LISTEN $CANAL_PEDIDOS") }
                    if (!primeiraVez) hub.ressincronizar()
                    primeiraVez = false
                    espera = ESPERA_INICIAL_MS
                    val pg = conn.unwrap(PGConnection::class.java)
                    while (rodando) {
                        pg.getNotifications(ESPERA_AVISO_MS)?.forEach { n -> n.parameter?.let(::repassar) }
                    }
                }
            } catch (e: Exception) {
                if (!rodando) return
                log.warn("Ouvinte de eventos caiu ({}). Tentando de novo em {} ms", e.message, espera)
                runCatching { Thread.sleep(espera) }
                espera = minOf(espera * 2, ESPERA_MAXIMA_MS)
            }
        }
    }

    private fun abrir(): Connection {
        val hikari = dataSource.unwrap(HikariDataSource::class.java)
        return DriverManager.getConnection(hikari.jdbcUrl, hikari.username, hikari.password)
    }

    private fun repassar(payload: String) {
        val m = PADRAO.matchEntire(payload)
        if (m == null) {
            log.warn("Aviso de pedido ilegível: {}", payload)
            return
        }
        hub.distribuir(
            EventoPedido(UUID.fromString(m.groupValues[1]), UUID.fromString(m.groupValues[2]), UUID.fromString(m.groupValues[3])),
        )
    }

    companion object {
        private const val UUID_RE = "[0-9a-fA-F-]{36}"
        private val PADRAO = Regex("""\{"b":"($UUID_RE)","c":"($UUID_RE)","p":"($UUID_RE)"}""")
        private const val ESPERA_INICIAL_MS = 1_000L
        private const val ESPERA_MAXIMA_MS = 30_000L
        private const val ESPERA_AVISO_MS = 30_000
    }
}
