// Módulos do Node que a GramJS só usa fora do navegador (rede TCP, proxy SOCKS, arquivos): vazios aqui.
export class EventEmitter {
  on() {
    return this
  }
  once() {
    return this
  }
  off() {
    return this
  }
  emit() {
    return false
  }
  removeListener() {
    return this
  }
}
export class LocalStorage {
  getItem() {
    return null
  }
  setItem() {}
  removeItem() {}
}
export class SocksClient {}
export class Socket {}
export default {}
