# Novidades do Alexandria

Cada seção vira o texto da Release no GitHub e aparece no aviso de atualização do app.
A versão mais nova fica em cima.

## 0.24.1
- Corrige o app fechando ao abrir para quem já tinha livros antes das abas (o banco de dados não recebia a coluna nova das abas).
- Se uma tela der erro, o app mostra o problema e o botão "Procurar atualização" em vez de fechar.

## 0.24.0
- Assistir nas abas de Filmes e Cursos: toque num vídeo e ele toca enquanto baixa do grupo (streaming), sem esperar o arquivo inteiro. Dá para pular para qualquer ponto.
- Continuar assistindo: o ponto onde você parou fica salvo no grupo da aba; ao abrir de novo (no celular ou no computador), o vídeo continua de lá, com a opção de voltar ao início. A lista mostra "Parou em…" e "✓ Assistido".
- No celular, o player gira com o aparelho e toca em tela cheia.

## 0.23.0
- Abas na tela inicial: além de Livros, crie abas de Livros, Filmes ou Cursos (o "＋" na fileira de abas). Cada aba pode ter o seu grupo privado do Telegram: toque em ⋯ ao lado do nome da aba → Grupo da nuvem.
- Abas de livros separam os livros (por exemplo, HQs à parte): o que você importa entra na aba aberta, e "Mover para outra aba" troca de lugar. A posição de leitura vai para o grupo da aba do livro.
- Abas de filmes e cursos já mostram os vídeos do grupo (nome, duração, tamanho, quem enviou). Assistir com streaming chega na próxima atualização.
- O grupo da nuvem agora é escolhido em cada aba (antes ficava em Ajustes); o grupo que você já usava passou para a aba Livros.

## 0.22.2
- Posição de leitura na nuvem do Telegram mais confiável: é gravada ao sair do livro mesmo quando a conexão demorou a responder na abertura; a posição recém-gravada é encontrada logo (sem esperar a busca do Telegram) e as dos outros livros não se perdem; um pedido sem resposta não trava mais a nuvem.
- Ajustes → Nuvem pelo Telegram mostra quando a posição foi gravada pela última vez, ou o erro, se não foi.

## 0.22.1
- No grupo da nuvem, qualquer arquivo de livro aparece em "Na nuvem", mesmo sem #alexandria_livro na legenda: livros mandados por contas que não usam o Alexandria e livros encaminhados de outras conversas também entram. Nas Mensagens salvas, a marca continua sendo necessária.

## 0.22.0
- Nuvem num grupo privado do Telegram: várias contas suas usam os mesmos livros e a mesma posição de leitura. Em Ajustes → Nuvem pelo Telegram → "Usar um grupo privado…": crie o grupo, entre com o link de convite ou escolha um grupo que você já tem.
- "Trazer livros das Mensagens salvas" copia para o grupo os livros e posições que já estavam na nuvem.
- Livros mandados pelo próprio Telegram também entram na nuvem quando a legenda tem #alexandria_livro.
- Na lista "Na nuvem" de um grupo aparece quem enviou cada livro.

## 0.21.1
- Primeira versão entregue pela atualização do próprio app. Se você está lendo isto no aviso do Alexandria, a atualização automática está funcionando.

## 0.21.0
- Atualização pelo próprio app: uma vez por dia o Alexandria procura versão nova (dá para desligar em Ajustes → Atualizações), baixa e abre o instalador do Android.
- Ajustes → Atualizações: versão instalada, última verificação e o botão "Procurar atualização agora".

## 0.20.1
- Corrige o envio para a nuvem de livros acima de 20 MB.

## 0.20.0
- Nuvem pelo Telegram: livros e posição de leitura viajam pelas suas Mensagens salvas.
