import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { basename } from 'node:path'
const raiz = fileURLToPath(new URL('../', import.meta.url))
const app = basename(process.cwd())
if (!['web-customer', 'web-staff'].includes(app)) throw new Error('Execute npm run lint na pasta do frontend.')
const r = spawnSync(process.execPath, [`${app}/node_modules/eslint/bin/eslint.js`, 'web-customer/src', 'web-customer/public', 'web-customer/tests', 'web-customer/vite.config.ts', 'web-staff/src', 'web-staff/public', 'web-staff/tests', 'web-staff/vite.config.ts', 'web-shared', 'backend/src/main/resources/static/js', '--max-warnings=0'], { cwd: raiz, stdio: 'inherit', env: { ...process.env, MESAMAR_LINT_APP: app } })
process.exit(r.status ?? 1)
