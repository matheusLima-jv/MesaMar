import { defineConfig } from 'vite'

// O bundle é servido pelo Spring em /staff/ (ver backend/build.gradle.kts).
export default defineConfig({
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
