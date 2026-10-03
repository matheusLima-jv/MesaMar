package br.com.mesamar.common

import org.postgresql.util.PSQLException

/** SQLSTATE e nome da restrição: mensagens e retries nunca dependem do texto do driver. */
object Restricoes {
    fun nome(erro: Throwable): String? =
        generateSequence(erro) { it.cause }
            .filterIsInstance<PSQLException>()
            .firstOrNull()
            ?.serverErrorMessage
            ?.constraint

    fun unica(
        erro: Throwable,
        nome: String,
    ): Boolean =
        generateSequence(erro) { it.cause }.filterIsInstance<PSQLException>().any {
            it.sqlState == "23505" && it.serverErrorMessage?.constraint == nome
        }

    fun mensagem(erro: Throwable): String =
        when (nome(erro)) {
            "uk_usuario_email" -> "Já existe uma conta com esse e-mail. Tente entrar ou trocar a senha."
            "uk_categoria_nome" -> "Já existe uma categoria com esse nome."
            "uk_barraca_slug" -> "Esse código de barraca já está em uso. Escolha outro."
            "uk_comanda_token" -> "Esse QR já está em uso. Gere outro código."
            else -> "Os dados mudaram durante a operação. Atualize a tela e tente de novo."
        }
}
