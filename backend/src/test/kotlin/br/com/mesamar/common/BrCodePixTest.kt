package br.com.mesamar.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O gerador de BR Code contra a referência do Banco Central e contra as regras do padrão EMV.
 *
 * Fonte da referência: Manual de Padrões para Iniciação do Pix (Banco Central do Brasil), seção do BR Code estático,
 * exemplo com a chave aleatória `123e4567-e12b-12d1-a456-426655440000`, recebedor "Fulano de Tal", cidade "BRASILIA"
 * e txid `***`, cujo CRC16 é `1D3D`. O mesmo payload aparece nos exemplos de implementação do próprio Banco Central.
 */
class BrCodePixTest {
    private val chaveManual = "123e4567-e12b-12d1-a456-426655440000"
    private val payloadDoManual =
        "00020126580014br.gov.bcb.pix0136$chaveManual" +
            "5204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***63041D3D"

    /** Lê o código de volta em campos (id → valor), no nível de cima. */
    private fun campos(codigo: String): List<Pair<String, String>> {
        val lista = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < codigo.length) {
            val id = codigo.substring(i, i + 2)
            val tamanho = codigo.substring(i + 2, i + 4).toInt()
            lista += id to codigo.substring(i + 4, i + 4 + tamanho)
            i += 4 + tamanho
        }
        return lista
    }

    @Test
    fun `reproduz o payload de exemplo do manual do Banco Central, com o CRC 1D3D`() {
        val gerado =
            BrCodePix.gerar(
                chave = chaveManual,
                nomeRecebedor = "Fulano de Tal",
                cidade = "BRASILIA",
                valorCentavos = null,
                txid = null,
                iniciacao = null,
            )

        assertEquals(payloadDoManual, gerado)
        assertEquals("1D3D", BrCodePix.crc16(payloadDoManual.dropLast(4)))
    }

    @Test
    fun `o CRC16 CCITT bate com o valor de referencia da string 123456789`() {
        // Vetor clássico do CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF): "123456789" → 29B1
        assertEquals("29B1", BrCodePix.crc16("123456789"))
    }

    @Test
    fun `com valor e txid o codigo tem todos os campos na ordem do padrao e o CRC confere`() {
        val codigo =
            BrCodePix.gerar(
                chave = "12345678909",
                nomeRecebedor = "JOSE DA SILVA",
                cidade = "GUARUJA",
                valorCentavos = 8750,
                txid = "MM20261001C0012",
            )

        val c = campos(codigo)
        assertEquals(listOf("00", "01", "26", "52", "53", "54", "58", "59", "60", "62", "63"), c.map { it.first })
        assertEquals("01", c[0].second)
        assertEquals("11", c[1].second, "QR estático reutilizável")
        assertEquals("0014br.gov.bcb.pix011112345678909", c[2].second)
        assertEquals("0000", c[3].second)
        assertEquals("986", c[4].second, "moeda: real")
        assertEquals("87.50", c[5].second)
        assertEquals("BR", c[6].second)
        assertEquals("JOSE DA SILVA", c[7].second)
        assertEquals("GUARUJA", c[8].second)
        assertEquals("0515MM20261001C0012", c[9].second)
        assertEquals(4, c[10].second.length)
        assertTrue(BrCodePix.crcConfere(codigo), codigo)
    }

    @Test
    fun `valor em centavos vira reais com ponto e duas casas`() {
        assertEquals("0.01", BrCodePix.valor(1))
        assertEquals("0.50", BrCodePix.valor(50))
        assertEquals("1.00", BrCodePix.valor(100))
        assertEquals("87.50", BrCodePix.valor(8750))
        assertEquals("1234.05", BrCodePix.valor(123405))
        assertEquals("9999999999.99", BrCodePix.valor(999_999_999_999))
    }

    @Test
    fun `um centavo e o valor maximo cabem no codigo e mudar qualquer letra quebra o CRC`() {
        val centavo = BrCodePix.gerar("a@b.com", "ANA", "SANTOS", 1)
        assertTrue(centavo.contains("54040.01"), centavo)
        assertTrue(BrCodePix.crcConfere(centavo))
        assertTrue(BrCodePix.crcConfere(BrCodePix.gerar("a@b.com", "ANA", "SANTOS", 999_999_999_999)))

        val adulterado = centavo.replace("54040.01", "540499.9")
        assertFalse(BrCodePix.crcConfere(adulterado))
    }

    @Test
    fun `cada tipo de chave do cadastro cabe no campo`() {
        val chaves =
            listOf(
                "12345678909", // CPF
                "11222333000181", // CNPJ
                "+5513999998888", // celular
                "voce@exemplo.com", // e-mail
                "7d9f0335-8dcc-4054-9bf9-0dbd61d36906", // aleatória
                "a".repeat(BrCodePix.CHAVE_MAX), // o maior e-mail que o cadastro aceita
            )
        chaves.forEach { chave ->
            val codigo = BrCodePix.gerar(chave, "NOME", "CIDADE", 100)
            assertTrue(BrCodePix.crcConfere(codigo), chave)
            assertEquals(chave, campos(campos(codigo).first { it.first == "26" }.second).first { it.first == "01" }.second)
        }
    }

    @Test
    fun `recusa o que o padrao nao aceita`() {
        fun gerar(
            chave: String = "12345678909",
            nome: String = "NOME",
            cidade: String = "CIDADE",
            valor: Long? = 100,
            txid: String? = null,
        ) = BrCodePix.gerar(chave, nome, cidade, valor, txid)

        assertFailsWith<IllegalArgumentException> { gerar(chave = "") }
        assertFailsWith<IllegalArgumentException> { gerar(chave = "a".repeat(BrCodePix.CHAVE_MAX + 1)) }
        assertFailsWith<IllegalArgumentException> { gerar(nome = "") }
        assertFailsWith<IllegalArgumentException> { gerar(nome = "N".repeat(BrCodePix.NOME_MAX + 1)) }
        assertFailsWith<IllegalArgumentException> { gerar(cidade = "") }
        assertFailsWith<IllegalArgumentException> { gerar(cidade = "C".repeat(BrCodePix.CIDADE_MAX + 1)) }
        assertFailsWith<IllegalArgumentException> { gerar(valor = 0) }
        assertFailsWith<IllegalArgumentException> { gerar(valor = -5) }
        assertFailsWith<IllegalArgumentException> { gerar(valor = 1_000_000_000_000) }
        assertFailsWith<IllegalArgumentException> { gerar(txid = "com espaco") }
        assertFailsWith<IllegalArgumentException> { gerar(txid = "T".repeat(BrCodePix.TXID_MAX + 1)) }
        assertFailsWith<IllegalArgumentException> { gerar(txid = "") }
    }

    @Test
    fun `nome e cidade guardados pelo cadastro entram do jeito que o banco mostra`() {
        val codigo =
            BrCodePix.gerar(
                chave = "12345678909",
                nomeRecebedor = "José da Silva".toPixText(),
                cidade = "Guarujá".toPixText(),
                valorCentavos = 2500,
            )

        assertTrue(codigo.contains("5913JOSE DA SILVA"), codigo)
        assertTrue(codigo.contains("6007GUARUJA"), codigo)
        assertTrue(BrCodePix.crcConfere(codigo))
    }
}
