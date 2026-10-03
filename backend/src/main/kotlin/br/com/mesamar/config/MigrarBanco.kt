package br.com.mesamar.config

import org.flywaydb.core.Flyway

/** Processo separado, sem servidor HTTP nem credenciais administrativas na aplicação. */
fun main() {
    fun obrigatoria(nome: String): String = requireNotNull(System.getenv(nome)?.takeIf { it.isNotBlank() }) { "Defina $nome" }
    Flyway
        .configure()
        .dataSource(obrigatoria("DB_URL"), obrigatoria("DB_MIGRATION_USER"), obrigatoria("DB_MIGRATION_PASSWORD"))
        .locations("classpath:db/migration")
        .load()
        .migrate()
}
