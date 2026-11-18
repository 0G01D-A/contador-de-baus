# Contador de Baús

Aplicação para Windows que conta aberturas de baús no Fortnite por som, imagem ou ambos. Mostra a contagem pessoal e pode somar as contagens de vários jogadores numa sala.

## Instalar noutro PC

- O instalador é para **Windows de 64 bits (x64)**. Podes confirmar o **Tipo de sistema** em **Definições → Sistema → Acerca de**.
- O repositório é privado. Entra no GitHub com uma conta que tenha acesso e abre a [versão mais recente](https://github.com/0G01D-A/contador-de-baus/releases/latest).
- Para testar a deteção, precisas do Fortnite a reproduzir som na saída predefinida do Windows, como os auscultadores ou as colunas.

1. Na secção **Assets** da versão, descarrega `Contador-de-Baus-<versão>.exe`.
2. Abre o ficheiro e conclui a instalação. O instalador inclui o Java.
3. Abre **Contador de Baus** pelo menu Iniciar. Se o instalador ou a aplicação fechar ao abrir, segue a opção JAR abaixo.

### Atualizar uma instalação existente

1. Fecha o **Contador de Baus**, mesmo que esteja minimizado.
2. Na mesma conta de Windows em que instalaste o contador, descarrega e executa o instalador `.exe` da [versão mais recente](https://github.com/0G01D-A/contador-de-baus/releases/latest). O instalador substitui a versão anterior sem precisares de a desinstalar.
3. Abre novamente **Contador de Baus** pelo menu Iniciar. O total guardado e as amostras ficam na mesma pasta de dados. A contagem da sessão começa a zero ao reabrir a aplicação.

Se usavas o `contador.jar`, fecha a aplicação, substitui o JAR pelo da versão mais recente e executa o mesmo comando de arranque. O total e as amostras também ficam disponíveis nessa opção.

### Abrir pelo JAR se o EXE fechar

O instalador e o executável da aplicação estão sem assinatura digital. Algumas políticas do Windows podem bloquear o seu arranque. Também podes abrir a aplicação pelo JAR com Java 25.

1. Na secção **Assets** da [versão mais recente](https://github.com/0G01D-A/contador-de-baus/releases/latest), descarrega `contador.jar`.
2. Se ainda não tens o Java 25, instala o **Temurin 25** para Windows. Escolhe o instalador `.msi` da arquitetura do teu PC e mantém a opção de adicionar o Java ao `PATH`. [Descarregar o Temurin 25](https://adoptium.net/temurin/releases/?os=windows&version=25)
3. No Explorador de Ficheiros, abre a pasta onde guardaste o JAR. Escreve `powershell` na barra de endereço e carrega em **Enter**.
4. Corre os comandos abaixo. O primeiro deve mostrar a versão 25 do Java. O segundo abre o contador e mantém eventuais erros visíveis no terminal.

```powershell
java -version
java --enable-native-access=ALL-UNNAMED -jar .\contador.jar
```

Se o terminal disser que `java` não é reconhecido, fecha-o e volta a abri-lo depois de instalar o Java. Se mostrar uma versão anterior à 25, confirma que o Java 25 está no `PATH`. Se o comando não encontrar o JAR, confirma que o PowerShell está na pasta onde o descarregaste e que o ficheiro se chama `contador.jar`.

## Calibrar e testar no Fortnite

Escolhe em **Deteção** o modo **Som**, **Imagem** ou **Som e imagem**. No modo combinado, basta que um dos métodos reconheça a abertura. Quando os dois métodos detetam uma abertura com até 1,25 segundos de diferença, a aplicação tenta juntar as deteções num só baú. Dois baús diferentes abertos quase ao mesmo tempo podem ficar emparelhados. Calibra ambos os métodos antes de usar esse modo.

### Deteção por imagem

1. Usa o Fortnite em **janela sem bordas** no monitor principal.
2. Clica em **Calibrar imagem**, volta ao jogo em cinco segundos e abre um baú nos oito segundos que se seguem.
3. Percorre as imagens com o seletor. Escolhe uma em que haja um aviso ou símbolo visual exclusivo da abertura. Arrasta um retângulo pequeno à volta desse sinal e clica em **Guardar zona**.
4. Escolhe **Imagem** ou **Som e imagem**, inicia a contagem e abre outro baú. A barra **Semelhança da imagem** mostra a comparação com a zona guardada.

A zona tem de ficar no mesmo sítio do ecrã. Volta a calibrar se mudares a resolução ou o tamanho da interface. Se a abertura não mostrar um sinal visual reconhecível, usa o som ou os atalhos. Durante a calibração, as imagens do ecrã ficam em memória. A aplicação guarda apenas a zona e o padrão usado para comparar.

### Deteção por som

1. No Fortnite, encontra um baú e deixa-o fechado. Confirma que o jogo usa a saída predefinida do Windows.
2. Na aplicação, escolhe **Gravar som de baú**. Depois de confirmares, tens 5 segundos para voltar ao jogo e 7 segundos de gravação. Abre um baú durante a gravação.
3. Volta à aplicação. Ajusta **Início** e **Fim** de forma a incluir o som da abertura com o mínimo de ruído de fundo. Confirma com **Ouvir trecho** e clica em **Guardar amostra**.
4. Abre outro baú para experimentar. A contagem começa ao guardar a amostra e ao abrir a aplicação nas sessões seguintes. Deixa a aplicação aberta enquanto jogas. Podes minimizá-la.

Se a barra **Nível do som do Windows** ficar parada, confirma que o Fortnite está a reproduzir som na saída predefinida. Se mudares de auscultadores ou colunas enquanto o contador está aberto, pausa e volta a iniciar a contagem para ligar à nova saída.

**Semelhança agora** compara o áudio recebido com a amostra guardada mais parecida e atualiza-se enquanto jogas. Ao abrir um baú com um som parecido com a amostra, a percentagem tende a subir e a descer a seguir. O volume, outros sons e diferenças entre baús podem fazer com que a abertura fique abaixo de 100% ou que passos tenham alguma semelhança. **Melhor semelhança** conserva o pico desde que iniciaste a contagem.

Se a barra mexer mas a contagem ficar igual, pausa e volta a iniciar a contagem para repor a **Melhor semelhança**. Abre um baú e compara o valor com o **Mínimo para contar**. Se ficar um pouco abaixo, move a sensibilidade para a esquerda e testa noutra abertura. Se a semelhança for baixa, usa **Ouvir trecho** ao gravar uma nova amostra e recorta o som de abertura com menos música, passos ou ataques. Depois, testa também alguns segundos sem abrir baús para verificar se o contador soma por engano.

Usa **F9** para adicionar um baú e **F8** para retirar um, mesmo com o Fortnite em primeiro plano. Os atalhos estão ativos ao abrir a aplicação. Podes desmarcá-los individualmente para libertar as teclas.

Se a aplicação contar um baú a mais, clica em **Remover 1 baú**. Isso retira um do total guardado e reduz a contagem da sessão, se estiver acima de zero. Podes corrigir o total mesmo depois de reabrir a aplicação.

Para começar do zero, clica em **Repor contagem** por baixo de **Total guardado**. Confirma na janela que aparece para repor a sessão e o total. As amostras de som ficam guardadas.

Se o contador falhar aberturas, experimenta mover a sensibilidade para a esquerda. Se contar outros sons, move para a direita. Para reconhecer outro tipo de baú, grava outra amostra da mesma forma. A aplicação guarda as seis mais recentes. Baús abertos durante a calibração podem ser adicionados com **+1**.

### Confirmar que som está a ser usado

- **Rever amostras** lista os trechos guardados. Seleciona um e clica em **Ouvir amostra**, **Editar trecho** ou **Remover amostra**.
- Em **Editar trecho**, ajusta **Início** e **Fim** e clica em **Guardar amostra**. As novas gravações permitem alargar o recorte dentro do áudio original. Nas amostras da versão 1.4.0, só podes editar dentro do trecho que ficou guardado. As amostras anteriores à 1.4.0 podem ser removidas, mas precisas de as gravar novamente para as ouvir ou editar.
- Em **Remover amostra**, confirma a remoção. A amostra deixa de ser usada na deteção e a contagem de baús fica igual. Se removeres a última, grava outra para retomar a deteção automática.
- **Ouvir áudio recebido** capta os próximos cinco segundos da saída predefinida do Windows. Reproduz som no Fortnite enquanto a captura decorre e depois clica em **Reproduzir gravação**. Esta gravação é temporária. A aplicação pausa a contagem durante o teste e retoma-a quando fechas a janela, se já estava ativa.

A deteção usa o áudio reproduzido na saída predefinida do Windows. Outros sons nessa saída, como música, chat de voz e efeitos do jogo, podem interferir. O total, as referências acústicas, as gravações de calibração e os respetivos recortes ficam guardados localmente em `%LOCALAPPDATA%\ContadorDeBaus`.

## Contagem em grupo

1. Cada jogador abre a versão atual do contador no seu PC. Os PCs devem conseguir comunicar pela **mesma rede local** ou por uma **VPN partilhada**.
2. O anfitrião escolhe **Contagem em grupo → Criar sala**. Se aparecerem vários endereços, escolhe o da rede ou VPN que os outros jogadores usam. Clica em **Copiar convite** e envia-o aos jogadores que queres convidar.
3. Os outros jogadores escolhem **Contagem em grupo → Entrar com convite** e colam o convite. O **Total do grupo** soma as aberturas registadas na sala a partir desse momento, mantendo também os totais pessoais.
4. As deteções automáticas e o **F9** somam um à contribuição do jogador. O **F8** e **Remover 1 baú** corrigem a contribuição desse jogador até zero. **Repor contagem** só repõe os contadores pessoais.

O anfitrião precisa de manter o contador aberto para a sala continuar. As salas são temporárias. Se a ligação falhar, confirma o endereço do convite, a rede e as permissões no Windows para ligações à porta TCP 47855 na rede privada. A aplicação envia contagens cifradas entre os PCs. O áudio e as imagens ficam em cada PC.

## Compilar

É necessário um JDK 25. Para gerar o instalador, disponibiliza o WiX 3.14.1 no `PATH` ou passa a pasta que contém `candle.exe` e `light.exe`.

```powershell
.\build.ps1 -WixDirectory 'C:\ferramentas\wix'
```

`.\build.ps1 -Portable` gera uma pasta executável em `out`, sem WiX, e mostra o caminho no terminal.
