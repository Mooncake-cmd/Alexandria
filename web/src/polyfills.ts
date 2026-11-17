/**
 * O pouco que falta em navegadores mais antigos e que as bibliotecas de leitura usam no site
 * (libarchive.js, dos quadrinhos CBR, usa Promise.withResolvers: Chrome 119+, Safari 17.4+).
 * O pdf.js traz o que precisa na versão "legacy" dele.
 */
type WithResolvers = <T>() => { promise: Promise<T>; resolve: (value: T | PromiseLike<T>) => void; reject: (reason?: unknown) => void }
const P = Promise as unknown as { withResolvers?: WithResolvers }
if (typeof P.withResolvers !== 'function') {
  P.withResolvers = <T,>() => {
    let resolve!: (value: T | PromiseLike<T>) => void
    let reject!: (reason?: unknown) => void
    const promise = new Promise<T>((ok, fail) => {
      resolve = ok
      reject = fail
    })
    return { promise, resolve, reject }
  }
}

export {}
