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

Na mesma rede local, o convite funciona diretamente. A sala é temporária e o anfitrião precisa de manter o contador aberto. Cada jogador pode retirar até ao número de baús que adicionou. A contribuição começa em zero ao entrar numa sala.

### Jogar pela internet sem VPN

Para amigos noutra rede, configura um túnel TCP no PC do anfitrião, por exemplo com o [ngrok](https://ngrok.com/docs/gateway/endpoints/tcp). Usa o IP local mostrado no convite e a porta `47855`. Se o teu IP no convite for `192.168.1.20`, corre:

```powershell
ngrok tcp 192.168.1.20:47855
```

Se o serviço mostrar `tcp://1.tcp.ngrok.io:12345`, o convite para o teu amigo fica no formato `baus://1.tcp.ngrok.io:12345/CHAVE_DA_SALA`. Mantém a chave original depois da última barra. Só o anfitrião precisa de manter o túnel aberto.

## Escolher os atalhos

Abre **Definições → Configurar atalhos** e escolhe uma tecla para **Adicionar baú** e outra para **Retirar baú**. Podes combinar as teclas com **Ctrl**, **Alt** e **Shift**.

Os atalhos iniciais são **F9** para adicionar e **F8** para retirar. Os atalhos funcionam com a janela minimizada enquanto estás numa sala. Se o Windows ou outra aplicação já tiver registado uma combinação, o contador avisa e podes escolher outra. As escolhas ficam guardadas neste PC em `%LOCALAPPDATA%\ContadorDeBaus\atalhos.properties`.

## Compilar

É necessário um JDK 25. Para gerar o instalador, disponibiliza o WiX 3.14.1 no `PATH` ou indica a pasta que contém `candle.exe` e `light.exe`.

```powershell
.\build.ps1 -WixDirectory 'C:\ferramentas\wix'
```

`.\build.ps1 -Portable` gera uma pasta executável em `out`, sem WiX, e mostra o caminho no terminal.
