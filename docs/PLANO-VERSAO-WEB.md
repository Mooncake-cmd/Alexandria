# Plano: Alexandria na web (GitHub Pages)

> Documento de planejamento e acompanhamento. Base: app Android 0.15.0 (≈9.200 linhas de Kotlin, 20 arquivos de teste).
>
> **Situação:** Etapas 0 a 7 prontas (base do site, regras do app com testes, biblioteca com backup, leitores de todos os formatos, ferramentas do leitor, tempo e estatísticas).

## 0. Decisões do dono do projeto

1. **Publicação automática**, sem precisar juntar ao `main`: a cada atualização da pasta `web/` no branch de
   desenvolvimento (ou no `main`), o GitHub Actions testa, gera o site e o publica no branch `gh-pages`.
2. **Foco no computador** (Chrome, Edge, Firefox; Safari no Mac). Mouse, teclado e trackpad em primeiro lugar;
   telas de toque continuam funcionando, mas não são a prioridade. Ver seção 2.1.
3. **Sem migração** da biblioteca do app para o site (a antiga Etapa 10 saiu do plano).
4. **Licença do Alexandria: AGPL-3.0-or-later** (seção 4.1), aprovada e aplicada (`LICENSE`).

## 1. Objetivo e princípios

Levar **todas as funcionalidades do app Android** para um site em
`https://mooncake-cmd.github.io/Alexandria/`, que:

1. **Funciona sem servidor**: o GitHub Pages só entrega arquivos estáticos. Toda leitura, conversão e
   armazenamento acontecem no navegador. **Nenhum livro sai do aparelho** (como no app, que nem tem permissão de internet).
2. **Funciona offline** depois da primeira visita e pode ser **instalado** como app (PWA: ícone na tela inicial,
   abre em tela cheia, sem a barra do navegador).
3. **Mantém o comportamento do app**: mesmos textos em português, mesmas regras (páginas com linhas inteiras,
   sequência de 5 min, glossário sem acentos, etc.), mesma aparência (farol, Estante de madeira, cores).
4. **Reaproveita a lógica já testada**: as regras puras do Kotlin (tempo, sequência, glossário, paginação por
   linhas, leitura de EPUB/DOCX, decodificação de TXT…) são portadas para TypeScript **junto com os testes**, que
   passam a garantir que o site faz exatamente o mesmo que o app.
5. **Cuidado com os dados**: o navegador pode apagar dados de sites. O site pede armazenamento persistente e oferece
   **backup/restauração** desde a primeira versão que guarda livros.

## 2. O que muda por ser web (limites que precisam ser aceitos)

| Assunto | No app | No site | Consequência |
|---|---|---|---|
| Brilho | Controla o brilho da tela | Navegador não controla o brilho | "Brilho do app" vira só escurecimento (uma camada escura); pode ficar abaixo do mínimo do sistema, mas não acima do brilho atual |
| Barras do sistema | Somem na tela limpa | API de tela cheia (Android: sim; iPhone: só com o site instalado na tela inicial) | Tela limpa completa no Android; no iPhone, só instalado |
| "Abrir com" | Aparece na lista do Android | Computador: arrastar para a janela; com o site instalado (Chrome/Edge), "Abrir com" do sistema | Firefox e Safari: só importar ou arrastar |
| "Compartilhar" | Aparece na lista | Não se aplica ao computador | — |
| Liberar espaço | Apaga a cópia e, com permissão, o arquivo do celular | Apaga só a cópia guardada no site | O arquivo original do celular nunca é tocado |
| Armazenamento | SQLite + pastas do app | IndexedDB + OPFS do navegador | Limpar dados do navegador apaga a biblioteca → backup |
| iPhone/Safari | — | Safari apaga dados de sites **não instalados** após ~7 dias sem uso | No iPhone, instalar na tela inicial é obrigatório para uso contínuo |
| Leitor de tela | O app detecta e deixa a alça da régua visível | Navegador não informa se há leitor de tela | Ajuste manual "Alça da régua sempre visível" |
| Motores | Programas nativos (MuPDF, libmobi, DjVuLibre, EraComic) | Bibliotecas JavaScript/WebAssembly | Mesmos formatos, outras bibliotecas (seção 4) |
| Sincronizar com o app | — | Não há servidor | Os dois não se sincronizam; não haverá migração (decisão 3) |

### 2.1 Computador primeiro: como os gestos do app viram mouse e teclado

| No app (toque) | No site (computador) |
|---|---|
| Toque duplo na borda direita/esquerda vira a página | Setas → e ←, Page Down/Up, espaço; clique duplo nas bordas; roda do mouse no modo páginas |
| Rolagem com o dedo | Roda do mouse, trackpad, barra de rolagem, setas ↑ ↓ |
| Pinça / toque duplo no meio = zoom | Ctrl + roda (e pinça no trackpad), Ctrl + "+"/"−"/"0", clique duplo no meio |
| Arrastar a página com zoom | Arrastar com o mouse, roda (vertical) e Shift + roda (lateral) |
| Toque no meio mostra/esconde controles (tela limpa) | Clique no meio; as barras também aparecem ao levar o mouse ao topo; tecla F ou F11 para tela cheia; Esc sai |
| Toque longo num livro = opções | Clique com o botão direito (e um botão "⋯" no cartão) |
| Toque longo numa palavra seleciona | Seleção normal com o mouse (clique duplo seleciona a palavra) + barra com Copiar, Definir termo e Anotar |
| Tocar na régua mostra a alça | Passar o mouse sobre a régua mostra a alça; arrastar com o mouse; Alt + ↑/↓ move a régua |
| Abrir com / Compartilhar | Arrastar arquivos para a janela; "Abrir com" do sistema quando o site está instalado (Chrome/Edge) |

O layout aproveita telas largas: biblioteca em grade, abas numa barra lateral, texto numa coluna de largura
confortável e painéis (sumário, busca, anotações, glossário) ao lado do texto em vez de por cima. Em janelas
estreitas, volta a ficar como no celular (abas embaixo).

## 3. Arquitetura

### 3.1 Tecnologias

- **TypeScript + Vite** (build rápido, saída estática para o Pages).
- **Preact** (interface em componentes, 4 KB) com *signals* para estado. Sem framework de CSS: tokens de cor
  próprios (os mesmos `Palette`/`Comfort` do app).
- **IndexedDB** (via `idb`) para dados; **OPFS** (Origin Private File System) para os arquivos dos livros, com
  IndexedDB como reserva onde não houver OPFS.
- **Service Worker** (via `vite-plugin-pwa`/Workbox): guarda o site para uso offline e serve os arquivos internos dos
  EPUBs (o papel do `book.alexandria` do WebView).
- **Web Workers** para tudo que é pesado (abrir PDF, converter MOBI, extrair RAR, ler DJVU, gerar capas, buscar),
  para a tela nunca travar.
- **Vitest** (testes da lógica) + **Playwright** (testes de ponta a ponta no Chromium, com emulação de celular).

### 3.2 Pastas (no mesmo repositório)

```
web/
  index.html, manifest.webmanifest, vite.config.ts
  public/            ícones (farol), fontes (Atkinson), decoração (planta, globo), licenças
  src/
    core/            regras puras portadas do Kotlin (+ testes)
    storage/         banco (IndexedDB/OPFS), backup, migrações
    formats/         um módulo por formato: abrir, páginas/capítulos, texto, capa, busca
    reader/          leitor: modos, gestos, régua, foco, tela limpa, seleção, glossário, anotações
    library/         biblioteca, Estante de madeira, prateleiras, capas
    screens/         Biblioteca, Notas, Estatísticas, Ajustes, Leitor
    sw/              service worker
  tests/e2e/         Playwright
```

O app Android continua em `app/`, sem mudanças.

### 3.3 Dados (equivalente ao banco v7 do app)

| Store | Conteúdo | Origem no app |
|---|---|---|
| `items` | id, nome, formato, datas (adicionado, aberto, concluído, arquivado), posição, páginas, prateleira, tempo, tamanho | `library_items` |
| `files` (OPFS) | o arquivo do livro | cópia em `filesDir/library` |
| `covers` | capa automática e capa personalizada (Blob) | `covers/` |
| `notes`, `terms` | anotações e glossário | `notes`, `terms` |
| `readingDays`, `readingLog` | tempo por dia e por livro+dia | `reading_days`, `reading_log` |
| `cache` | conversões (MOBI→EPUB, páginas de RAR) | `cacheDir` |
| `prefs` | modo de leitura, régua, conforto, tela limpa, bloqueio lateral… | SharedPreferences |

Com versão de esquema e migrações (como o `onUpgrade` do app).

### 3.4 Segurança e privacidade

- Política de segurança (CSP): só código do próprio site; **scripts dos livros bloqueados** (EPUB em iframe com
  `sandbox` sem `allow-scripts`; o paginador e o glossário agem de fora, pelo documento do iframe).
- Nenhuma análise de uso, nenhum servidor, nenhum cookie. Links externos dos livros são ignorados (como no app).

## 4. Formatos: como cada um será lido

| Formato | Biblioteca | Licença | Observações |
|---|---|---|---|
| PDF | **pdf.js** (Mozilla) | Apache-2.0 | Desenho das páginas, camada de texto (seleção, glossário, busca); já traz fontes padrão (inclusive Liberation) para PDFs sem fontes embutidas — o problema resolvido na 0.11 |
| EPUB 2/3 | **Leitor próprio**, portado do `EpubViewer`/`EpubPackage` | — | Capítulo num iframe protegido; mesmo CSS de conforto, glossário (`Glossary.markHtml`), paginador por linhas (o `PAGER_JS` já é JavaScript) |
| MOBI/AZW/AZW3 | Módulo `mobi.js` do **foliate-js** (cópia fixada no repositório) | MIT | Convertido para a mesma estrutura do EPUB e lido pelo leitor de EPUB (como o EraMOBI faz hoje). Livros com DRM continuam sem abrir |
| DJVU | **DjVu.js** | GPL-2.0 | Páginas como imagem, num Worker |
| CBZ | **fflate** (ZIP) | MIT | Mesma ordem natural (`ComicArchive`) |
| CBR (RAR 4/5) | **libarchive.js** (WebAssembly) | MIT / BSD-2 | Extração sob demanda; CBR que é ZIP disfarçado continua aberto como CBZ |
| DOCX | **Leitor próprio**, portado do Kotlin (`Books.kt`) | — | Títulos com estilos em português, listas, tabelas, caixas de texto, imagens |
| TXT | Portado do `TextDecoding` | — | UTF-8/UTF-16/Windows-1252; lista virtual para arquivos grandes |

As bibliotecas WebAssembly (DJVU, RAR) só são baixadas quando um livro desse formato é aberto, e depois ficam
guardadas para uso offline.

**Licenças:** a página de licenças do site lista todas, como em Ajustes do app. O DjVu.js é GPL-2.0 **ou posterior**
(confirmado no `LICENSE.md` do projeto), então pode ser combinado com código GPL-3.0/AGPL-3.0.

### 4.1 Licença do Alexandria: AGPL-3.0-or-later (aprovada)

- **Compatível com tudo o que já é usado**: os motores do app (EraPDF, EraMOBI, EraDjVu: AGPL-3.0; EraComic:
  GPL-2.0-or-later), pdf.js (Apache-2.0), DjVu.js (GPL-2.0-or-later), foliate-js, fflate e libarchive.js (MIT/BSD)
  e as fontes (SIL OFL, que ficam com a própria licença).
- **Uma licença só para app e site**, a mesma dos motores principais: não há dúvida sobre misturar os códigos.
- **O que ela permite e exige**: qualquer pessoa pode usar, estudar, modificar e redistribuir (inclusive vender),
  desde que entregue o código-fonte com a mesma licença; quem oferecer uma versão modificada como serviço na
  internet também precisa publicar o código.
- **Alternativa**: GPL-3.0-or-later (igual, sem a cláusula de serviço na internet). Licenças permissivas (MIT,
  Apache) não servem para o app, por causa dos motores AGPL.
- Ao aprovar: arquivo `LICENSE` na raiz, aviso no README e nas telas de licenças do app e do site.

## 5. Lista completa de funcionalidades e como cada uma vai para a web

Cada linha é um item de verificação: a etapa só termina quando todos os itens dela funcionam e têm teste.

### 5.1 Biblioteca
| Funcionalidade do app | Na web | Etapa |
|---|---|---|
| Importar vários documentos | Botão Importar (seletor de arquivos, várias de uma vez) + arrastar e soltar no computador | 2 |
| Mesmo livro (nome + tamanho) não duplica, volta ao ponto | Igual | 2 |
| Cartões com capa, formato, progresso, tempo | Igual | 2 |
| Capas (PDF 1ª página, EPUB capa declarada, CBZ/CBR/DJVU 1ª imagem/página, MOBI) | Geradas num Worker e guardadas | 2/4/5 |
| Lendo agora / Estante recolhível, ordem por leitura recente | Igual | 2 |
| Prateleiras (mover, renomear, recolher) | Igual | 2 |
| Marcar concluído / voltar para "Lendo agora", remover | Igual | 2 |
| Liberar espaço mantendo na Estante; reimportar traz de volta | Igual (apaga a cópia guardada) | 2 |
| Abrir com | Arrastar para a janela (2); "Abrir com" do sistema com o site instalado — File Handling, Chrome/Edge (9) | 2/9 |
| Sequência "🔥 N dias seguidos" na biblioteca | Igual | 7 |
| Estante de madeira (madeira, livros em pé em 3D, sombra, giro ao tocar, capa de couro) | Madeira em Canvas (mesmo gerador de veios); livros com CSS 3D; planta e globo | 8 |
| Placas de latão que abrem/fecham e renomeiam | Igual | 8 |
| Trocar capa pela galeria (corte 2:3, foto endireitada) / restaurar | Seletor de imagem; `createImageBitmap` já endireita pela orientação da foto | 8 |
| Opções com data de conclusão e tempo no topo | Igual (menu ao manter pressionado / clique direito) | 2/8 |

### 5.2 Leitor (todos os formatos)
| Funcionalidade | Na web | Etapa |
|---|---|---|
| Modo páginas (toque duplo nas bordas) / rolagem contínua, lembrado | Igual; no computador também setas e Page Up/Down | 3–5 |
| Páginas com linhas inteiras (EPUB, MOBI, TXT, DOCX) | `LinePager` portado + paginador do EPUB | 3/4 |
| Zoom (pinça, toque duplo) nos dois modos; arrastar com zoom | Pointer Events próprios (o zoom do navegador fica desligado no leitor) | 5 |
| Bloquear movimento para os lados | Igual | 5 |
| Tela limpa (toque no meio mostra/esconde; toque duplo, termo, régua e seleção não mexem) | Igual + API de tela cheia | 6 |
| Controles sempre visíveis (livro entre as barras) | Igual | 6 |
| Régua (alça aparece ao tocar, espessura, cor, opacidade) | Igual + opção "alça sempre visível" | 6 |
| Modo foco (só o texto da régua) | Igual | 6 |
| Conforto: 5 temas (também PDF/DJVU/quadrinhos, com inversão nos escuros), brilho, luz quente, sobreposição | Igual (brilho = escurecimento, ver seção 2); inversão por filtro CSS | 6 |
| Tipografia (fonte, Atkinson, espaçamentos, margens, largura, alinhamento) | CSS, como hoje no EPUB | 3/4 |
| Seleção de texto (PDF com alças; EPUB/DOCX/TXT) | Seleção nativa do navegador + barra própria (Copiar, Definir termo, Anotar) | 3–5 |
| Glossário (definir, sublinhar sem acento/maiúscula, tocar mostra, editar, remover) | `Glossary` portado; sublinhado em todos os formatos de texto e no PDF | 6 |
| Anotações (trecho ou página inteira), lista leva ao ponto | Igual | 6 |
| Busca sem acentos, com lista de resultados | Igual (pdf.js para PDF) | 6 |
| Ir para página, sumário, capítulo anterior/próximo, letra maior/menor | Igual | 3–5 |
| Progresso salvo, "Você chegou ao fim / Concluir livro" | Igual | 3 |
| Cronômetro (pausa ao sair da tela ou após 5 min sem toque) | Page Visibility API + mesmo `ReadingClock` | 7 |

### 5.3 Notas, Estatísticas, Ajustes
| Funcionalidade | Na web | Etapa |
|---|---|---|
| Aba Notas (anotações e glossários de todos os livros) | Igual | 7 |
| Estatísticas: sequência, melhor, hoje, total, calendário, mais tempo, por formato | Igual | 7 |
| Apagar o tempo de um livro; limpar estatísticas | Igual (mesma regra do `clearBookTime`) | 7 |
| Ajustes: versão, espaço usado (cópias/cache) com botão para liberar, licenças | Igual + **Backup e restauração** + "manter dados" (armazenamento persistente) | 2/7 |

## 6. Etapas de implementação

Cada etapa termina com: testes passando, site publicado (ou prévia), e um resumo para você testar no celular.
Tamanho relativo: P (pequena), M (média), G (grande).

### Etapa 0 — Base do site (P) ✅
- Projeto `web/` com Vite + TypeScript + Preact, ícone do farol, cores e abas com os mesmos ícones do app
  (barra lateral no computador, abas embaixo em janela estreita).
- PWA: manifesto, service worker, funciona offline, instalável.
- GitHub Actions (`.github/workflows/web.yml`): testa, gera e publica no branch `gh-pages` a cada atualização.
- Primeiro porte com testes: `ReadingTime` (formatação, cronômetro e `spreadRemoval`).
- **Pronto quando:** o endereço do site abre a casca do app (abas vazias), instala no navegador e abre sem internet.

### Etapa 1 — Lógica portada com testes (M) ✅
Portar para `web/src/core/`, com os testes equivalentes do Kotlin:
`ReadingTime` (inclui `spreadRemoval`), `Days`, `Streak`, `ReadingClock`, `Glossary` (normalização e marcação),
`PageTurn`, `LinePager`, `ReadingStatus`, `Shelves`, `Formats`, `Comfort` (modelo, cores, CSS), `TextDecoding`,
`ComicArchive`, `EpubPackage`, leitor de DOCX.
- **Pronto quando:** os testes de unidade portados passam e dão os mesmos resultados que os do app.

### Etapa 2 — Armazenamento e biblioteca (M) ✅
- Banco no IndexedDB (livros e capas como Blobs; o OPFS fica para depois, se o IndexedDB não der conta de livros muito grandes), migrações, pedido de armazenamento persistente.
- Importar (vários, arrastar e soltar), sem duplicar, cartões, Lendo agora/Estante (ainda em lista), prateleiras,
  concluir, remover, liberar espaço, espaço usado.
- **Backup e restauração** (arquivo `.zip` com dados, capas e, opcionalmente, os livros).
- **Pronto quando:** dá para montar a biblioteca, fechar e reabrir o navegador e tudo continuar lá; o backup
  restaura tudo num navegador limpo.

### Etapa 3 — TXT e DOCX (M) ✅
- Leitor de texto com lista virtual, modo páginas com linhas inteiras e rolagem contínua, letra, tipografia,
  temas, progresso, fim do livro, seleção.
- **Pronto quando:** os mesmos cenários dos testes `TextReaderTest`/`ReadingModesTest` passam no Playwright.

### Etapa 4 — EPUB e MOBI (G) ✅
- Leitor de EPUB (iframe protegido; os arquivos do livro viram endereços locais blob:, sem precisar do service worker), sumário, links internos, capítulos, paginador por linhas,
  posição, capas; MOBI/AZW3 pelo foliate-js.
- **Pronto quando:** EPUBs de teste do app e o "Didascalicon" abrem, paginam sem repetir/cortar linhas, voltam ao
  ponto; MOBIs de teste (`huffdic`, `windows-1252`, `obfuscated_fonts`) abrem.

### Etapa 5 — PDF, quadrinhos e DJVU (G) ✅
- Visualizador de páginas (modo páginas com página inteira e rolagem contínua), zoom por pinça/toque duplo,
  bloqueio lateral, ir para página; PDF com camada de texto (seleção com alças, busca); CBZ, CBR (RAR 4/5), DJVU.
- **Pronto quando:** as amostras do app (`amostra.pdf`, `sem-fontes.pdf`, `amostra.djvu`, `quadrinho-rar5.cbr`)
  abrem e os gestos funcionam no Chrome do Android (emulação no Playwright + teste seu no aparelho).

### Etapa 6 — Ferramentas do leitor (M) ✅
- Tela limpa, controles sempre visíveis, régua (alça ao tocar), modo foco, painel de conforto completo,
  busca com lista, anotações, glossário em todos os formatos.
- **Pronto quando:** cada item da tabela 5.2 tem teste de ponta a ponta.

### Etapa 7 — Tempo, estatísticas, Notas e Ajustes (M) ✅
- Cronômetro com pausa, sequência, calendário, estatísticas, apagar tempo de um livro, limpar estatísticas,
  aba Notas, Ajustes e licenças.
- **Pronto quando:** os cenários de `GlossaryAndTimeTest`, `LibraryDatabaseTest` (tempo) e `MainActivityTest`
  têm equivalentes passando.

### Etapa 8 — Estante de madeira e capas (M)
- Madeira gerada em Canvas, livros 3D com CSS (giro ao tocar), sombras, planta e globo, placas de latão,
  capa de couro, trocar/restaurar capa.
- **Pronto quando:** a prévia do site fica igual às imagens aprovadas da Estante do app.

### Etapa 9 — Acabamento de app (M)
- "Abrir com" do sistema (site instalado, Chrome/Edge), todos os atalhos de teclado, acessibilidade (rótulos,
  foco visível, navegação só por teclado, leitor de tela), desempenho com livros grandes, testes em Chrome, Edge,
  Firefox e Safari (Mac), em telas grandes e janelas estreitas.
- **Pronto quando:** a lista completa da seção 5 está marcada.

## 7. Como vai ser testado

- **Testes de unidade** (Vitest) da lógica portada: mesmos casos do Kotlin.
- **Testes de ponta a ponta** (Playwright, Chromium com emulação de celular): abrir cada amostra, virar página,
  zoom, régua, glossário, anotar, buscar, tempo, Estante, backup.
- **Imagens de prévia** a cada etapa (como as da Estante e do modo foco), para você aprovar a aparência.
- **Teste no seu computador** a cada etapa publicada, no navegador que você usa.

## 8. Riscos e como reduzir

| Risco | Redução |
|---|---|
| Navegador apaga os dados | Armazenamento persistente, aviso para instalar o site, backup em Ajustes |
| Livros grandes travarem o celular | Workers, desenhar só as páginas visíveis, liberar memória das páginas que saem da tela |
| Gestos brigarem com o navegador (zoom, voltar com deslize) | Desligar o zoom do navegador só no leitor; `touch-action` nas áreas de leitura |
| Diferenças entre navegadores (Safari) | Testes em vários navegadores na Etapa 9; recursos que não existem no Safari ficam ocultos, não quebrados |
| Bibliotecas de terceiros mudarem | Versões fixadas; cópia do foliate-js dentro do repositório |
| Licenças | Página de licenças completa; definir a licença do Alexandria antes de publicar |

## 9. Pendências

- ~~Ativar o GitHub Pages~~ (feito: o site está no ar).
