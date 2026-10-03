package br.com.mesamar.common

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Estado persistente permite perceber tarefas esquecidas após reiniciar a JVM. Sem dados de clientes. */
@Service
class SaudeOperacional(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun registrar(
        tarefa: String,
        sucesso: Boolean,
    ) {
        require(tarefa.matches(Regex("[a-z-]{1,40}")))
        val coluna = if (sucesso) "ultimo_sucesso" else "ultima_falha"
        val contador = if (sucesso) "sucessos" else "falhas"
        jdbc.update(
            """INSERT INTO saude_operacional (tarefa,$coluna,$contador) VALUES (?,now(),1)
               ON CONFLICT (tarefa) DO UPDATE SET $coluna=now(),$contador=saude_operacional.$contador+1""",
            tarefa,
        )
    }

    fun consultar(): List<Map<String, Any?>> = jdbc.queryForList("SELECT * FROM saude_operacional ORDER BY tarefa")
}

@RestController
class DiagnosticoOperacional(
    private val saude: SaudeOperacional,
) {
    @GetMapping("/admin/operacao")
    fun estado(): List<Map<String, Any?>> = saude.consultar()
}
