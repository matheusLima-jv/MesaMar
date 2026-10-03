import { defineConfig, devices } from '@playwright/test'

if (!process.env.E2E_ADMIN_PASSWORD?.trim()) throw new Error('E2E_ADMIN_PASSWORD é obrigatória: os fluxos administrativos não podem ser pulados.')

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
    // PW 1.63: service workers são suportados somente em Chromium. Reabertura no Safari exige aparelho real.
    // Screenshotter WebKit injeta <style>body {}</style>, bloqueado por nossa CSP. Preservar traces DOM sem screenshots.
    { name: 'iphone', testIgnore: '**/offline-reabertura.spec.ts', use: { ...devices['iPhone 13'], browserName: 'webkit', serviceWorkers: 'block', screenshot: 'off', trace: { mode: 'retain-on-failure', screenshots: false, snapshots: true } } },
    { name: 'teclado', use: { ...devices['Desktop Chrome'], viewport: { width: 640, height: 720 }, isMobile: false } },
  ],
})
