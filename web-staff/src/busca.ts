// Buscas que vários lugares disparam (tempo real, consulta de reserva, ações, troca de aba).

/**
 * Uma busca por vez. Pedidos que chegam durante uma busca viram UMA busca a mais, logo depois dela. Assim as
 * respostas nunca chegam fora de ordem (uma resposta velha não sobrescreve uma nova) e rajadas de avisos não viram
 * rajadas de requisições.
 */
export function umaPorVez (buscar: () => Promise<void>): () => Promise<void> {
  let emCurso: Promise<void> | null = null
  let deNovo = false
  return async () => {
    if (emCurso !== null) {
      deNovo = true
      return await emCurso
    }
    emCurso = (async () => {
      try {
        do {
          deNovo = false
          await buscar()
        } while (deNovo)
      } finally {
        emCurso = null
      }
    })()
    return await emCurso
  }
}

/** Espera com uma variação de ±20%: vários aparelhos que perderam o sinal juntos não voltam todos no mesmo segundo. */
export function comVariacao (ms: number): number {
  return Math.round(ms * (0.8 + Math.random() * 0.4))
}
