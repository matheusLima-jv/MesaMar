/** UUID v4 com crypto.getRandomValues (o crypto.randomUUID não existe fora de HTTPS, ex.: http://IP-DO-PC). */
export function novoId (): string {
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const hex = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 12300 → "R$ 123,00" (o mesmo formato do servidor: Money.format). */
export function reais (centavos: number): string {
  const inteiro = String(Math.floor(centavos / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, '.')
  return `R$ ${inteiro},${String(centavos % 100).padStart(2, '0')}`
}

/** "100", "100,50" ou "R$ 1.250,00" → centavos. `null` se não for um valor em reais. */
export function lerReais (texto: string): number | null {
  const limpo = texto.replace(/R\$/g, '').replace(/\s/g, '')
  const m = /^(\d{1,3}(?:\.\d{3})*|\d+)(?:,(\d{1,2}))?$/.exec(limpo)
  if (m === null) return null
  return Number(m[1].replace(/\./g, '')) * 100 + Number((m[2] ?? '').padEnd(2, '0'))
}
