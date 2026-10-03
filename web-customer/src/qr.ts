import qrcode from 'qrcode-generator'
import { qrSvg as montarQr } from '../../web-shared/src/qr'

export const qrSvg = (texto: string, rotulo: string): SVGSVGElement => montarQr(qrcode, texto, rotulo)
