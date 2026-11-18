import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;

@SuppressWarnings("serial")
final class RoomPanel extends JPanel implements AutoCloseable {
    private final JLabel total = new JLabel("Sem sala");
    private final JButton settings = new JButton("Contagem em grupo");
    private SharedRoom room;
    private long generation;

    RoomPanel() {
        super(new FlowLayout(FlowLayout.LEFT, 5, 0));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        add(settings);
        add(total);
        settings.addActionListener(event -> manage());
    }

    void adjust(int amount) {
        if (room != null) {
            room.adjust(amount);
        }
    }

    private void manage() {
        if (room != null) {
            var invitation = new JTextField(room.invitation(), 44);
            invitation.setEditable(false);
            var options = new Object[]{"Copiar convite", "Sair da sala", "Fechar"};
            int choice = JOptionPane.showOptionDialog(this, invitation, "Sala em grupo",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[2]);
            if (choice == 0) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new StringSelection(invitation.getText()), null);
            } else if (choice == 1) {
                close();
            }
            return;
        }
        var options = new Object[]{"Criar sala", "Entrar com convite", "Cancelar"};
        int choice = JOptionPane.showOptionDialog(this,
                "Cria uma sala ou entra com o convite de alguém na mesma rede ou VPN.",
                "Contagem em grupo", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, options, options[2]);
        long token = ++generation;
        try {
            if (choice == 0) {
                String address = chooseAddress();
                if (address != null) {
                    room = SharedRoom.host(address, SharedRoom.PORT, view -> update(token, view));
                    total.setText("A criar sala…");
                    manage();
                }
            } else if (choice == 1) {
                String invitation = JOptionPane.showInputDialog(this, "Cola o convite da sala.");
                if (invitation != null) {
                    room = SharedRoom.join(invitation, view -> update(token, view));
                    total.setText("A ligar à sala…");
                }
            }
        } catch (IOException | IllegalArgumentException error) {
            JOptionPane.showMessageDialog(this, "Não foi possível abrir a sala. " + error.getMessage(),
                    "Contagem em grupo", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String chooseAddress() throws SocketException {
        var addresses = new ArrayList<String>();
        for (NetworkInterface network : NetworkInterface.networkInterfaces().toList()) {
            if (network.isUp() && !network.isLoopback()) {
                for (InetAddress address : network.inetAddresses().toList()) {
                    if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        }
        if (addresses.isEmpty()) {
            throw new SocketException("Liga este PC à rede antes de criar a sala.");
        }
        if (addresses.size() == 1) {
            return addresses.getFirst();
        }
        return (String) JOptionPane.showInputDialog(this, "Escolhe o endereço da rede ou VPN que vais partilhar.",
                "Criar sala", JOptionPane.PLAIN_MESSAGE, null, addresses.toArray(), addresses.getFirst());
    }

    private void update(long token, SharedRoom.View view) {
        SwingUtilities.invokeLater(() -> {
            if (token == generation && room != null) {
                total.setText(view.online() ? "Total do grupo: " + view.total()
                        : "Sala sem ligação · último total: " + view.total());
            }
        });
    }

    @Override
    public void close() {
        generation++;
        if (room != null) {
            room.close();
            room = null;
        }
        total.setText("Sem sala");
    }
}
