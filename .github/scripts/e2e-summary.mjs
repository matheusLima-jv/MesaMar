// Resume o results.json do Playwright em anotações do GitHub Actions.
//   uso: node .github/scripts/e2e-summary.mjs e2e/test-results/results.json
import { existsSync, readFileSync } from 'node:fs'

const file = process.argv[2] ?? 'e2e/test-results/results.json'
if (!existsSync(file)) {
  console.log(`::warning title=E2E::Resultado não encontrado em ${file}`)
  process.exit(0)
}

const report = JSON.parse(readFileSync(file, 'utf8'))
const esc = (s) => String(s).replace(/%/g, '%25').replace(/\r/g, '').replace(/\n/g, '%0A')
const stripAnsi = (s) => String(s).replace(/\u001b\[[0-9;]*m/g, '')

const lines = []
const failures = []
let passed = 0
let failed = 0
let loadMs = null

function walk (suite, prefix = '') {
  const title = suite.title && !suite.title.endsWith('.ts') ? `${prefix}${suite.title} › ` : prefix
  for (const spec of suite.specs ?? []) {
    for (const t of spec.tests ?? []) {
      const last = t.results.at(-1)
      const ok = t.status === 'expected' || t.status === 'flaky'
      const skipped = t.status === 'skipped'
      if (skipped) continue
      ok ? passed++ : failed++
      lines.push(`${ok ? '✅' : '❌'} ${suite.file ?? ''} › ${title}${spec.title}`)
      for (const a of t.annotations ?? []) if (a.type === 'carregamento-4g-ms') loadMs = a.description
      for (const r of t.results ?? []) for (const a of r.annotations ?? []) if (a.type === 'carregamento-4g-ms') loadMs = a.description
      if (!ok && last) failures.push(`${spec.title}\n${stripAnsi(last.error?.message ?? '').slice(0, 2500)}`)
    }
  }
  for (const child of suite.suites ?? []) walk(child, title)
}
for (const s of report.suites ?? []) walk(s)

console.log(`::notice title=E2E: ${passed} passaram · ${failed} falharam::${esc(lines.join('\n'))}`)
if (loadMs) console.log(`::notice title=E2E: landing em 4G simulado::${loadMs} ms (meta do plano: 1500 ms)`)
failures.slice(0, 8).forEach((f) => console.log(`::error title=E2E falhou::${esc(f)}`))
