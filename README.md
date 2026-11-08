# Alexandria — projeto Android

**Versão:** 0.3.0  
**Pacote:** `com.alexandria.reader`  
**Plataforma:** Android 6.0 ou superior (API 23+)  
**Idioma da interface:** Português (Brasil)

## O que esta versão contém

- Aplicativo Android nativo em Kotlin, sem dependências externas no APK.
- Biblioteca local em SQLite, com importação de vários documentos de uma vez (Storage Access Framework).
- Biblioteca ordenada pelo que foi lido ou importado por último.
- **Leitor de PDF** (renderizador nativo do Android):
  - zoom por pinça (até 300%) e toque duplo para alternar entre 100% e 200%;
  - deslizar para os lados troca de página (sem zoom);
  - toque no número da página para ir direto a outra página.
- **Leitor de TXT**:
  - texto dividido em blocos e exibido numa lista, o que mantém a rolagem fluida mesmo em arquivos grandes;
  - detecção de codificação: UTF-8, UTF-16 (com BOM) ou Windows-1252;
  - tamanho da fonte ajustável e tema escuro (botão ◐).
- **Progresso de leitura salvo** por documento. Na biblioteca o PDF mostra "Página 12 de 300 · 4%" e o TXT mostra a porcentagem lida.
- Importação reconhece o formato pela extensão ou, na falta dela, pelo tipo MIME.
- Remoção de itens por toque longo, liberando a permissão persistida do arquivo.
- Layout compatível com Android 15 (barras do sistema não cobrem cabeçalho nem navegação).
- Notas, Estatísticas e Ajustes continuam como áreas de demonstração.

## Limitações desta etapa

- EPUB, MOBI, DJVU, DOCX, CBZ e CBR podem ser importados, mas ainda **não têm leitor**. Os cartões desses itens mostram "Leitor em breve".
- O leitor de PDF não tem busca. A memória de cada página renderizada é limitada (cerca de 8 milhões de pixels), então em páginas muito grandes o zoom máximo pode ser menor que 300%.
- Arquivos TXT acima de 8 MB são exibidos só até esse limite.
- Os gestos do PDF (pinça, toque duplo, deslizar) foram compilados e revisados, mas **só podem ser validados num aparelho real**: o simulador usado nos testes automatizados não renderiza PDF.

## Testes

Os testes rodam na JVM, sem emulador:

- `FormatsTest`, `TextDecodingTest`, `TextChunkerTest`, `ReadingStatusTest`: lógica pura.
- `LibraryDatabaseTest` (Robolectric): migração do banco da versão 0.2 para a 0.3 sem perda de dados, renomeação, ordem e progresso.
- `TextReaderTest` (Robolectric): abre um TXT grande, confere os blocos, salva a posição, reabre e confere que volta ao mesmo ponto.

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

1. Testar em aparelho real os gestos do PDF, PDFs grandes, PDFs com senha e arquivos vindos do Google Drive.
2. Integrar leitor EPUB.
3. Busca no TXT e no PDF.
4. Implementar anotação, destaques e marcadores (seção Notas).
5. Estatísticas de leitura a partir do progresso já salvo.
6. Adicionar os demais formatos com bibliotecas compatíveis e licenças verificadas.

## Privacidade

A aplicação não contém analytics, anúncios, login ou comunicação de rede. Os documentos são selecionados pelo usuário e seus identificadores são armazenados localmente no banco SQLite do aplicativo. O backup automático e a transferência entre aparelhos estão desligados (`allowBackup`, `fullBackupContent` e `dataExtractionRules`), porque as permissões de acesso aos arquivos não são restauradas em outro aparelho.

## Alternativa de compilação na nuvem (GitHub Actions)

O repositório inclui `.github/workflows/android.yml`. A cada pull request ou commit em `main`/`master` (ou manualmente, na aba **Actions**, em **Build Alexandria APK**), ele roda os testes, o lint e gera o APK. Ao concluir, baixe o artefato `Alexandria-debug-apk` na execução; os relatórios de testes e lint ficam no artefato `Alexandria-reports`. Isso permite compilar sem instalar Android Studio no Windows 7.
