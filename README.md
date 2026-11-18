# Contador de Baús

Aplicação para Windows que conta aberturas de baús no Fortnite pelo som. Mostra a contagem da sessão e o total guardado entre utilizações.

## Instalar noutro PC

- O instalador é para **Windows de 64 bits (x64)**. Podes confirmar o **Tipo de sistema** em **Definições → Sistema → Acerca de**.
- O repositório é privado. Entra no GitHub com uma conta que tenha acesso e abre a [versão mais recente](https://github.com/0G01D-A/contador-de-baus/releases/latest).
- Para testar a deteção, precisas do Fortnite a reproduzir som na saída predefinida do Windows, como os auscultadores ou as colunas.

1. Na secção **Assets** da versão, descarrega `Contador-de-Baus-<versão>.exe`.
2. Abre o ficheiro e conclui a instalação. O instalador inclui o Java.
3. Abre **Contador de Baus** pelo menu Iniciar. Se o instalador ou a aplicação fechar ao abrir, segue a opção JAR abaixo.

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

1. No Fortnite, encontra um baú e deixa-o fechado. Confirma que o jogo usa a saída predefinida do Windows.
2. Na aplicação, escolhe **Gravar som de baú**. Depois de confirmares, tens 5 segundos para voltar ao jogo e 7 segundos de gravação. Abre um baú durante a gravação.
3. Volta à aplicação. Ajusta **Início** e **Fim** de forma a incluir o som da abertura com o mínimo de ruído de fundo. Confirma com **Ouvir trecho** e clica em **Guardar amostra**.
4. Abre outro baú para experimentar. A contagem começa ao guardar a amostra e ao abrir a aplicação nas sessões seguintes. Deixa a aplicação aberta enquanto jogas. Podes minimizá-la.

Se a barra **Nível do som do Windows** ficar parada, confirma que o Fortnite está a reproduzir som na saída predefinida. Se mudares de auscultadores ou colunas enquanto o contador está aberto, pausa e volta a iniciar a contagem para ligar à nova saída.

Se a barra mexer mas a contagem ficar igual, pausa e volta a iniciar a contagem para repor a **Melhor semelhança**. Abre um baú e compara o valor com o **Mínimo para contar**. Se ficar um pouco abaixo, move a sensibilidade para a esquerda e testa noutra abertura. Se a semelhança for baixa, usa **Ouvir trecho** ao gravar uma nova amostra e recorta o som de abertura com menos música, passos ou ataques. Depois, testa também alguns segundos sem abrir baús para verificar se o contador soma por engano.

Se o som não for detetado, carrega uma vez em **F9** para somar um baú à sessão e ao total, mesmo com o Fortnite em primeiro plano. O atalho está ativo ao abrir a aplicação. Podes desmarcar **Atalho global F9 (+1)** para libertar a tecla, por exemplo se já a usas no jogo.

Se a aplicação contar um baú a mais, clica em **Remover 1 baú**. Isso retira um do total guardado e reduz a contagem da sessão, se estiver acima de zero. Podes corrigir o total mesmo depois de reabrir a aplicação.

Se o contador falhar aberturas, experimenta mover a sensibilidade para a esquerda. Se contar outros sons, move para a direita. Para reconhecer outro tipo de baú, grava outra amostra da mesma forma. A aplicação guarda as seis mais recentes. Baús abertos durante a calibração podem ser adicionados com **+1**.

A deteção usa o áudio reproduzido na saída predefinida do Windows. Outros sons nessa saída, como música, chat de voz e efeitos do jogo, podem interferir. As referências acústicas e o total são guardados localmente em `%LOCALAPPDATA%\ContadorDeBaus`. A gravação de calibração é mantida em memória durante a seleção.

## Compilar

É necessário um JDK 25. Para gerar o instalador, disponibiliza o WiX 3.14.1 no `PATH` ou passa a pasta que contém `candle.exe` e `light.exe`.

```powershell
.\build.ps1 -WixDirectory 'C:\ferramentas\wix'
```

`.\build.ps1 -Portable` gera uma pasta executável em `out`, sem WiX, e mostra o caminho no terminal.
