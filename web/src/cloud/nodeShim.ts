// A GramJS (biblioteca do Telegram) espera o Buffer e o process do Node no navegador.
import { Buffer } from 'buffer'
const g = globalThis as unknown as { Buffer?: typeof Buffer; process?: unknown }
g.Buffer ??= Buffer
g.process ??= { env: {}, versions: {}, platform: 'browser', nextTick: (fn: () => void) => queueMicrotask(fn) }
export {}
