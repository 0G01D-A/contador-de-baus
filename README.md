# Contador de Baús

Aplicação para Windows que conta aberturas de baús no Fortnite pelo som. Mostra a contagem da sessão e o total guardado entre utilizações.

## Instalar e usar

1. Descarrega o instalador da [versão mais recente](https://github.com/0G01D-A/contador-de-baus/releases/latest). Executa-o e abre **Contador de Baus** no menu Iniciar. O instalador inclui o Java e pode ser usado sem instalar o Java à parte.
2. No Fortnite, encontra um baú e deixa-o fechado. O jogo deve usar os auscultadores ou colunas definidos como saída predefinida no Windows.
3. Na aplicação, escolhe **Gravar som de baú**. Depois de confirmares, tens 5 segundos para voltar ao jogo e 7 segundos de gravação. Abre um baú durante a gravação.
4. Volta à aplicação. Ajusta **Início** e **Fim** de forma a incluir o som da abertura com o mínimo de ruído de fundo. Confirma com **Ouvir trecho** e clica em **Guardar amostra**.
5. Abre outro baú para experimentar. A contagem começa ao guardar a amostra e ao abrir a aplicação nas sessões seguintes. Deixa a aplicação aberta enquanto jogas. Podes minimizá-la.

Se o som não for detetado, carrega uma vez em **F9** para somar um baú à sessão e ao total, mesmo com o Fortnite em primeiro plano. O atalho está ativo ao abrir a aplicação. Podes desmarcar **Atalho global F9 (+1)** para libertar a tecla, por exemplo se já a usas no jogo.

Se a aplicação contar um baú a mais, clica em **Remover 1 baú**. Isso retira um do total guardado e reduz a contagem da sessão, se estiver acima de zero. Podes corrigir o total mesmo depois de reabrir a aplicação.

Se o contador falhar aberturas, experimenta mover a sensibilidade para a esquerda. Se contar outros sons, move para a direita. Para reconhecer outro tipo de baú, grava outra amostra da mesma forma. A aplicação guarda as seis mais recentes. Baús abertos durante a calibração podem ser adicionados com **+1**.

A deteção usa o áudio reproduzido na saída predefinida do Windows. Outros sons nessa saída, como música, chat de voz e efeitos do jogo, podem interferir. As referências acústicas e o total são guardados localmente em `%LOCALAPPDATA%\ContadorDeBaus`. A gravação de calibração é mantida em memória durante a seleção.

## Abrir a partir do Java

Com o Java 25 instalado, descarrega o `contador.jar` da mesma página e abre o PowerShell na pasta onde o guardaste.

```powershell
java --enable-native-access=ALL-UNNAMED -jar .\contador.jar
```

## Compilar

É necessário um JDK 25. Para gerar o instalador, disponibiliza o WiX 3.14.1 no `PATH` ou passa a pasta que contém `candle.exe` e `light.exe`.

```powershell
.\build.ps1 -WixDirectory 'C:\ferramentas\wix'
```

`.\build.ps1 -Portable` gera uma pasta executável em `out`, sem WiX, e mostra o caminho no terminal.
