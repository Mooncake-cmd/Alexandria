// Trabalhador do pdf.js: primeiro o que falta em navegadores mais antigos, depois o pdf.js.
// (A versão "legacy" do pdf.js não traz Promise.withResolvers, ausente no Chrome 109 do Windows 7.)
import '../polyfills'
import 'pdfjs-dist/legacy/build/pdf.worker.mjs'
