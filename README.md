# Contador de Baús

Contador manual para Windows. Cria uma sala para somar os baús abertos por ti e pelos teus amigos. A janela mostra o **Total do grupo** e **A minha contribuição**.

## Instalar ou atualizar

O instalador é para **Windows x64** e inclui o Java. Entra no GitHub com uma conta que tenha acesso ao repositório privado e descarrega o instalador da [versão mais recente](https://github.com/0G01D-A/contador-de-baus/releases/latest).

Se já tens o contador, fecha-o antes de executar o instalador. Podes instalar por cima e voltar a abrir **Contador de Baus** pelo menu Iniciar.

Se o Windows bloquear o executável, podes usar o `contador.jar` da mesma página com [Java 25](https://adoptium.net/temurin/releases/?os=windows&version=25). Abre o PowerShell na pasta onde guardaste o JAR e corre:

```powershell
java --enable-native-access=ALL-UNNAMED -jar .\contador.jar
```

## Criar uma sala

1. No PC do anfitrião, escolhe **Contagem em grupo → Criar sala**. Se aparecerem vários IPs, escolhe o da rede que vais usar.
2. Clica em **Copiar convite** e partilha-o em privado.
3. Os amigos abrem **Contagem em grupo → Entrar com convite** e colam o convite. Podem usar os botões ou os atalhos globais para atualizar a contagem.

Na mesma rede local, o convite funciona diretamente. Cada jogador pode retirar até ao número de baús que adicionou. A contribuição começa em zero quando entras numa sala diferente.

### Retomar a última sala

Quando fechas a aplicação, o último total do grupo e a tua contribuição ficam guardados neste PC. Ao abrir novamente, o contador tenta retomar a mesma sala. Se usaste **Sair da sala**, podes voltar em **Contagem em grupo → Retomar última sala**. Criar ou entrar noutra sala substitui a última guardada.

O anfitrião precisa de reabrir a aplicação para os outros jogadores voltarem a sincronizar. Se estiver desligado, os convidados veem o último total recebido. Se o endereço local do anfitrião ou o endereço público do túnel TCP mudar, a ligação anterior pode deixar de funcionar.

A gravação da sala começa na versão **3.2.0**. As versões anteriores não guardavam as salas após fechar.

### Jogar pela internet sem VPN

Só o anfitrião precisa de instalar o **ngrok**. Os outros jogadores entram pelo Contador de Baús.

1. Instala o ngrok pela Microsoft Store ou corre o comando abaixo no PowerShell. [Instalação para Windows](https://ngrok.com/download/windows)

   ```powershell
   winget install ngrok -s msstore
   ```

2. Cria uma conta no ngrok e copia o teu *authtoken* no painel. Abre um novo PowerShell e substitui `O_TEU_TOKEN` pelo teu token. Para ativar túneis TCP no plano gratuito, o ngrok pede um cartão válido. [Configurar o token](https://ngrok.com/docs/start) · [Requisitos do túnel TCP](https://ngrok.com/docs/gateway/endpoints/tcp)

   ```powershell
   ngrok config add-authtoken "O_TEU_TOKEN"
   ```

3. Cria a sala no contador e copia o convite. Usa o **IP e a porta desse convite** no comando. Por exemplo, se o convite começar por `baus://192.168.1.20:47855/`, corre:

   ```powershell
   ngrok tcp 192.168.1.20:47855
   ```

4. Copia o endereço público que o ngrok mostrar. No convite, troca apenas o IP e a porta por esse endereço. Mantém `baus://` e a chave original depois da última barra.

   ```text
   Convite local:   baus://192.168.1.20:47855/CHAVE_DA_SALA
   Convite público: baus://1.tcp.ngrok.io:12345/CHAVE_DA_SALA
   ```

5. Envia o convite alterado em privado. O teu colega cola-o em **Contagem em grupo → Entrar com convite**. Mantém o contador e o ngrok abertos. Para testar, usa **Adicionar 1** e confirma que o **Total do grupo** sobe nos dois PCs. Guarda o *authtoken* do ngrok para ti.

## Escolher os atalhos

Na janela principal, clica em **Alterar teclas**. Na linha **Adicionar baú** ou **Retirar baú**, clica em **Premir tecla** e carrega na tecla que queres, por exemplo **Y**. Clica em **Guardar**. Também podes escolher pelas listas ou combinar a tecla com **Ctrl**, **Alt** e **Shift**. Estão disponíveis letras, números, teclas numéricas e **F1–F11**.

Os atalhos iniciais são **F9** para adicionar e **F8** para retirar. Os atalhos funcionam com a janela minimizada enquanto estás numa sala. Se o Windows ou outra aplicação já tiver registado uma combinação, o contador avisa e podes escolher outra. As escolhas ficam guardadas neste PC em `%LOCALAPPDATA%\ContadorDeBaus\atalhos.properties`.

## Compilar

É necessário um JDK 25. Para gerar o instalador, disponibiliza o WiX 3.14.1 no `PATH` ou indica a pasta que contém `candle.exe` e `light.exe`.

```powershell
.\build.ps1 -WixDirectory 'C:\ferramentas\wix'
```

`.\build.ps1 -Portable` gera uma pasta executável em `out`, sem WiX, e mostra o caminho no terminal.
