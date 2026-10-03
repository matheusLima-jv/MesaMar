// O que a página guarda no navegador, por comanda (token): carrinho, pedido em envio, avisos lidos. A comanda vence no
// servidor, mas isso não apaga estas cópias: sem uma regra aqui, o celular acumula dados de comandas antigas (com nome
// e pedidos) para sempre. Cada comanda aberta marca `visto.<token>`; as que ficam [VALIDADE_DIAS] dias sem uso saem.

const VALIDADE_DIAS = 7
const PREFIXO = 'mesamar.'
const TIPOS = ['carrinho', 'pendente', 'avisados']

function chaveVisto (token: string): string {
  return `${PREFIXO}visto.${token}`
}

/** Marca que a comanda foi aberta agora (começa de novo o prazo de 7 dias). */
export function marcarVisto (token: string): void {
  try { localStorage.setItem(chaveVisto(token), String(Date.now())) } catch { /* sem armazenamento: nada a marcar */ }
}

/** Apaga tudo o que a página guardou desta comanda. */
export function esquecerComanda (token: string): void {
  try {
    for (const tipo of TIPOS) localStorage.removeItem(`${PREFIXO}${tipo}.${token}`)
    localStorage.removeItem(chaveVisto(token))
  } catch { /* sem armazenamento: nada a apagar */ }
}

/**
 * Ao abrir uma comanda: apaga os dados das OUTRAS comandas que ficaram sem uso por mais de 7 dias. O que ainda não tem
 * data (de antes desta regra) ganha a de hoje e vence depois, para nunca apagar um pedido pendente por engano.
 */
export function limparComandasAntigas (tokenAtual: string): void {
  try {
    const limite = Date.now() - VALIDADE_DIAS * 86_400_000
    const tokens = new Set<string>()
    for (const k of Object.keys(localStorage)) {
      for (const tipo of [...TIPOS, 'visto']) {
        const inicio = `${PREFIXO}${tipo}.`
        if (k.startsWith(inicio)) tokens.add(k.slice(inicio.length))
      }
    }
    tokens.delete(tokenAtual)
    for (const token of tokens) {
      const visto = Number(localStorage.getItem(chaveVisto(token)))
      if (!Number.isFinite(visto) || visto <= 0) marcarVisto(token)
      else if (visto < limite) esquecerComanda(token)
    }
  } catch { /* sem armazenamento: nada a limpar */ }
}
