package br.com.mesamar.eventos

import br.com.mesamar.common.ExecutorLimitado
import br.com.mesamar.common.SaudeOperacional
import com.zaxxer.hikari.HikariDataSource
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** O canal do PostgreSQL por onde passam os avisos de tempo real. */
const val CANAL_PEDIDOS = "mesamar_pedidos"

/**
 * O que pode acontecer e interessa em tempo real. Os avisos levam só ids (e o número do pedido): quem recebe busca
 * o estado pela API, que confere a barraca.
 */
sealed interface EventoTempoReal {
    val barracaId: UUID

    /** Um pedido nasceu ou mudou (status, item cancelado). */
    data class PedidoMudou(
        override val barracaId: UUID,
        val comandaId: UUID,
        val pedidoId: UUID,
    ) : EventoTempoReal

    /** O quiosque pausou ou voltou a receber pedidos: o app e todas as comandas da barraca precisam saber. */
    data class PausaMudou(
        override val barracaId: UUID,
    ) : EventoTempoReal

    /** O cardápio mudou (preço, esgotado, produto ou categoria): o app e as páginas abertas buscam o cardápio de novo. */
    data class CardapioMudou(
        override val barracaId: UUID,
    ) : EventoTempoReal

    /** A conta de uma comanda mudou (fechou, avisou que vai pagar, recebeu pagamento, foi bloqueada…). */
    data class ContaMudou(
        override val barracaId: UUID,
        val comandaId: UUID,
    ) : EventoTempoReal

    /** Um pedido passou do tempo de espera configurado. */
    data class PedidoAtrasado(
        override val barracaId: UUID,
        val pedidoId: UUID,
        val numero: Int,
    ) : EventoTempoReal

    /** O mesmo texto que vai pelo `NOTIFY` (só ids e números, nunca nome ou dado pessoal). */
    fun json(): String =
        when (this) {
            is PedidoMudou -> """{"t":"pedido","b":"$barracaId","c":"$comandaId","p":"$pedidoId"}"""
            is PausaMudou -> """{"t":"pausa","b":"$barracaId"}"""
            is CardapioMudou -> """{"t":"cardapio","b":"$barracaId"}"""
            is ContaMudou -> """{"t":"conta","b":"$barracaId","c":"$comandaId"}"""
            is PedidoAtrasado -> """{"t":"atraso","b":"$barracaId","p":"$pedidoId","n":$numero}"""
        }

    companion object {
        private fun texto(
            json: String,
            campo: String,
        ): String? = Regex(""""$campo":"([^"]+)"""").find(json)?.groupValues?.get(1)

        private fun id(
            json: String,
            campo: String,
        ): UUID? = texto(json, campo)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        /** Lê um aviso do `NOTIFY`. `null` se não for um aviso conhecido. */
        fun ler(json: String): EventoTempoReal? {
            val barraca = id(json, "b") ?: return null
            return when (texto(json, "t")) {
                "pedido" -> PedidoMudou(barraca, id(json, "c") ?: return null, id(json, "p") ?: return null)
                "pausa" -> PausaMudou(barraca)
                "cardapio" -> CardapioMudou(barraca)
                "conta" -> ContaMudou(barraca, id(json, "c") ?: return null)
                "atraso" ->
                    PedidoAtrasado(
                        barraca,
                        id(json, "p") ?: return null,
                        Regex(""""n":(\d+)""")
                            .find(json)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull() ?: return null,
                    )
                else -> null
            }
        }
    }
}

/**
 * Publica os eventos no PostgreSQL (`NOTIFY`) em vez de falar direto com os assinantes em memória.
 * Duas vantagens, pelo mesmo custo: o aviso só sai se a transação **der commit** (o `NOTIFY` é entregue no
 * commit, então ninguém é avisado de uma mudança que foi desfeita), e com mais de um servidor todos recebem.
 */
@Component
class PublicadorEventos(
    private val jdbc: JdbcTemplate,
) {
    fun publicar(evento: EventoTempoReal) {
        jdbc.queryForList("SELECT pg_notify(?, ?)", CANAL_PEDIDOS, evento.json())
    }

    fun pedidoMudou(
        barracaId: UUID,
        comandaId: UUID,
        pedidoId: UUID,
    ) = publicar(EventoTempoReal.PedidoMudou(barracaId, comandaId, pedidoId))

    fun pausaMudou(barracaId: UUID) = publicar(EventoTempoReal.PausaMudou(barracaId))

    fun cardapioMudou(barracaId: UUID) = publicar(EventoTempoReal.CardapioMudou(barracaId))

    fun contaMudou(
        barracaId: UUID,
        comandaId: UUID,
    ) = publicar(EventoTempoReal.ContaMudou(barracaId, comandaId))

    fun pedidoAtrasado(
        barracaId: UUID,
        pedidoId: UUID,
        numero: Int,
    ) = publicar(EventoTempoReal.PedidoAtrasado(barracaId, pedidoId, numero))
}

private typealias Registro = ConcurrentHashMap<UUID, CopyOnWriteArrayList<Assinante>>

/** Quem está ouvindo (um celular). Os envios são serializados: o `SseEmitter` não aceita duas escritas ao mesmo tempo. */
class Assinante(
    private val emitter: SseEmitter,
    /** O app do quiosque informa como conferir se quem abriu a conexão ainda pode estar nela; a página do cliente, não. */
    val aindaTemAcesso: (() -> Boolean)? = null,
) {
    val envioPendente = AtomicBoolean(false)

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
 * Registro dos assinantes de tempo real (SSE): o app do quiosque, por barraca, e a página do cliente, por comanda
 * (e também por barraca, para a pausa de pedidos chegar a todas as comandas dela).
 * Um evento de uma barraca só chega aos assinantes **dela**.
 */
@Component
class HubEventos {
    private val log = LoggerFactory.getLogger(javaClass)
    private val quiosques = Registro()
    private val comandas = Registro()
    private val clientesDaBarraca = Registro()

    // Cada envio vai numa thread virtual: um celular lento não segura a entrega para os outros.
    private val executor = ExecutorLimitado("sse")
    private val destinosPorAssinante = ConcurrentHashMap<Assinante, List<Pair<Registro, UUID>>>()

    fun assinarBarraca(
        barracaId: UUID,
        aindaTemAcesso: (() -> Boolean)? = null,
    ): SseEmitter = assinar(listOf(quiosques to barracaId), LIMITE_POR_BARRACA, aindaTemAcesso)

    fun assinarComanda(
        comandaId: UUID,
        barracaId: UUID,
    ): SseEmitter = assinar(listOf(comandas to comandaId, clientesDaBarraca to barracaId), LIMITE_POR_COMANDA)

    fun distribuir(e: EventoTempoReal) {
        when (e) {
            is EventoTempoReal.PedidoMudou -> {
                enviarA(quiosques, e.barracaId) { SseEmitter.event().name("fila").data("""{"pedido":"${e.pedidoId}"}""") }
                enviarA(comandas, e.comandaId) { SseEmitter.event().name("pedido").data("""{"pedido":"${e.pedidoId}"}""") }
            }
            is EventoTempoReal.ContaMudou -> {
                enviarA(quiosques, e.barracaId) { SseEmitter.event().name("conta").data("""{"comanda":"${e.comandaId}"}""") }
                enviarA(comandas, e.comandaId) { SseEmitter.event().name("conta").data("""{"comanda":"${e.comandaId}"}""") }
            }
            is EventoTempoReal.PausaMudou -> {
                enviarA(quiosques, e.barracaId) { SseEmitter.event().name("fila").data("""{"pausa":true}""") }
                enviarA(clientesDaBarraca, e.barracaId) { SseEmitter.event().name("pedido").data("""{"pausa":true}""") }
            }
            is EventoTempoReal.CardapioMudou -> {
                enviarA(quiosques, e.barracaId) { SseEmitter.event().name("cardapio").data("{}") }
                enviarA(clientesDaBarraca, e.barracaId) { SseEmitter.event().name("cardapio").data("{}") }
            }
            is EventoTempoReal.PedidoAtrasado ->
                enviarA(
                    quiosques,
                    e.barracaId,
                ) { SseEmitter.event().name("atraso").data("""{"pedido":"${e.pedidoId}","numero":${e.numero}}""") }
        }
    }

    /** Depois de uma queda da conexão com o banco, eventos podem ter se perdido: manda todo mundo buscar o estado de novo. */
    fun ressincronizar() {
        destinosPorAssinante.keys.forEach { enviar(it) { SseEmitter.event().name("ressincronizar").data("{}") } }
    }

    /**
     * Linha de comentário a cada 25 s: mantém a conexão viva atrás do proxy e da Cloudflare (que cortam conexão
     * parada) e faz o servidor notar o celular que sumiu.
     */
    @Scheduled(fixedDelayString = "\${mesamar.sse.batimento:PT25S}")
    fun batimento() {
        // A autorização é conferida só ao abrir a conexão; aqui ela é conferida de novo, para quem perdeu o acesso
        // (atendente desativado, sessões revogadas, senha trocada, barraca desativada) não continuar recebendo eventos
        revisarAcessos()
        destinosPorAssinante.keys.forEach { enviar(it) { SseEmitter.event().comment("ping") } }
    }

    /**
     * Derruba as conexões do app cujo dono perdeu o acesso. Roda a cada batimento e logo depois de uma revogação
     * explícita (desativar atendente, "sair de todos os aparelhos", troca de senha).
     */
    fun revisarAcessos() {
        quiosques.values
            .flatten()
            .filter { a -> a.aindaTemAcesso?.let { conferir -> !runCatching { conferir() }.getOrDefault(true) } == true }
            .forEach { a ->
                log.debug("Conexão do app sem acesso; encerrando")
                retirarDeTodos(a)
                a.encerrar()
            }
    }

    /** Como [revisarAcessos], mas só depois do commit da transação em andamento (o banco já mostra a revogação). */
    fun revisarAcessosAposCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = revisarAcessos()
                },
            )
        } else {
            revisarAcessos()
        }
    }

    /** Fecha as conexões em tempo real de uma comanda (o link foi trocado por um QR novo). */
    fun encerrarComanda(comandaId: UUID) {
        comandas[comandaId]?.toList()?.forEach { a ->
            retirarDeTodos(a)
            a.encerrar()
        }
    }

    /** Assinantes agora (cada conexão conta uma vez), só para os testes e o diagnóstico. */
    fun total(): Int = destinosPorAssinante.size

    private fun assinar(
        destinos: List<Pair<Registro, UUID>>,
        limite: Int,
        aindaTemAcesso: (() -> Boolean)? = null,
    ): SseEmitter {
        val emitter = SseEmitter(TEMPO_MAXIMO_MS)
        val assinante = Assinante(emitter, aindaTemAcesso)
        destinosPorAssinante[assinante] = destinos
        destinos.forEach { (registro, chave) ->
            registro.compute(chave) { _, atual -> (atual ?: CopyOnWriteArrayList()).also { it += assinante } }
        }
        // Abas esquecidas abertas: passou do limite, a mais antiga cai (o navegador reconecta sozinho se ainda precisar)
        val (registroPrincipal, chavePrincipal) = destinos.first()
        registroPrincipal[chavePrincipal]?.let { lista ->
            while (lista.size > limite) {
                val antigo = lista.removeFirstOrNull() ?: break
                retirarDeTodos(antigo)
                antigo.encerrar()
            }
        }
        val sair = Runnable { retirarDeTodos(assinante) }
        emitter.onCompletion(sair)
        emitter.onError { sair.run() }
        // Fim do tempo máximo: encerra de propósito, e o EventSource do navegador reconecta sozinho
        emitter.onTimeout { emitter.complete() }
        assinante.enviar(SseEmitter.event().comment("conectado"))
        return emitter
    }

    private fun retirarDeTodos(assinante: Assinante) {
        destinosPorAssinante.remove(assinante)?.forEach { (registro, chave) ->
            registro.computeIfPresent(chave) { _, lista ->
                lista.remove(assinante)
                if (lista.isEmpty()) null else lista
            }
        }
    }

    private fun enviarA(
        registro: Registro,
        chave: UUID,
        evento: () -> SseEmitter.SseEventBuilder,
    ) {
        registro[chave]?.forEach { enviar(it, evento) }
    }

    private fun enviar(
        assinante: Assinante,
        evento: () -> SseEmitter.SseEventBuilder,
    ) {
        // Se já há envio preso ou a fila lotou, reconectar força a leitura do estado atual.
        if (!assinante.envioPendente.compareAndSet(false, true)) {
            retirarDeTodos(assinante)
            assinante.encerrar()
            return
        }
        val aceita =
            executor.executar {
                try {
                    if (!assinante.enviar(evento())) {
                        retirarDeTodos(assinante)
                        assinante.encerrar()
                    }
                } finally {
                    assinante.envioPendente.set(false)
                }
            }
        if (!aceita) {
            retirarDeTodos(assinante)
            assinante.encerrar()
        }
    }

    @jakarta.annotation.PreDestroy
    fun desligar() {
        destinosPorAssinante.keys.toList().forEach {
            retirarDeTodos(it)
            it.encerrar()
        }
        executor.close()
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
    private val saude: SaudeOperacional? = null,
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
                        saude?.registrar("eventos-conexao", true)
                        pg.getNotifications(ESPERA_AVISO_MS)?.forEach { n -> n.parameter?.let(::repassar) }
                    }
                }
            } catch (e: Exception) {
                if (!rodando) return
                saude?.registrar("eventos-conexao", false)
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
        val evento = EventoTempoReal.ler(payload)
        if (evento == null) {
            log.warn("Aviso de tempo real ilegível")
            return
        }
        hub.distribuir(evento)
        saude?.registrar("eventos-recepcao", true)
    }

    companion object {
        private const val ESPERA_INICIAL_MS = 1_000L
        private const val ESPERA_MAXIMA_MS = 30_000L
        private const val ESPERA_AVISO_MS = 30_000
    }
}
