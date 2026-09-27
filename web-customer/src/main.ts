import './style.css'

// Placeholder da Fase 0: só confirma que o front conversa com o backend.
const status = document.querySelector<HTMLParagraphElement>('#status')

async function checkHealth(): Promise<void> {
  if (!status) return
  try {
    const response = await fetch('/actuator/health', { cache: 'no-store' })
    const body = (await response.json()) as { status?: string }
    status.textContent = body.status === 'UP' ? 'Servidor: online ✅' : 'Servidor: com problema ⚠️'
  } catch {
    status.textContent = 'Servidor: sem conexão ❌'
  }
}

void checkHealth()
