import { render } from 'preact'
import { registerSW } from 'virtual:pwa-register'
import { App } from './App'
import './styles.css'

render(<App />, document.getElementById('app')!)

// Guarda o site no navegador para abrir sem internet; atualiza sozinho quando há versão nova.
registerSW({ immediate: true })
