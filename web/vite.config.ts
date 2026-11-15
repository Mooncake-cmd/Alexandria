import { defineConfig } from 'vitest/config'
import preact from '@preact/preset-vite'
import { VitePWA } from 'vite-plugin-pwa'
import { readFileSync } from 'node:fs'

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string }

// O site fica em https://mooncake-cmd.github.io/Alexandria/ (GitHub Pages do repositório).
export default defineConfig({
  base: '/Alexandria/',
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  plugins: [
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
        globPatterns: ['**/*.{js,css,html,png,svg,woff2,webmanifest}'],
        navigateFallback: 'index.html'
      }
    })
  ],
  test: {
    include: ['src/**/*.test.ts']
  }
})
