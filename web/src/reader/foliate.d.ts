// Tipos mínimos do foliate-js (JavaScript sem tipos), só o que o site usa.
declare module 'foliate-js/mobi.js' {
  export function isMOBI(file: Blob): Promise<boolean>
  export class MOBI {
    constructor(options: { unzlib: (data: Uint8Array) => Uint8Array })
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    open(file: Blob): Promise<any>
  }
}
