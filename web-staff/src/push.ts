// Avisos de pedido com o app fechado (Web Push). O navegador cuida da notificação; aqui só se inscreve este
// aparelho no servidor, que manda o aviso cifrado quando chega um pedido (ou ele atrasa).
import { ApiError, cancelarPush, inscreverPush, pushConfig, testarPush } from './api'
import { lembrar } from './dom'
import { instalado, plataforma } from './install'

export type EstadoPush =
  | 'servidor-sem-chaves' // o servidor ainda não tem as chaves VAPID
  | 'sem-conexao' // não deu para perguntar ao servidor (sinal): não é o mesmo que "sem chaves"
  | 'app-nao-pronto' // o service worker não ficou pronto (registro falhou ou demorou demais)
  | 'nao-suportado' // o navegador não sabe fazer push
  | 'precisa-instalar' // iPhone: só funciona com o app na tela inicial
  | 'bloqueado' // o usuário negou a permissão de notificações
  | 'desativado'
  | 'ativo'

function suportado (): boolean {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

/** A chave pública VAPID chega em base64url; o navegador quer os bytes. */
function bytesDaChave (base64url: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64url.length % 4)) % 4)
  const texto = atob((base64url + padding).replace(/-/g, '+').replace(/_/g, '/'))
  return Uint8Array.from(texto, (c) => c.charCodeAt(0))
}

/** O service worker não ficou pronto a tempo (registro falhou, navegador travado): ninguém fica esperando para sempre. */
export class AppNaoPronto extends Error {
  constructor () {
    super('O app ainda não terminou de se preparar neste celular. Feche e abra de novo, e tente outra vez.')
  }
}

const PRAZO_SW_MS = 8_000

/** `navigator.serviceWorker.ready` com prazo: sem ele, uma falha no registro deixava ativar, testar e até sair esperando. */
async function registroPronto (): Promise<ServiceWorkerRegistration> {
  let timer: number | undefined
  try {
    return await Promise.race([
      navigator.serviceWorker.ready,
      new Promise<never>((_resolve, reject) => { timer = window.setTimeout(() => { reject(new AppNaoPronto()) }, PRAZO_SW_MS) })
    ])
  } finally {
    window.clearTimeout(timer)
  }
}

async function inscricaoAtual (): Promise<PushSubscription | null> {
  const registro = await registroPronto()
  return await registro.pushManager.getSubscription()
}

export async function estadoPush (): Promise<EstadoPush> {
  // No iPhone o PushManager só existe com o app instalado (iOS 16.4 ou mais novo)
  if (plataforma() === 'iphone' && !instalado()) return 'precisa-instalar'
  if (!suportado()) return 'nao-suportado'
  let config
  try {
    config = await pushConfig()
  } catch (e) {
    // Sem sinal (ou o servidor demorou): não dá para saber se há chaves, então não diz que não há
    if (e instanceof ApiError && e.descartada) throw e
    return 'sem-conexao'
  }
  if (!config.habilitado) return 'servidor-sem-chaves'
  if (Notification.permission === 'denied') return 'bloqueado'
  let inscricao: PushSubscription | null
  try {
    inscricao = await inscricaoAtual()
  } catch (e) {
    if (e instanceof AppNaoPronto) return 'app-nao-pronto'
    throw e
  }
  return inscricao !== null && Notification.permission === 'granted' ? 'ativo' : 'desativado'
}

/** Pede a permissão, inscreve este aparelho e avisa o servidor. Lança um erro com a mensagem para mostrar. */
export async function ativarPush (): Promise<void> {
  const config = await pushConfig()
  if (!config.habilitado || config.chavePublica === null) throw new Error('Os avisos ainda não foram configurados no servidor.')
  const permissao = await Notification.requestPermission()
  if (permissao !== 'granted') throw new Error('Sem a permissão de notificações não dá para avisar. Libere nas configurações do navegador.')
  const registro = await registroPronto()
  const inscricao = await registro.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: bytesDaChave(config.chavePublica) })
  try {
    await inscreverPush(inscricao.toJSON())
  } catch (e) {
    // O servidor recusou: não deixa o aparelho inscrito só do lado do navegador
    await inscricao.unsubscribe().catch(() => undefined)
    throw e instanceof ApiError ? e : new Error('Não deu para ativar os avisos. Tente de novo.')
  }
  lembrar.set('push', 'on')
}

export async function desativarPush (): Promise<void> {
  lembrar.set('push', 'off')
  if (!suportado()) return
  // Sem service worker pronto, não há inscrição deste aparelho a cancelar: segue (sair nunca fica preso aqui)
  const inscricao = await inscricaoAtual().catch(() => null)
  if (inscricao === null) return
  await cancelarPush(inscricao.endpoint).catch(() => undefined)
  await inscricao.unsubscribe().catch(() => undefined)
}

/** "Manda um aviso agora": o dono confere no próprio celular. */
export async function testar (): Promise<void> {
  const inscricao = await inscricaoAtual()
  if (inscricao === null) throw new Error('Ative os avisos antes de testar.')
  await testarPush(inscricao.endpoint)
}

/**
 * Ao abrir o app: se este aparelho já tinha os avisos ligados, confirma a inscrição no servidor (o login pode ter
 * mudado de pessoa ou o navegador pode ter trocado a inscrição). Em silêncio, sem nunca pedir permissão.
 */
export async function religarPush (): Promise<void> {
  try {
    if (!suportado() || Notification.permission !== 'granted' || lembrar.get('push') === 'off') return
    const config = await pushConfig()
    if (!config.habilitado || config.chavePublica === null) return
    const registro = await registroPronto()
    const inscricao = await registro.pushManager.getSubscription() ??
      (lembrar.get('push') === 'on'
        ? await registro.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: bytesDaChave(config.chavePublica) })
        : null)
    if (inscricao !== null) await inscreverPush(inscricao.toJSON())
  } catch { /* sem sinal ou sem suporte: tenta de novo na próxima vez que abrir */ }
}
