package br.com.mesamar.push

/**
 * Gera um par de chaves VAPID para o Web Push. Rode UMA vez, no seu computador:
 *
 *     ./gradlew --console=plain -q :backend:gerarVapid
 *
 * Nada é gravado: copie as duas linhas para o `.env` do servidor. **A privada é segredo** (não vai para o
 * repositório, nem para conversa ou e-mail). Trocar as chaves depois obriga todos a ativarem os avisos de novo.
 */
fun main() {
    val par = WebPushCripto.gerarPar()
    println("MESAMAR_VAPID_PUBLIC_KEY=${Base64Url.codificar(WebPushCripto.publicaEmBytes(par.public))}")
    println("MESAMAR_VAPID_PRIVATE_KEY=${Base64Url.codificar(WebPushCripto.privadaEmBytes(par.private))}")
}
