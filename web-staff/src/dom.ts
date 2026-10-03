export { h, mount, lembrar } from '../../web-shared/src/dom'

const telas = new WeakMap<HTMLElement, number>()

/**
 * Marca uma tela nova em `root` e devolve a pergunta "esta tela ainda está aberta?". Uma resposta lenta (de Clientes,
 * do Cardápio…) que chega depois de a pessoa trocar de aba é descartada em vez de desenhar por cima da aba nova.
 */
export function abrirTela (root: HTMLElement): () => boolean {
  const geracao = (telas.get(root) ?? 0) + 1
  telas.set(root, geracao)
  return () => telas.get(root) === geracao && root.isConnected
}

const sobreposicoes = new Set<() => void>()

/**
 * Diálogos e telas cheias são anexados ao `body`, fora da raiz do app. Quem abre registra aqui como tirá-los da tela
 * (sem rodar o "ao fechar" de quem abriu): ao trocar de aba, sair ou a sessão acabar, todos somem juntos.
 * Devolve a função que desfaz o registro (para chamar quando o diálogo fecha normalmente).
 */
export function registrarSobreposicao (remover: () => void): () => void {
  sobreposicoes.add(remover)
  return () => { sobreposicoes.delete(remover) }
}

export function fecharSobreposicoes (): void {
  const todas = Array.from(sobreposicoes)
  sobreposicoes.clear()
  todas.forEach((remover) => { remover() })
}
