# Alexandria — projeto Android

**Versão:** 0.2.0  
**Pacote:** `com.alexandria.reader`  
**Plataforma:** Android 6.0 ou superior (API 23+)  
**Idioma da interface:** Português (Brasil)

## O que esta versão contém

- Aplicativo Android nativo em Kotlin, sem dependências externas.
- Biblioteca local em SQLite, com importação de vários documentos de uma vez (Storage Access Framework).
- **Leitor de PDF** (renderizador nativo do Android), com navegação por página.
- **Leitor de TXT**, com detecção de codificação (UTF-8 ou Windows-1252) e ajuste do tamanho da fonte.
- **Progresso de leitura salvo** por documento e exibido na biblioteca.
- Importação reconhece o formato pela extensão ou, na falta dela, pelo tipo MIME.
- Remoção de itens por toque longo, liberando a permissão persistida do arquivo.
- Layout compatível com Android 15 (barras do sistema não cobrem cabeçalho nem navegação).
- Notas, Estatísticas e Ajustes continuam como áreas de demonstração.

## Limitações desta etapa

- EPUB, MOBI, DJVU, DOCX, CBZ e CBR podem ser importados, mas ainda **não têm leitor**. Os cartões desses itens mostram "Leitor em breve".
- O leitor de PDF não tem zoom nem busca; mostra uma página por vez na largura da tela.
- Arquivos TXT acima de 3 MB são exibidos só até esse limite.
- Não há testes automatizados.
- **Esta versão não foi compilada nem executada pelo autor das alterações** (o ambiente não tem Android SDK nem acesso ao Gradle/Maven). O código foi revisado à mão. O primeiro build no GitHub Actions é a verificação real; se falhar, o log aponta a linha.

## Requisitos para compilar

- Android Studio compatível com Android Gradle Plugin 8.7.3.
- JDK 17 ou superior.
- Android SDK Platform 35.
- Gradle 8.9 (o Android Studio pode baixar a distribuição e as dependências configuradas).

## Como compilar

1. Extraia o ZIP.
2. Abra a pasta `Alexandria` no Android Studio.
3. Aguarde a sincronização do Gradle e a instalação dos componentes solicitados.
4. Execute `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
5. O APK de depuração será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Próximas etapas sugeridas

1. Testar em aparelho real PDFs grandes, PDFs com senha e arquivos vindos do Google Drive.
2. Testes unitários para `Formats` e para a migração do banco.
3. Zoom no PDF e paginação do TXT (hoje o texto inteiro vira um único `TextView`).
4. Integrar leitor EPUB.
5. Implementar anotação, destaques e marcadores.
6. Adicionar os demais formatos com bibliotecas compatíveis e licenças verificadas.

## Privacidade

A aplicação não contém analytics, anúncios, login ou comunicação de rede. Os documentos são selecionados pelo usuário e seus identificadores são armazenados localmente no banco SQLite do aplicativo. O backup automático do Android está desligado, porque as permissões de acesso aos arquivos não são restauradas em outro aparelho.

## Alternativa de compilação na nuvem (GitHub Actions)

O repositório inclui `.github/workflows/android.yml`. Depois de enviar o projeto para um repositório GitHub, abra a aba **Actions**, execute **Build Alexandria APK** (ou envie um commit). Ao concluir, baixe o artefato `Alexandria-debug-apk` na execução. Isso permite compilar sem instalar Android Studio no Windows 7. A execução no GitHub exige uma conta e um repositório acessível.
