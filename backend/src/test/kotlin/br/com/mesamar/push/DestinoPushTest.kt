package br.com.mesamar.push

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A conferência do `endpoint` com as propriedades de **produção** (as do perfil de teste liberam um servidor falso local). */
class DestinoPushTest {
    private val producao = PushProperties()

    @Test
    fun `aceita so https nos servicos de push conhecidos`() {
        listOf(
            "https://fcm.googleapis.com/fcm/send/abc",
            "https://android.googleapis.com/gcm/send/abc",
            "https://updates.push.services.mozilla.com/wpush/v2/abc",
            "https://web.push.apple.com/QGxyz",
            "https://wns2-par02p.notify.windows.com/w/?token=abc",
            "https://fcm.googleapis.com:443/fcm/send/abc",
        ).forEach { assertNotNull(DestinoPush.validar(producao, it), it) }
    }

    @Test
    fun `recusa a propria maquina, redes internas, nomes parecidos e o que nao e https`() {
        listOf(
            "http://fcm.googleapis.com/fcm/send/x",
            "https://localhost/x",
            "https://127.0.0.1/x",
            "https://[::1]/x",
            "https://169.254.169.254/latest/meta-data",
            "https://10.0.0.5/x",
            "https://192.168.0.10/x",
            "https://mesamar-app:8080/x",
            "https://fcm.googleapis.com.malvado.com/x",
            "https://malvadofcm.googleapis.com.br/x",
            "https://notfcm.googleapis.com/x",
            "https://usuario:senha@fcm.googleapis.com/x",
            "https://fcm.googleapis.com:8443/x",
            "https://fcm.googleapis.com/x#fragmento",
            "ftp://fcm.googleapis.com/x",
            "//fcm.googleapis.com/x",
            "isto não é uma url",
            "",
            "https://fcm.googleapis.com/" + "a".repeat(600),
        ).forEach { assertNull(DestinoPush.validar(producao, it), it) }
    }

    @Test
    fun `um host extra so vale quando http de teste esta ligado, e so ele`() {
        val soHost = PushProperties(hostsExtras = listOf("localhost"))
        val teste = PushProperties(hostsExtras = listOf("localhost"), permitirHttp = true)

        assertNull(DestinoPush.validar(soHost, "http://localhost:8123/push/x"), "sem permitir-http, nem o host extra vale")
        assertEquals("localhost", DestinoPush.validar(teste, "http://localhost:8123/push/x")?.host)
        assertNull(DestinoPush.validar(teste, "http://127.0.0.1:8123/push/x"), "só o host liberado, não qualquer um")
        assertNull(DestinoPush.validar(teste, "http://outro.exemplo.com/x"))
        assertNotNull(DestinoPush.validar(teste, "https://fcm.googleapis.com/fcm/send/x"), "os serviços conhecidos continuam valendo")
    }
}
