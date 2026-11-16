import { defineConfig } from 'vitest/config'
import preact from '@preact/preset-vite'
import { VitePWA } from 'vite-plugin-pwa'
import { cpSync, existsSync, readFileSync } from 'node:fs'

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string }

/**
 * Arquivos auxiliares das bibliotecas de leitura, servidos pelo próprio site (nada vem de fora):
 * fontes padrão, mapas de caracteres e decodificadores do pdf.js, e o motor de RAR do libarchive.js.
 */
function copyReaderAssets() {
  const copies: [string, string][] = [
    ['node_modules/pdfjs-dist/standard_fonts', 'public/vendor/pdfjs/standard_fonts'],
    ['node_modules/pdfjs-dist/cmaps', 'public/vendor/pdfjs/cmaps'],
    ['node_modules/pdfjs-dist/wasm', 'public/vendor/pdfjs/wasm'],
    ['node_modules/pdfjs-dist/LICENSE', 'public/vendor/pdfjs/LICENSE'],
    ['node_modules/libarchive.js/dist/worker-bundle.js', 'public/vendor/libarchive/worker-bundle.js'],
    ['node_modules/libarchive.js/dist/libarchive.wasm', 'public/vendor/libarchive/libarchive.wasm'],
    ['node_modules/libarchive.js/LICENSE', 'public/vendor/libarchive/LICENSE']
  ]
  return {
    name: 'copy-reader-assets',
    buildStart() {
      for (const [from, to] of copies) if (existsSync(from)) cpSync(from, to, { recursive: true })
    }
  }
}

// O site fica em https://mooncake-cmd.github.io/Alexandria/ (GitHub Pages do repositório).
export default defineConfig({
  base: '/Alexandria/',
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  plugins: [
    copyReaderAssets(),
    preact(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icons/favicon-64.png', 'icons/apple-touch-icon.png'],
      manifest: {
        name: 'Alexandria',
        short_name: 'Alexandria',
        description: 'Sua biblioteca pessoal: PDF, EPUB, MOBI, DJVU, quadrinhos, DOCX e TXT, sem servidor e sem internet.',
        lang: 'pt-BR',
        start_url: './',
        scope: './',
        display: 'standalone',
        background_color: '#F5F2EB',
        theme_color: '#162234',
        icons: [
          { src: 'icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icons/maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' }
        ]
      },
      workbox: {
        // Todo o site fica guardado para funcionar sem internet.
        globPatterns: ['**/*.{js,mjs,css,html,png,svg,woff2,webmanifest}'],
        globIgnores: ['vendor/**', 'licenses/**'],
        maximumFileSizeToCacheInBytes: 6 * 1024 * 1024,
        navigateFallback: 'index.html',
        // Arquivos das bibliotecas de leitura: guardados na primeira vez que um livro precisa deles.
        runtimeCaching: [
          { urlPattern: ({ url }) => url.pathname.includes('/vendor/'), handler: 'CacheFirst', options: { cacheName: 'alexandria-vendor' } }
        ]
      }
    })
  ],
  test: {
    include: ['src/**/*.test.ts']
  }
})
