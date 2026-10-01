package br.com.mesamar.push

import br.com.mesamar.config.SiteProperties
import br.com.mesamar.equipe.AtendenteRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** Um aparelho que ativou os avisos. O `endpoint` é a URL secreta do serviço de push; `p256dh` e `auth` cifram a mensagem. */
@Entity
@Table(name = "push_subscription")
class PushSubscription(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "barraca_id", nullable = false)
    var barracaId: UUID,
    @Column(name = "usuario_id")
    var usuarioId: UUID?,
    @Column(name = "atendente_id")
    var atendenteId: UUID?,
    @Column(nullable = false, length = 600)
    val endpoint: String,
    @Column(nullable = false, length = 100)
    var p256dh: String,
    @Column(nullable = false, length = 40)
    var auth: String,
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
    @Column(name = "ultimo_uso_em")
    var ultimoUsoEm: Instant? = null,
)

interface PushSubscriptionRepository : JpaRepository<PushSubscription, UUID> {
    fun findByEndpoint(endpoint: String): PushSubscription?

    fun findAllByBarracaId(barracaId: UUID): List<PushSubscription>

    fun countByBarracaId(barracaId: UUID): Long

    /** Só apaga se a inscrição for da barraca de quem pediu. */
    @Modifying
    @Transactional
    @Query("delete from PushSubscription s where s.endpoint = :endpoint and s.barracaId = :barracaId")
    fun removerDaBarraca(
        @Param("endpoint") endpoint: String,
        @Param("barracaId") barracaId: UUID,
    ): Int

    @Modifying
    @Transactional
    @Query("delete from PushSubscription s where s.id = :id")
    fun removerPorId(
        @Param("id") id: UUID,
    ): Int

    @Modifying
    @Transactional
    @Query("update PushSubscription s set s.ultimoUsoEm = :agora where s.id = :id")
    fun marcarUso(
        @Param("id") id: UUID,
        @Param("agora") agora: Instant,
    ): Int
}

/** O texto de um aviso. Vai cifrado ao aparelho, que o mostra pelo service worker do app. */
data class MensagemPush(
    val titulo: String,
    val corpo: String,
    /** Para onde o toque na notificação leva. */
    val url: String,
    /** Avisos com a mesma etiqueta se substituem na tela (um pedido, um aviso). */
    val tag: String,
    /** `NOVO_PEDIDO` ou `PEDIDO_ATRASADO`. */
    val tipo: String,
    /** Quanto tempo o serviço de push guarda o aviso se o aparelho estiver sem sinal. Depois disso ele perde o sentido. */
    val validadeSegundos: Int,
) {
    fun json(): String =
        """{"titulo":${aspas(titulo)},"corpo":${aspas(corpo)},"url":${aspas(url)},"tag":${aspas(tag)},"tipo":${aspas(tipo)}}"""

    private fun aspas(texto: String): String {
        val sb = StringBuilder("\"")
        for (c in texto) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c.code < 0x20 -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}

sealed interface ResultadoInscricao {
    data object Ok : ResultadoInscricao

    /** Sem as chaves VAPID no servidor. */
    data object Desligado : ResultadoInscricao

    data class Invalido(
        val mensagem: String,
    ) : ResultadoInscricao

    /** O endpoint já é de outra barraca, com outras chaves: não se toma a inscrição de ninguém. */
    data object Conflito : ResultadoInscricao
}

/**
 * Web Push para o dono e os atendentes: guarda os aparelhos inscritos e envia os avisos (cifrados, RFC 8291, e
 * assinados com o VAPID). O envio não trava quem pediu: roda em thread virtual, e inscrição que o serviço de push
 * diz que não existe mais (404 ou 410) é removida sozinha.
 */
@Service
class PushService(
    private val props: PushProperties,
    site: SiteProperties,
    private val subs: PushSubscriptionRepository,
    private val atendentes: AtendenteRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER) // um redirecionamento poderia levar o servidor a um endereço interno
            .build()

    private val vapid: Vapid? =
        if (!props.habilitado) {
            null
        } else {
            runCatching { Vapid(props.publicKey, props.privateKey, props.subject.ifBlank { site.publicBaseUrl }) }
                .onFailure { log.error("Chaves VAPID inválidas: o Web Push fica desligado ({})", it.message) }
                .getOrNull()
        }

    val habilitado: Boolean get() = vapid != null

    val chavePublica: String? get() = if (habilitado) props.publicKey else null

    @Transactional
    fun inscrever(
        barracaId: UUID,
        usuarioId: UUID?,
        atendenteId: UUID?,
        endpoint: String?,
        p256dh: String?,
        auth: String?,
    ): ResultadoInscricao {
        require((usuarioId == null) != (atendenteId == null)) { "inscrição sem autor ou com dois autores" }
        if (!habilitado) return ResultadoInscricao.Desligado
        if (endpoint.isNullOrBlank() || DestinoPush.validar(props, endpoint) == null) {
            return ResultadoInscricao.Invalido("Esse celular não aceita avisos pelo serviço de notificação dele.")
        }
        val chaveOk = runCatching { WebPushCripto.publicaDeBytes(Base64Url.decodificar(p256dh.orEmpty())) }.isSuccess
        val autenticacaoOk = runCatching { Base64Url.decodificar(auth.orEmpty()).size == 16 }.getOrDefault(false)
        if (!chaveOk || !autenticacaoOk) return ResultadoInscricao.Invalido("As chaves do aparelho são inválidas. Ative os avisos de novo.")

        val existente = subs.findByEndpoint(endpoint)
        val agora = Instant.now(clock)
        if (existente == null) {
            if (subs.countByBarracaId(barracaId) >= MAX_POR_BARRACA) {
                return ResultadoInscricao.Invalido(
                    "Esta barraca já tem $MAX_POR_BARRACA aparelhos com avisos. Desative os que não usa mais.",
                )
            }
            subs.save(
                PushSubscription(
                    barracaId = barracaId,
                    usuarioId = usuarioId,
                    atendenteId = atendenteId,
                    endpoint = endpoint,
                    p256dh = p256dh!!,
                    auth = auth!!,
                    criadoEm = agora,
                ),
            )
            return ResultadoInscricao.Ok
        }
        // O mesmo aparelho pode entrar em outra barraca (outro login): as chaves são as mesmas. Chaves diferentes
        // num endpoint que já existe é alguém tentando tomar a inscrição de outro.
        if (existente.barracaId != barracaId && (existente.p256dh != p256dh || existente.auth != auth)) return ResultadoInscricao.Conflito
        existente.barracaId = barracaId
        existente.usuarioId = usuarioId
        existente.atendenteId = atendenteId
        existente.p256dh = p256dh!!
        existente.auth = auth!!
        subs.save(existente)
        return ResultadoInscricao.Ok
    }

    /** Desativa os avisos deste aparelho. Só mexe em inscrição da própria barraca. */
    @Transactional
    fun cancelar(
        barracaId: UUID,
        endpoint: String?,
    ): Boolean = !endpoint.isNullOrBlank() && subs.removerDaBarraca(endpoint, barracaId) > 0

    /**
     * Manda um aviso de teste só para o aparelho que pediu (o dono confere se está tudo certo no celular dele).
     * `false` se esse aparelho não está inscrito **nesta barraca**.
     */
    fun enviarTeste(
        barracaId: UUID,
        endpoint: String?,
    ): Boolean {
        val v = vapid ?: return false
        val inscricao = endpoint?.let { subs.findByEndpoint(it) }?.takeIf { it.barracaId == barracaId } ?: return false
        executor.execute {
            entregar(
                v,
                inscricao,
                MensagemPush(
                    titulo = "MesaMar: avisos funcionando",
                    corpo = "É assim que você vai receber os pedidos novos.",
                    url = "/staff/#/fila",
                    tag = "teste",
                    tipo = "TESTE",
                    validadeSegundos = 60,
                ),
            )
        }
        return true
    }

    /** Avisa todos os aparelhos inscritos da barraca. Sai na hora e não espera os serviços de push responderem. */
    fun enviarParaBarraca(
        barracaId: UUID,
        mensagem: MensagemPush,
    ) {
        val v = vapid ?: return
        val lista = subs.findAllByBarracaId(barracaId)
        if (lista.isEmpty()) return
        val atendentesAtivos =
            atendentes
                .findAllById(lista.mapNotNull { it.atendenteId }.distinct())
                .filter { it.ativo }
                .map { it.id }
                .toSet()
        lista.forEach { s ->
            // Atendente desativado pelo dono deixa de receber os avisos da barraca
            if (s.atendenteId != null && s.atendenteId !in atendentesAtivos) {
                subs.removerPorId(s.id)
            } else {
                executor.execute { entregar(v, s, mensagem) }
            }
        }
    }

    private fun entregar(
        v: Vapid,
        s: PushSubscription,
        mensagem: MensagemPush,
    ) {
        try {
            val destino = URI(s.endpoint)
            val corpo =
                WebPushCripto.cifrar(
                    mensagem.json().toByteArray(Charsets.UTF_8),
                    Base64Url.decodificar(s.p256dh),
                    Base64Url.decodificar(s.auth),
                )
            val pedido =
                HttpRequest
                    .newBuilder(destino)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", v.autorizacao(destino))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", mensagem.validadeSegundos.toString())
                    .header("Urgency", "high")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(corpo))
                    .build()
            val resposta = http.send(pedido, HttpResponse.BodyHandlers.discarding())
            when (val codigo = resposta.statusCode()) {
                in 200..299 -> subs.marcarUso(s.id, Instant.now(clock))
                // O aparelho se desinscreveu, desinstalou o app ou a inscrição venceu: não existe mais
                404, 410 -> {
                    subs.removerPorId(s.id)
                    log.info("Inscrição de push removida (o serviço respondeu {}): {}", codigo, s.id)
                }
                else -> log.warn("Serviço de push respondeu {} para a inscrição {}", codigo, s.id)
            }
        } catch (e: Exception) {
            log.warn("Não deu para entregar o aviso à inscrição {}: {}", s.id, e.message)
        }
    }

    companion object {
        const val MAX_POR_BARRACA = 40
    }
}

/**
 * Para onde o servidor aceita mandar um aviso. O `endpoint` vem do navegador de quem está logado, mas quem chama é o
 * **servidor**: sem esta conferência, alguém logado poderia fazê-lo acessar endereços internos (SSRF). Só `https`
 * e só os serviços de push conhecidos (Google/FCM, Mozilla, Apple e Windows).
 */
object DestinoPush {
    private val SERVICOS =
        listOf("fcm.googleapis.com", "android.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com")

    fun validar(
        props: PushProperties,
        endpoint: String,
    ): URI? {
        if (endpoint.length > 600) return null
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.userInfo != null || uri.fragment != null) return null
        // Só nos testes: um servidor de push falso na própria máquina
        if (props.permitirHttp && props.hostsExtras.any { it.equals(host, ignoreCase = true) }) {
            return uri.takeIf { it.scheme == "http" || it.scheme == "https" }
        }
        if (uri.scheme != "https" || (uri.port != -1 && uri.port != 443)) return null
        return uri.takeIf { SERVICOS.any { s -> host == s || host.endsWith(".$s") } }
    }
}
