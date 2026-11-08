# Alexandria — projeto Android

**Versão:** 0.4.0  
**Pacote:** `com.alexandria.reader`  
**Plataforma:** Android 6.0 ou superior (API 23+)  
**Idioma da interface:** Português (Brasil)

## O que esta versão contém

- Aplicativo Android nativo em Kotlin, sem dependências externas no APK.
- Ícone próprio (farol sobre livros), adaptativo e com versão monocromática para ícones temáticos (Android 13+).

### Biblioteca

- Importação de vários documentos de uma vez (Storage Access Framework), guardados no SQLite local.
- Cartões com **capa** (PDF, EPUB e CBZ), formato, progresso em texto e barra de progresso.
- **Lendo agora** e **Estante** (livros concluídos, recolhível), ordenados pelo que foi lido por último.
- **Prateleiras**: toque longo num livro → mover para prateleira; toque longo no nome da prateleira → renomear.
- Toque longo também permite marcar como concluído/não concluído e remover (apaga as anotações do livro).

### Leitores

| Formato | Como é exibido | Recursos |
|---|---|---|
| PDF | renderizador nativo do Android | zoom por pinça e toque duplo, deslizar para trocar de página, ir para página |
| CBZ | imagens do ZIP em ordem natural (pág. 2 antes da 10) | os mesmos do PDF |
| CBR | abre como CBZ quando é um ZIP disfarçado | CBR em RAR de verdade ainda não é suportado |
| EPUB 2 e 3 | WebView do sistema, capítulo por capítulo | sumário, busca, letra maior/menor, tema escuro |
| DOCX | texto com títulos, listas, negrito, itálico e sublinhado | busca, letra, tema escuro |
| TXT | blocos numa lista (fluido mesmo em arquivos grandes) | UTF-8/UTF-16/Windows-1252, busca, letra, tema escuro |

Em todos os formatos:

- **Progresso salvo** por documento.
- **Anotações**: selecione um trecho e toque em "Anotar" (EPUB, DOCX, TXT); em PDF e CBZ, use 📝 → "Anotar esta página". A lista de anotações do livro leva direto ao trecho.
- **Busca no livro** (🔍) que ignora maiúsculas e acentos ("acao" encontra "Ação"), com lista de resultados.
- Ao chegar no fim, aparece **🏁 Concluir livro**, que move o livro para a Estante.

### Notas e Estatísticas

- **Notas**: todas as anotações, agrupadas por livro; tocar abre o livro no trecho anotado.
- **Estatísticas**: livros na biblioteca, em leitura, concluídos, anotações e contagem por formato.
- Ajustes continua como área de demonstração.

### Origem dos novos recursos

Os recursos da 0.4.0 vieram da Biblioteca do site RPG Hardcore (HTML/JavaScript), **reescritos em Kotlin**. Nada do código web nem das bibliotecas JavaScript/WASM dele (pdf.js, epub.js, JSZip, mammoth, libarchive, mobi-parser, djvu-rs) entrou no app. O app usa só o que o Android já oferece (`PdfRenderer`, `java.util.zip`, `XmlPullParser`, `WebView`), então não há licenças de terceiros a acompanhar. Ficaram de fora, por serem do site e não da biblioteca: XP ao concluir, cartões SRS, limite diário de quadrinhos e backup.

## Limitações desta etapa

- MOBI/AZW3, DJVU e CBR em RAR podem ser importados, mas ainda **não têm leitor** (precisam de decodificadores próprios).
- PDF não tem busca nem seleção de texto: o renderizador nativo só entrega imagem. Anotações de PDF são por página.
- EPUB com DRM não abre. O livro é exibido com o estilo de leitura do app (cores e margens); scripts do livro são bloqueados.
- Arquivos TXT acima de 8 MB são exibidos só até esse limite.
- EPUB, CBZ e DOCX são copiados para o cache do app na primeira leitura (acesso aleatório ao ZIP). Se o Android limpar o cache, a cópia é refeita.
- Os gestos do PDF/CBZ e a exibição do EPUB no WebView **só podem ser validados num aparelho real**: o simulador dos testes não renderiza PDF, imagens nem páginas web.

## Testes

Os testes rodam na JVM, sem emulador (58 no total):

- Lógica pura: `FormatsTest`, `TextDecodingTest`, `TextChunkerTest`, `ReadingStatusTest`, `BookHelpersTest` (ordem natural, busca sem acentos, caminhos em ZIP, preparo do HTML do EPUB, prateleiras, blocos do DOCX).
- `ZipFormatsTest` (Robolectric): EPUB 2 e 3 (capítulos, sumário, capa), DOCX (títulos com estilos em português, listas, estilos, tabelas, caixas de texto), CBZ.
- `LibraryDatabaseTest` (Robolectric): migrações 0.2 → 0.4 e 0.3 → 0.4 sem perda de dados, anotações, Estante e prateleiras.
- `TextReaderTest`, `ReaderFormatsTest` (Robolectric): abre TXT, DOCX e EPUB no leitor, salva e restaura posição, busca, CBR em RAR com erro claro.
- `MainActivityTest` (Robolectric): biblioteca com prateleiras e Estante, abas Notas e Estatísticas.

```
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Os relatórios ficam em `app/build/reports/`.

## Requisitos para compilar

- Android Studio compatível com Android Gradle Plugin 8.7.3.
- JDK 17 ou superior.
- Android SDK Platform 35.
- O Gradle é baixado automaticamente pelo Gradle Wrapper (`gradlew`, versão 8.14.3).

## Como compilar

1. Abra a pasta do projeto no Android Studio.
2. Aguarde a sincronização do Gradle e a instalação dos componentes solicitados.
3. Execute `Build > Build Bundle(s) / APK(s) > Build APK(s)`, ou no terminal: `./gradlew assembleDebug` (no Windows, `gradlew.bat assembleDebug`).
4. O APK de depuração será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Próximas etapas sugeridas

1. Testar em aparelho real: gestos do PDF/CBZ, EPUBs variados (imagens, notas de rodapé, livros grandes), DOCX com tabelas e arquivos vindos do Google Drive.
2. Leitor MOBI (o formato antigo, PalmDOC, é viável sem bibliotecas; o AZW3/KF8 é maior).
3. CBR em RAR e DJVU (precisam de decodificador; avaliar licenças antes).
4. Exportar/importar backup da biblioteca e das anotações.
5. Ajustes: tema, tamanho de letra padrão, ordem da biblioteca.

## Ícone

Gerado por `art/gerar_icones.py` a partir de `art/icone-original.jpg` (requer Python com Pillow): o farol é recortado, separado do fundo creme e montado em todas as densidades. O nome "Alexandria" da arte original ficou de fora do ícone, porque fica ilegível no tamanho do ícone e o Android já mostra o nome do app embaixo dele.

## Privacidade

A aplicação não contém analytics, anúncios, login ou comunicação de rede. Os documentos são selecionados pelo usuário e seus identificadores são armazenados localmente no banco SQLite do aplicativo. O app não pede permissão de internet: nem o leitor de EPUB consegue acessar a rede. O backup automático e a transferência entre aparelhos estão desligados (`allowBackup`, `fullBackupContent` e `dataExtractionRules`), porque as permissões de acesso aos arquivos não são restauradas em outro aparelho.

## Alternativa de compilação na nuvem (GitHub Actions)

O repositório inclui `.github/workflows/android.yml`. A cada pull request ou commit em `main`/`master` (ou manualmente, na aba **Actions**, em **Build Alexandria APK**), ele roda os testes, o lint e gera o APK. Ao concluir, baixe o artefato `Alexandria-debug-apk` na execução; os relatórios de testes e lint ficam no artefato `Alexandria-reports`. Isso permite compilar sem instalar Android Studio no Windows 7.
