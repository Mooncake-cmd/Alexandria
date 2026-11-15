declare module 'libarchive.js' {
  export class Archive {
    static init(options: { workerUrl: string }): void
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    static open(file: File): Promise<any>
  }
}
