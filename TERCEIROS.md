# Componentes de terceiros

O Alexandria lê PDF, MOBI/AZW/AZW3, DJVU e CBR (RAR) com quatro motores do **OpenReadEra**, o projeto de código aberto
publicado pela equipe do ReadEra. Eles são programas separados, compilados a partir das fontes que estão em
[`app/src/main/cpp/openreadera`](app/src/main/cpp/openreadera) e empacotados no APK como `liberamobi.so`,
`liberadjvu.so`, `liberacomic.so` e `liberapdf.so`. O app os executa e conversa com eles por pipes nomeados, sem ligar o código
deles ao próprio código.

| Motor | Para quê | Baseado em | Licença |
|---|---|---|---|
| EraMOBI | MOBI, AZW, AZW3 → EPUB | libmobi (LGPL-3.0+) | AGPL-3.0-ou-posterior |
| EraDjVu | DJVU | DjVuLibre (GPL-2.0+), libjpeg-turbo | AGPL-3.0-ou-posterior |
| EraPDF | PDF (páginas, texto com posição, busca) | MuPDF (AGPL-3.0+), jbig2dec (AGPL-3.0+), FreeType (FTL), OpenJPEG (BSD-2), libjpeg-turbo | AGPL-3.0-ou-posterior |
| EraComic | CBR (RAR 4 e 5) e CBZ | dmc_unrar (GPL-2.0+), libjpeg-turbo, libpng, libwebp, MiniZip, EasyBMP | GPL-2.0 |

- Origem das fontes, versão e lista exata de alterações: [`ALTERACOES-ALEXANDRIA.txt`](app/src/main/cpp/openreadera/ALTERACOES-ALEXANDRIA.txt).
- Textos completos das licenças e avisos das bibliotecas: [`app/src/main/assets/licenses`](app/src/main/assets/licenses).
  O app mostra os mesmos textos em **Ajustes → Licenças de código aberto**.

## Fonte Atkinson Hyperlegible

A fonte de leitura **Atkinson Hyperlegible** (© 2020 Braille Institute of America) vem do pacote `@fontsource/atkinson-hyperlegible`
5.3.0 e está em `app/src/main/assets/fonts` (subconjunto latino; os arquivos `.ttf` foram convertidos dos `.woff` originais, sem
alterar os desenhos). Licença: **SIL Open Font License 1.1**, em `app/src/main/assets/licenses/OFL-1.1-Atkinson.txt`.

## Fontes Liberation

As fontes **Liberation Serif, Sans e Mono** 2.1.5 (© 2010 Google Corporation; © 2012 Red Hat, Inc.), sem alterações, estão em
`app/src/main/assets/fonts/pdf`. Têm as mesmas medidas de Times New Roman, Arial e Courier New, e o EraPDF as usa quando um PDF
cita uma dessas fontes sem trazê-la embutida (comum em arquivos gerados pelo Word). Licença: **SIL Open Font License 1.1**, em
`app/src/main/assets/licenses/OFL-1.1-Liberation.txt`.

## Site (pasta `web/`)

O site usa, além do próprio código (AGPL-3.0-or-later): **Preact** (MIT), **fflate** (MIT, ZIP e zlib),
**foliate-js** (MIT, leitura de MOBI/AZW3, de John Factotum), **pdf.js** (Apache-2.0, Mozilla, com as fontes
padrão e mapas de caracteres dele), **libarchive.js** (MIT, com o libarchive em WebAssembly, BSD-2, para CBR em RAR),
**DjVu.js** (GPL-2.0-or-later, de RussCoder, cópia do código em `web/vendor/djvujs` com a alteração descrita no
`LEIA-ME.txt` de lá), **GramJS** (MIT, pacote `telegram`, com as dependências dele — buffer, pako, big-integer,
htmlparser2, mime, store2, async-mutex e outras de licença MIT/ISC/BSD —, carregado só quando a nuvem pelo Telegram é
usada; a mesma GramJS vai no APK, em `app/src/main/assets/cloud/engine.js`, gerada por `npm run build:android-cloud`,
com a licença em `app/src/main/assets/licenses/gramjs-MIT.txt`) e a fonte **Atkinson Hyperlegible** (SIL OFL 1.1,
em `web/public/fonts`). As ferramentas de build e teste (Vite, TypeScript, Vitest, jsdom, fake-indexeddb,
vite-plugin-pwa/Workbox) não são distribuídas no site publicado.

## Imagens da Estante

A planta e o globo da Estante (`app/src/main/assets/shelf`) foram fornecidos pelo dono do projeto para uso no app.
A madeira, os livros e as placas são desenhados em código.

## Obrigações ao distribuir o APK

AGPL e GPL exigem que quem recebe o APK também possa obter o **código-fonte dos motores**. Como as fontes e o
script de compilação estão neste repositório, basta que ele esteja acessível a quem receber o app (por exemplo,
repositório público, ou o código entregue junto com o APK). Alterações nos motores também precisam ser publicadas.

## O código do próprio Alexandria

O código do Alexandria (app Android em `app/` e site em `web/`) é distribuído sob a **GNU Affero General Public
License, versão 3 ou (a seu critério) qualquer versão posterior** (AGPL-3.0-or-later), texto em [`LICENSE`](LICENSE).
É a mesma licença dos motores principais (EraPDF, EraMOBI, EraDjVu) e é compatível com as demais bibliotecas usadas
(GPL-2.0-or-later, LGPL, Apache-2.0, MIT/BSD). As fontes (SIL OFL) e as imagens da Estante mantêm as próprias licenças.

## Amostras de teste

`app/src/test/resources/ore/*.mobi` vêm dos testes do libmobi (LGPL-3.0+). Os demais arquivos dessa pasta
(DJVU, CBR e PNG) foram gerados para os testes.
