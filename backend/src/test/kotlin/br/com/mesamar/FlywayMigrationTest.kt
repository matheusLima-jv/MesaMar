package br.com.mesamar

import br.com.mesamar.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@IntegrationTest
class FlywayMigrationTest(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `flyway aplica a migracao inicial no PostgreSQL`() {
        val applied =
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String::class.java,
            )

        assertTrue("1" in applied, "V1 deveria estar aplicada, encontradas: $applied")
    }

    @Test
    fun `migracao inicial cria o marco zero do schema`() {
        val baseline =
            jdbcTemplate.queryForObject(
                "SELECT value FROM app_metadata WHERE key = 'schema_baseline'",
                String::class.java,
            )

        assertEquals("fase-0", baseline)
    }

    @Test
    fun `banco de teste e PostgreSQL`() {
        val version = jdbcTemplate.queryForObject("SELECT version()", String::class.java)

        assertTrue(version!!.startsWith("PostgreSQL 16"), "Esperado PostgreSQL 16, veio: $version")
    }
}
