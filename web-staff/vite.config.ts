import { createHash } from 'node:crypto'
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { defineConfig, type Plugin } from 'vite'

/**
 * Depois do build, grava no sw.js a versão desta publicação e a lista de arquivos dela. Assim o service worker:
 * - guarda, já na instalação, o JS e o CSS que a página usa (a primeira reabertura sem sinal funciona);
 * - usa um cache por publicação e apaga só as versões antigas DELE (nunca o cache do outro app).
 * Este arquivo fica fora do `tsc` (é lido pelo Vite, que roda no Node).
 */
function versaoDoServiceWorker (prefixo: string, base: string, pastas: string[]): Plugin {
  const medicao = `/js/medicoes-${createHash('md5').update(readFileSync('../backend/src/main/resources/static/js/medicoes.js')).digest('hex')}.js`
  return {
    name: 'mesamar-versao-do-sw',
    apply: 'build',
    transformIndexHtml: html => html.replaceAll('/js/medicoes.js', medicao),
    writeBundle () {
      const dist = 'dist'
      const arquivos = pastas
        .filter((pasta) => existsSync(join(dist, pasta)))
        .flatMap((pasta) => readdirSync(join(dist, pasta)).sort().map((f) => `${base}${pasta}/${f}`))
      const versao = createHash('sha256')
        .update(arquivos.join('\n'))
        .update(readFileSync(join(dist, 'index.html')))
        .digest('hex')
        .slice(0, 12)
      const caminho = join(dist, 'sw.js')
      const fonte = readFileSync(caminho, 'utf8').replaceAll('/js/medicoes.js', medicao)
      const marcaVersao = `'${prefixo}dev'`
      const marcaArquivos = '/* ARQUIVOS */ []'
      if (!fonte.includes(marcaVersao) || !fonte.includes(marcaArquivos)) throw new Error('sw.js sem as marcas de versão e de arquivos')
      writeFileSync(caminho, fonte.replace(marcaVersao, `'${prefixo}${versao}'`).replace(marcaArquivos, JSON.stringify(arquivos)))
    }
  }
}

// O bundle é servido pelo Spring em /staff/ (ver backend/build.gradle.kts).
export default defineConfig({
  plugins: [versaoDoServiceWorker('mesamar-staff-', '/staff/', ['assets', 'fonts', 'icons'])],
  base: '/staff/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    // No dev (npm run dev), as chamadas de API vão para o Spring local.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
