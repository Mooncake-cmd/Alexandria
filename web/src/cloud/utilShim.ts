// Pedaço do "util" do Node que a GramJS usa no navegador (só o símbolo de inspeção e TextEncoder/Decoder).
export const inspect = Object.assign((value: unknown) => String(value), { custom: Symbol.for('nodejs.util.inspect.custom') })
export const TextEncoder = globalThis.TextEncoder
export const TextDecoder = globalThis.TextDecoder
export const promisify = <T extends (...args: never[]) => unknown>(fn: T) => fn
export default { inspect, TextEncoder, TextDecoder, promisify }
