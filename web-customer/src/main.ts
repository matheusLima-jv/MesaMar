import './style.css'
import { criarAplicacao } from './aplicacao'
import { iniciarAmbiente } from './ambiente'

const app = criarAplicacao(document.getElementById('app') as HTMLElement)
iniciarAmbiente()
window.addEventListener('pagehide', () => { app.parar() })
window.addEventListener('pageshow', (evento) => { if (evento.persisted) void app.iniciar() })
void app.iniciar()
