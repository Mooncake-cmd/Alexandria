export function Settings() {
  return (
    <>
      <h2>Ajustes</h2>
      <dl class="facts">
        <dt>Versão</dt>
        <dd>{__APP_VERSION__} (web)</dd>
        <dt>Seus dados</dt>
        <dd>Ficam só neste navegador. Nada é enviado para a internet.</dd>
        <dt>Código-fonte</dt>
        <dd>
          <a href="https://github.com/Mooncake-cmd/Alexandria" rel="noopener">github.com/Mooncake-cmd/Alexandria</a>
        </dd>
      </dl>
    </>
  )
}
