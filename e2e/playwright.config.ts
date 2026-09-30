import { defineConfig, devices } from '@playwright/test'

/**
 * E2E contra a aplicação rodando de verdade (docker compose).
 *   E2E_BASE_URL        padrão http://localhost:8080
 *   E2E_ADMIN_PASSWORD  senha do painel (a mesma de MESAMAR_ADMIN_PASSWORD)
 */
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  fullyParallel: false, // os testes compartilham o limite de envios por IP
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['json', { outputFile: 'test-results/results.json' }], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    locale: 'pt-BR',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    // O público real é celular: Pixel 7 (Chrome no Android)
    { name: 'celular', use: { ...devices['Pixel 7'] } },
  ],
})
