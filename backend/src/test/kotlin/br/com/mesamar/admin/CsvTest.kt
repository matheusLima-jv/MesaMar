package br.com.mesamar.admin

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvTest {
    @ParameterizedTest(name = "[{0}] → [{1}]")
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '`',
        value = [
            "Zé da Silva|Zé da Silva",
            "=1+1|'=1+1",
            "+5513|'+5513",
            "-2|'-2",
            "@SUM(A1)|'@SUM(A1)",
            "a;b|\"a;b\"",
            "diz \"oi\"|\"diz \"\"oi\"\"\"",
        ],
    )
    fun `escapa celulas`(
        raw: String,
        expected: String,
    ) {
        assertEquals(expected, Csv.cell(raw))
    }

    @Test
    fun `arquivo tem BOM, separador ponto e virgula e CRLF`() {
        val csv = Csv.write(listOf("A", "B"), listOf(listOf("1", "2")))

        assertEquals("﻿A;B\r\n1;2\r\n", csv)
    }
}
