package br.com.mesamar.common

/**
 * BR Code do Pix estático ("copia e cola" e o texto do QR), no padrão EMV do Banco Central
 * (Manual de Padrões para Iniciação do Pix). Sem PSP e sem custódia: o código leva a chave, o nome e a cidade
 * do recebedor e, se houver, o valor.
 *
 * Cada campo é `id (2 dígitos) + tamanho (2 dígitos) + valor`. O último, o `63`, é o CRC16 de todo o texto anterior.
 * O texto precisa ser ASCII (nome e cidade já saem de [toPixText]); tamanho é contado em caracteres.
 */
object BrCodePix {
    /** Campo 01: `11` pode ser pago várias vezes (QR fixo); `12` só uma. */
    enum class Iniciacao(
        val codigo: String,
    ) {
        REUTILIZAVEL("11"),
        UNICO("12"),
    }

    const val NOME_MAX = 25
    const val CIDADE_MAX = 15
    const val TXID_MAX = 25
    const val CHAVE_MAX = 77

    /** `***` é o txid "não informado" que o manual prevê para o QR estático. */
    const val TXID_NAO_INFORMADO = "***"

    private const val GUI = "br.gov.bcb.pix"
    private val txidRegex = Regex("^[A-Za-z0-9]{1,$TXID_MAX}$")

    /**
     * Monta o código. `valorCentavos = null` deixa o cliente digitar o valor no banco. Quem chama passa o texto já
     * normalizado (sem acento, só ASCII); aqui só se confere o que o padrão exige, e o que estiver fora lança
     * [IllegalArgumentException] (é erro de programação ou dado salvo errado, nunca entrada do cliente).
     */
    fun gerar(
        chave: String,
        nomeRecebedor: String,
        cidade: String,
        valorCentavos: Long? = null,
        txid: String? = null,
        iniciacao: Iniciacao? = Iniciacao.REUTILIZAVEL,
    ): String {
        require(chave.isNotEmpty() && chave.length <= CHAVE_MAX) { "Chave Pix vazia ou maior que $CHAVE_MAX caracteres." }
        require(nomeRecebedor.isNotEmpty() && nomeRecebedor.length <= NOME_MAX) { "Nome do recebedor vazio ou maior que $NOME_MAX." }
        require(cidade.isNotEmpty() && cidade.length <= CIDADE_MAX) { "Cidade vazia ou maior que $CIDADE_MAX." }
        require(valorCentavos == null || valorCentavos in 1..VALOR_MAX_CENTAVOS) { "Valor fora do que o Pix aceita: $valorCentavos." }
        val idTransacao = txid ?: TXID_NAO_INFORMADO
        require(
            idTransacao == TXID_NAO_INFORMADO || txidRegex.matches(idTransacao),
        ) { "txid inválido: só letras e números, até $TXID_MAX." }

        val semCrc =
            buildString {
                append(campo("00", "01"))
                iniciacao?.let { append(campo("01", it.codigo)) }
                append(campo("26", campo("00", GUI) + campo("01", chave)))
                append(campo("52", "0000"))
                append(campo("53", "986"))
                valorCentavos?.let { append(campo("54", valor(it))) }
                append(campo("58", "BR"))
                append(campo("59", nomeRecebedor))
                append(campo("60", cidade))
                append(campo("62", campo("05", idTransacao)))
                append("6304")
            }
        return semCrc + crc16(semCrc)
    }

    /**
     * CRC16-CCITT-FALSE (polinômio 0x1021, valor inicial 0xFFFF, sem inversão), em 4 letras hexadecimais maiúsculas.
     * É calculado sobre todo o código, incluindo o `6304` que anuncia o próprio CRC.
     */
    fun crc16(texto: String): String {
        var crc = 0xFFFF
        for (byte in texto.toByteArray(Charsets.US_ASCII)) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return "%04X".format(crc)
    }

    /** O código está inteiro e o CRC bate? (Usado nos testes para conferir o que o servidor gerou.) */
    fun crcConfere(codigo: String): Boolean =
        codigo.length > 8 && codigo.takeLast(8).startsWith("6304") && crc16(codigo.dropLast(4)) == codigo.takeLast(4)

    /** 8750 → "87.50": ponto decimal e sempre duas casas, como o campo 54 pede. */
    fun valor(centavos: Long): String = "${centavos / 100}.${(centavos % 100).toString().padStart(2, '0')}"

    private fun campo(
        id: String,
        valor: String,
    ): String {
        require(valor.length in 1..99) { "Campo $id com tamanho inválido: ${valor.length}." }
        return id + valor.length.toString().padStart(2, '0') + valor
    }

    /** O campo 54 aceita até 13 caracteres ("9999999999.99"). */
    private const val VALOR_MAX_CENTAVOS = 999_999_999_999L
}
