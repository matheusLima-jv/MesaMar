package br.com.mesamar.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChavePixTest {
    @ParameterizedTest
    @CsvSource(
        "CPF, 529.982.247-25, 52998224725",
        "CPF, 52998224725, 52998224725",
        "CNPJ, 11.222.333/0001-81, 11222333000181",
        "CNPJ, 12.ABC.345/01DE-35, 12ABC34501DE35",
        "CNPJ, 12abc34501de35, 12ABC34501DE35",
        "TELEFONE, (13) 99111-2233, +5513991112233",
        "EMAIL, Ze.Praia@Exemplo.COM, ze.praia@exemplo.com",
        "ALEATORIA, 7D9F0335-8DCC-4054-9BF9-0DBD61D36906, 7d9f0335-8dcc-4054-9bf9-0dbd61d36906",
    )
    fun `chaves validas sao normalizadas`(
        tipo: TipoChavePix,
        entrada: String,
        esperado: String,
    ) {
        assertEquals(esperado, ChavePix.normalize(tipo, entrada))
    }

    @ParameterizedTest
    @CsvSource(
        "CPF, 529.982.247-24", // dígito errado
        "CPF, 111.111.111-11", // todos iguais
        "CPF, 1234",
        "CNPJ, 11.222.333/0001-82",
        "CNPJ, 00000000000000",
        "CNPJ, 12.ABC.345/01DE-36",
        "TELEFONE, (13) 3355-1234", // fixo não vale como chave de celular
        "TELEFONE, 99111-2233", // sem DDD
        "EMAIL, ze@",
        "EMAIL, ze praia@exemplo.com",
        "ALEATORIA, 7d9f0335-8dcc-4054-9bf9",
        "CPF, ''",
    )
    fun `chaves invalidas sao recusadas`(
        tipo: TipoChavePix,
        entrada: String,
    ) {
        assertNull(ChavePix.normalize(tipo, entrada))
    }

    @Test
    fun `e-mail acima de 77 caracteres e recusado`() {
        val email = "a".repeat(70) + "@ex.com.br"
        assertNull(ChavePix.normalize(TipoChavePix.EMAIL, email))
    }

    @Test
    fun `exibicao com mascara`() {
        assertEquals("529.982.247-25", ChavePix.display(TipoChavePix.CPF, "52998224725"))
        assertEquals("11.222.333/0001-81", ChavePix.display(TipoChavePix.CNPJ, "11222333000181"))
        assertEquals("(13) 99111-2233", ChavePix.display(TipoChavePix.TELEFONE, "+5513991112233"))
    }

    @Test
    fun `texto do Pix sem acento e em maiusculas`() {
        assertEquals("JOSE DA PRAIA", "José da Praia".toPixText())
        assertEquals("GUARUJA", "  Guarujá ".toPixText())
        assertEquals("BAR DO ZE", "Bar do Zé!".toPixText())
    }
}
