import qrcode from 'qrcode-generator'

const SVG = 'http://www.w3.org/2000/svg'

/**
 * QR Code como SVG montado pelo DOM (nada de innerHTML, e a CSP não precisa liberar nada).
 * Correção de erro "M" (15%): aguenta reflexo e tela um pouco suja sem deixar o código denso demais.
 * A margem de 4 módulos é a "zona de silêncio" que as câmeras precisam para achar o código.
 */
export function qrSvg (texto: string, rotulo: string): SVGSVGElement {
  const qr = qrcode(0, 'M')
  qr.addData(texto)
  qr.make()

  const n = qr.getModuleCount()
  const margem = 4
  const lado = n + margem * 2
  let d = ''
  for (let linha = 0; linha < n; linha++) {
    for (let coluna = 0; coluna < n; coluna++) {
      if (qr.isDark(linha, coluna)) d += `M${coluna + margem} ${linha + margem}h1v1h-1z`
    }
  }

  const svg = document.createElementNS(SVG, 'svg')
  svg.setAttribute('viewBox', `0 0 ${lado} ${lado}`)
  svg.setAttribute('role', 'img')
  svg.setAttribute('aria-label', rotulo)
  svg.setAttribute('shape-rendering', 'crispEdges')
  svg.setAttribute('class', 'qr')

  const fundo = document.createElementNS(SVG, 'rect')
  fundo.setAttribute('width', String(lado))
  fundo.setAttribute('height', String(lado))
  fundo.setAttribute('fill', '#fff')
  const modulos = document.createElementNS(SVG, 'path')
  modulos.setAttribute('d', d)
  modulos.setAttribute('fill', '#000')
  svg.append(fundo, modulos)
  return svg
}
