# Alexandria — projeto Android

**Versão:** 0.12.0  
**Pacote:** `com.alexandria.reader`  
**Plataforma:** Android 6.0 ou superior (API 23+)  
**Idioma da interface:** Português (Brasil)

## O que esta versão contém

- Aplicativo Android nativo em Kotlin, sem dependências externas no APK.
- Abas com ícones: livro (Biblioteca), caneta pena (Notas), ábaco (Estatísticas) e bússola (Ajustes).
- Ícone próprio (farol sobre livros), adaptativo e com versão monocromática para ícones temáticos (Android 13+).

### Biblioteca

- **Abrir com / Compartilhar**: o Alexandria aparece na lista ao abrir ou compartilhar PDF, EPUB, MOBI/AZW3, DJVU,
  CBZ/CBR e DOCX em outros apps (WhatsApp, Gmail, Arquivos, Drive…). O arquivo é copiado para o app (a permissão do
  outro app é temporária), entra na biblioteca e abre na hora. O mesmo livro (mesmo nome e tamanho) aberto de novo
  volta ao ponto onde parou, sem duplicar. TXT ficou de fora para o app não aparecer em qualquer arquivo de texto.
- **Liberar espaço de um livro concluído** (toque longo → "Liberar espaço (manter na Estante)"): o arquivo é apagado,
  mas capa, nome, datas, tempo, anotações e glossário ficam na Estante. Importar ou abrir o arquivo de novo traz o
  livro de volta ao mesmo lugar. O arquivo do celular só é apagado se o app tiver permissão de escrita nele (a
  importação pede essa permissão desde a 0.10.0; para livros importados antes, o app avisa para apagar pelo Arquivos).

- Importação de vários documentos de uma vez (Storage Access Framework), guardados no SQLite local.
- Cartões com **capa** (PDF, EPUB e CBZ), formato, progresso em texto e barra de progresso.
- **Lendo agora** e **Estante** (livros concluídos, recolhível), ordenados pelo que foi lido por último.
- **Estante de madeira**: os concluídos ficam em pé num móvel de madeira, com capa, lombada, páginas e sombra; na
  prateleira de cima, uma planta e um globo. Tocar num livro faz ele girar um pouco em 3D e abre; manter pressionado
  mostra as opções (com data de conclusão e tempo de leitura no topo). Cada prateleira com nome ganha uma **placa de
  latão**: tocar abre ou fecha a prateleira, manter pressionado renomeia. Livro sem capa ganha capa de couro com o título.
- **Trocar capa** (toque longo → "Trocar capa…"): escolha uma imagem da galeria; ela é cortada no centro na proporção
  de livro (2:3), endireitada se a foto estiver deitada, e guardada no app. "Restaurar capa original" volta à automática.
- **Prateleiras**: toque longo num livro → mover para prateleira; toque longo no nome da prateleira → renomear.
- Toque longo também permite marcar como concluído/não concluído e remover (apaga as anotações do livro).

### Leitores

| Formato | Como é exibido | Recursos |
|---|---|---|
| PDF | motor **EraPDF** (MuPDF, o mesmo do ReadEra) | **texto selecionável**, glossário sublinhado, busca, anotar trecho, zoom, ir para página |
| CBZ | imagens do ZIP em ordem natural (pág. 2 antes da 10) | os mesmos do PDF |
| CBR | RAR 4/5 pelo motor **EraComic** (ou como CBZ, se for ZIP disfarçado) | os mesmos do PDF |
| DJVU | motor **EraDjVu** (DjVuLibre) | os mesmos do PDF |
| EPUB 2 e 3 | WebView do sistema, capítulo por capítulo | sumário, busca, letra maior/menor, tema escuro |
| MOBI, AZW, AZW3 | convertido em EPUB pelo motor **EraMOBI** (libmobi) na primeira leitura | os mesmos do EPUB |
| DOCX | texto com títulos, listas, negrito, itálico, sublinhado e **imagens** | busca, letra, tema escuro |
| TXT | blocos numa lista (fluido mesmo em arquivos grandes) | UTF-8/UTF-16/Windows-1252, busca, letra, tema escuro |

Em todos os formatos:

- **Modo de leitura** (menu ⋮), lembrado entre as leituras:
  - **Páginas**: uma página (ou uma tela de texto) por vez. **Toque duas vezes na borda direita** para avançar e na
    **esquerda** para voltar; deslizar não vira a página. Em PDF, DJVU e quadrinhos a página aparece **inteira** na
    tela; toque duplo no meio ou pinça dão zoom, e com zoom o dedo arrasta a página.
  - **Rolagem contínua**: o texto corre livremente; em PDF, DJVU e quadrinhos as páginas ficam uma embaixo da outra.
- **Zoom também na rolagem contínua**: pinça ou toque duplo ampliam a coluna de páginas; com zoom, o dedo arrasta para
  os lados e a rolagem vertical continua normal.
- **Régua de leitura** (menu ⋮): faixa sobre a linha lida; arraste pela alça à direita. Espessura (1, 2 ou 3 linhas),
  cor, opacidade e "escurecer o resto da página" são escolhidos no Conforto visual.
- **Glossário do livro**: selecione uma palavra ou expressão ("Arrakis", "Casa Atreides") e toque em **"Definir termo"**.
  Nas próximas vezes em que ela aparecer no livro, fica **sublinhada**: um toque mostra a definição (com editar e remover).
  Vale sem diferenciar maiúsculas e acentos, só para palavras inteiras, e cada livro tem o seu glossário (menu ⋮ →
  "Glossário do livro"; todos aparecem também na aba Notas). Vale também no PDF. Em DJVU e quadrinhos o texto é
  imagem: dá para cadastrar termos pelo menu, mas não há sublinhado.
- **Tempo de leitura**: o cronômetro aparece embaixo do título ao abrir o livro. Para quando o app sai da tela ou depois
  de 5 minutos sem nenhum toque. O total de cada livro aparece no cartão da biblioteca.
- **Limpar estatísticas** (aba Estatísticas): zera tempo de leitura e sequência, sem mexer em livros, progresso,
  Estante, anotações e glossário.
- **Sequência de dias**: um dia conta a partir de 5 minutos de leitura. A biblioteca mostra "🔥 N dias seguidos" e a aba
  Estatísticas mostra sequência atual, melhor sequência, tempo de hoje, tempo total, livros com mais tempo e um
  calendário do mês com os dias lidos.
- **Seleção de texto**: funciona em **PDF**, EPUB, MOBI, DOCX e TXT. No PDF: **toque longo** numa palavra a seleciona
  (continuar arrastando estende), as **alças azuis** ajustam o começo e o fim e a barra oferece Copiar, Definir termo e
  Anotar. Funciona nos dois modos de leitura. Em DJVU e quadrinhos a página é imagem; PDF escaneado (foto de papel)
  também não tem texto.
- **Conforto visual** (menu ⋮ ou Ajustes), com efeito ao vivo enquanto se ajusta:
  - **Cor da página**: Claro, Sépia, Cinza suave, Escuro suave e Escuro. Vale também para PDF, DJVU e quadrinhos: nos
    temas claros o papel ganha a cor escolhida; nos escuros a luminosidade é invertida mantendo o matiz das imagens.
  - **Luz e filtro**: brilho próprio do app (pode ficar abaixo do mínimo do sistema), luz quente e sobreposição colorida
    opcional (cor e opacidade).
  - **Tipografia** (EPUB, MOBI, DOCX, TXT): fonte (original, serifada, sem serifa ou Atkinson Hyperlegible), espaçamento
    entre linhas e entre letras, margens, largura máxima da linha (~55 a 75 caracteres) e alinhamento.

- **Progresso salvo** por documento.
- **Anotações**: selecione um trecho e toque em "Anotar" (EPUB, DOCX, TXT); em PDF e CBZ, use 📝 → "Anotar esta página". A lista de anotações do livro leva direto ao trecho.
- **Busca no livro** (🔍) que ignora maiúsculas e acentos ("acao" encontra "Ação"), com lista de resultados.
- Ao chegar no fim, aparece **🏁 Concluir livro**, que move o livro para a Estante.

### Notas, Estatísticas e Ajustes

- **Notas**: todas as anotações, agrupadas por livro; tocar abre o livro no trecho anotado.
- **Estatísticas**: livros na biblioteca, em leitura, concluídos, anotações e contagem por formato.
- **Ajustes**: versão, espaço usado pelas cópias em cache (com botão para liberar) e licenças de código aberto.

### Motores do OpenReadEra (0.5.0)

MOBI/AZW/AZW3, DJVU e CBR em RAR são lidos pelos motores **EraMOBI**, **EraDjVu** e **EraComic** do projeto de
código aberto [OpenReadEra](https://readera.org/book-reader/OpenReadEra), os mesmos que o ReadEra usa. Nada foi
copiado do APK do ReadEra (que é um app fechado): os motores são compilados aqui, a partir das fontes abertas, que
estão em `app/src/main/cpp/openreadera`.

- São **programas separados**, empacotados como `lib<motor>.so`. O app inicia o programa e conversa com ele por dois
  pipes nomeados, com o protocolo binário do OpenReadEra (`OreEngine.kt`). Nenhum código deles é ligado ao app.
- O APK tem ~14,5 MB (quatro motores × três arquiteturas: arm64-v8a, armeabi-v7a, x86_64; o EraPDF é o maior; as fontes Liberation somam ~2,4 MB). No aparelho só os motores da arquitetura dele são instalados.
- Licenças (AGPL-3.0, GPL-2.0 e outras) e obrigações ao distribuir o APK: veja [`TERCEIROS.md`](TERCEIROS.md).

### Origem dos novos recursos

Os recursos da 0.4.0 vieram da Biblioteca do site RPG Hardcore (HTML/JavaScript), **reescritos em Kotlin**. Nada do código web nem das bibliotecas JavaScript/WASM dele (pdf.js, epub.js, JSZip, mammoth, libarchive, mobi-parser, djvu-rs) entrou no app. O app usa só o que o Android já oferece (`PdfRenderer`, `java.util.zip`, `XmlPullParser`, `WebView`), então não há licenças de terceiros a acompanhar. Ficaram de fora, por serem do site e não da biblioteca: XP ao concluir, cartões SRS, limite diário de quadrinhos e backup.

## Limitações desta etapa

- MOBI/AZW3 com DRM não abre. A primeira abertura de um MOBI demora um pouco mais (conversão para EPUB).
- DJVU não tem busca nem seleção de texto nesta versão (o motor oferece; falta ligar à interface).
- PDFs que citam fontes sem embuti-las (Times New Roman, Arial, Courier New, comuns em arquivos do Word) são desenhados
  com as fontes **Liberation**, que vêm no app e têm as mesmas medidas; fontes de outras famílias não embutidas usam a
  mais parecida das três.
- PDF protegido por senha ainda não abre. PDF escaneado não tem texto (precisaria de reconhecimento de texto).
- A seleção no PDF fica dentro de uma página (não atravessa para a seguinte).
- Se o motor EraPDF não iniciar num aparelho, o PDF abre pelo renderizador do Android, sem seleção nem busca.
- EPUB com DRM não abre. O livro é exibido com o estilo de leitura do app (cores e margens); scripts do livro são bloqueados.
- Arquivos TXT acima de 8 MB são exibidos só até esse limite.
- EPUB, CBZ e DOCX são copiados para o cache do app na primeira leitura (acesso aleatório ao ZIP). Se o Android limpar o cache, a cópia é refeita.
- Os gestos do PDF/CBZ e a exibição do EPUB no WebView **só podem ser validados num aparelho real**: o simulador dos testes não renderiza PDF, imagens nem páginas web.
- Os motores foram testados de verdade, mas no Linux (veja Testes). Rodar no Android depende de o aparelho permitir executar os programas instalados com o app, o que o Android permite para arquivos `lib*.so` extraídos; ainda assim, **teste num aparelho real** antes de distribuir.

## Testes

Os testes rodam na JVM, sem emulador (115 no total):

- Lógica pura: `FormatsTest`, `TextDecodingTest`, `TextChunkerTest`, `ReadingStatusTest`, `BookHelpersTest` (ordem natural, busca sem acentos, caminhos em ZIP, preparo do HTML do EPUB, prateleiras, blocos do DOCX).
- `ZipFormatsTest` (Robolectric): EPUB 2 e 3 (capítulos, sumário, capa), DOCX (títulos com estilos em português, listas, estilos, tabelas, caixas de texto), CBZ.
- `LibraryDatabaseTest` (Robolectric): migrações 0.2 → 0.4 e 0.3 → 0.4 sem perda de dados, anotações, Estante e prateleiras.
- `TextReaderTest`, `ReaderFormatsTest` (Robolectric): abre TXT, DOCX e EPUB no leitor, salva e restaura posição, busca, CBR em RAR com erro claro.
- `MainActivityTest` (Robolectric): biblioteca com prateleiras e Estante de madeira (placas que abrem e fecham, troca de capa pela galeria), abas Notas, Estatísticas e Ajustes.
- `ShelfPreviewTest`: gera imagens da Estante quando `SHELF_PREVIEW` aponta para uma pasta com capas de exemplo (sem a variável, é pulado).
- `IncomingBooksTest`: migração para o banco v6, "Abrir com" e "Compartilhar" oferecidos para os formatos certos (e
  não para TXT), livro repetido reaproveitado, liberar espaço mantendo na Estante, livro arquivado voltando com
  anotações e glossário, limpar estatísticas.
- `PageTextModelTest`, `PdfTextTest`: texto do PDF por caractere com posição (EraPDF real, com acentos), toque longo
  selecionando palavras nos dois modos, termo do glossário tocado no PDF e busca sem acentos.
- `ReadingLogicTest`, `GlossaryAndTimeTest`: sequência de dias, cronômetro com pausa por inatividade e virada de dia,
  migração para o banco v5, glossário (palavras inteiras, sem acentos, HTML), termo sublinhado e tocado no TXT (com
  medida de texto real), termos marcados no EPUB, tempo salvo no livro e no dia, sequência na biblioteca e estatísticas.
- `ComfortMathTest`, `ComfortTest`: cores dos temas (papel sépia, escuro mantendo o matiz), brilho abaixo do mínimo,
  CSS do EPUB, fonte servida de dentro do app, tema/fonte/espaçamento no TXT, estilo da régua, painel e zoom na rolagem.
- `ReadingModesTest`, `PageTurnTest`: toque duplo nas bordas vira a página (DJVU pelo motor real e TXT), no meio dá
  zoom; rolagem contínua lista todas as páginas; página inteira na tela; régua de leitura.
- `OreEnginesTest` e parte do `ReaderFormatsTest`: os **motores de verdade**, compilados para Linux a partir das mesmas
  fontes (`app/src/test/native/build_host_engines.py`), conversam com o app pelo mesmo protocolo: MOBI vira EPUB legível
  (inclusive com compressão HUFF/CDIC), DJVU é desenhado com as cores certas, páginas de um CBR RAR5 saem idênticas às
  originais, e o leitor abre MOBI, DJVU e CBR. Precisam de python3, gcc, g++, make, nasm e zlib; sem eles (ex.: no
  Windows) esses testes são pulados, exceto no CI, onde são obrigatórios.

```
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Os relatórios ficam em `app/build/reports/`.

## Requisitos para compilar

- Android Studio compatível com Android Gradle Plugin 8.7.3.
- JDK 17 ou superior.
- Android SDK Platform 35.
- Android NDK 27.0.12077973 (o Android Studio oferece instalar; no terminal: `sdkmanager "ndk;27.0.12077973"`).
- O Gradle é baixado automaticamente pelo Gradle Wrapper (`gradlew`, versão 8.14.3).
- O NDK 27 não roda no Windows 7. Nesse caso, compile pelo GitHub Actions (abaixo).

## Como compilar

1. Abra a pasta do projeto no Android Studio.
2. Aguarde a sincronização do Gradle e a instalação dos componentes solicitados.
3. Execute `Build > Build Bundle(s) / APK(s) > Build APK(s)`, ou no terminal: `./gradlew assembleDebug` (no Windows, `gradlew.bat assembleDebug`).
4. O APK de depuração será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Próximas etapas sugeridas

1. Testar em aparelho real: MOBI/AZW3, DJVU e CBR (motores), gestos do PDF/CBZ, EPUBs variados e arquivos do Google Drive.
2. Busca e seleção de texto no DJVU (o EraDjVu já oferece os comandos).
3. Exportar/importar backup da biblioteca e das anotações.
4. Ajustes: tema, tamanho de letra padrão, ordem da biblioteca.
5. Definir a licença do código do próprio Alexandria (veja `TERCEIROS.md`).

## Ícone

Gerado por `art/gerar_icones.py` a partir de `art/icone-original.jpg` (requer Python com Pillow): o farol é recortado, separado do fundo creme e montado em todas as densidades. O nome "Alexandria" da arte original ficou de fora do ícone, porque fica ilegível no tamanho do ícone e o Android já mostra o nome do app embaixo dele.

## Privacidade

A aplicação não contém analytics, anúncios, login ou comunicação de rede. Os documentos são selecionados pelo usuário e seus identificadores são armazenados localmente no banco SQLite do aplicativo. O app não pede permissão de internet: nem o leitor de EPUB nem os motores conseguem acessar a rede. O backup automático e a transferência entre aparelhos estão desligados (`allowBackup`, `fullBackupContent` e `dataExtractionRules`), porque as permissões de acesso aos arquivos não são restauradas em outro aparelho.

## Alternativa de compilação na nuvem (GitHub Actions)

O repositório inclui `.github/workflows/android.yml`. A cada pull request ou commit em `main`/`master` (ou manualmente, na aba **Actions**, em **Build Alexandria APK**), ele roda os testes, o lint e gera o APK. Ao concluir, baixe o artefato `Alexandria-debug-apk` na execução; os relatórios de testes e lint ficam no artefato `Alexandria-reports`. Isso permite compilar sem instalar Android Studio nem o NDK no Windows 7. O workflow instala o NDK e as ferramentas dos testes dos motores sozinho.
