import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.function.Consumer;

@SuppressWarnings("serial")
final class RoomPanel extends JPanel implements AutoCloseable {
    private final JLabel total = new JLabel("—", SwingConstants.CENTER);
    private final JLabel own = new JLabel("—", SwingConstants.CENTER);
    private final JLabel status = new JLabel("Sem sala", SwingConstants.CENTER);
    private final JButton settings = new JButton("Contagem em grupo");
    private final Consumer<Boolean> membershipChanged;
    private SharedRoom room;
    private long generation;

    RoomPanel(Consumer<Boolean> membershipChanged) {
        super(new BorderLayout(0, 12));
        this.membershipChanged = membershipChanged;
        setOpaque(false);
        var counts = new JPanel(new GridLayout(1, 2, 10, 0));
        counts.setOpaque(false);
        counts.add(card("Total do grupo", total));
        counts.add(card("A minha contribuição", own));
        add(counts, BorderLayout.CENTER);
        var actions = new JPanel(new BorderLayout(0, 6));
        actions.setOpaque(false);
        actions.add(status, BorderLayout.NORTH);
        actions.add(settings, BorderLayout.SOUTH);
        add(actions, BorderLayout.SOUTH);
        settings.addActionListener(event -> manage());
    }

    private static JPanel card(String caption, JLabel value) {
        var panel = new JPanel(new BorderLayout(0, 5));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(16, 10, 16, 10));
        panel.add(new JLabel(caption, SwingConstants.CENTER), BorderLayout.NORTH);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 36f));
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    void adjust(int amount) {
        if (room != null) {
            own.setText(Long.toString(room.adjust(amount)));
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
                "Cria uma sala ou cola o convite de outro jogador.",
                "Contagem em grupo", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, options, options[2]);
        long token = ++generation;
        try {
            if (choice == 0) {
                String address = chooseAddress();
                if (address != null) {
                    room = SharedRoom.host(address, SharedRoom.PORT, view -> update(token, view));
                    joined("A criar sala…");
                    manage();
                }
            } else if (choice == 1) {
                String invitation = JOptionPane.showInputDialog(this, "Cola o convite da sala.");
                if (invitation != null) {
                    room = SharedRoom.join(invitation, view -> update(token, view));
                    joined("A ligar à sala…");
                }
            }
        } catch (IOException | IllegalArgumentException error) {
            JOptionPane.showMessageDialog(this, "Não foi possível abrir a sala. " + error.getMessage(),
                    "Contagem em grupo", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void joined(String message) {
        own.setText("0");
        status.setText(message);
        membershipChanged.accept(true);
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
                total.setText(Long.toString(view.total()));
                status.setText(view.online() ? "Ligado à sala" : "Sala sem ligação · a tentar novamente…");
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
        total.setText("—");
        own.setText("—");
        status.setText("Sem sala");
        membershipChanged.accept(false);
    }
}
