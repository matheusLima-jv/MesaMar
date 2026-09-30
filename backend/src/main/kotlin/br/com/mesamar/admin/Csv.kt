package br.com.mesamar.admin

/**
 * CSV no formato que o Excel em português abre direto: separador ";", BOM UTF-8 e CRLF.
 * Também neutraliza "fórmulas" (células que começam com = + - @), para um nome
 * malicioso digitado no formulário não virar comando ao abrir a planilha.
 */
object Csv {
    private const val BOM = "﻿"
    private val formulaStarts = setOf('=', '+', '-', '@', '\t', '\r')

    fun write(
        header: List<String>,
        rows: List<List<String>>,
    ): String {
        val lines = (listOf(header) + rows).joinToString("\r\n") { row -> row.joinToString(";") { cell(it) } }
        return BOM + lines + "\r\n"
    }

    internal fun cell(raw: String): String {
        val safe = if (raw.firstOrNull() in formulaStarts) "'$raw" else raw
        val needsQuotes = safe.any { it == ';' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
