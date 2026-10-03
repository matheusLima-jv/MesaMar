package br.com.mesamar.common

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Limita tanto as tarefas em execução quanto a fila; o dono fecha o executor no desligamento. */
class ExecutorLimitado(
    nome: String,
    concorrencia: Int = 8,
    fila: Int = 128,
) : AutoCloseable {
    private val executor =
        ThreadPoolExecutor(
            concorrencia,
            concorrencia,
            0,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(fila),
            Thread.ofVirtual().name("$nome-", 0).factory(),
            ThreadPoolExecutor.AbortPolicy(),
        )

    fun executar(tarefa: () -> Unit): Boolean =
        try {
            executor.execute(tarefa)
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }
}
