// Gera o motor da nuvem pelo Telegram para o app Android (app/src/main/assets/cloud).
// Rode com: npm run build:android-cloud (o resultado vai para o repositório junto com o app).
import { build } from 'vite'
import { fileURLToPath } from 'node:url'
import { writeFileSync, mkdirSync } from 'node:fs'
import config from './vite.config.ts'
const out = fileURLToPath(new URL('../app/src/main/assets/cloud', import.meta.url))
mkdirSync(out, { recursive: true })
await build({
  configFile: false,
  root: fileURLToPath(new URL('.', import.meta.url)),
  logLevel: 'warn',
  resolve: config.resolve,
  build: {
    outDir: out,
    emptyOutDir: true,
    copyPublicDir: false,
    target: 'es2019',
    lib: { entry: 'src/cloud/androidEngine.ts', formats: ['iife'], name: 'alxCloudEngine', fileName: () => 'engine.js' },
    rollupOptions: { output: { inlineDynamicImports: true } }
  }
})
writeFileSync(`${out}/engine.html`, '<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src https://alexandria.cloud; connect-src wss://*.web.telegram.org"></head><body><script src="engine.js"></script></body></html>\n')
console.log('ok', out)
