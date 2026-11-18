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
    private final JLabel personalAverage = new JLabel("—", SwingConstants.CENTER);
    private final JLabel teamAverage = new JLabel("—", SwingConstants.CENTER);
    private final JLabel status = new JLabel("Sem sala", SwingConstants.CENTER);
    private final JButton settings = new JButton("Contagem em grupo");
    private final JButton nextPersonal = new JButton("Nova partida pessoal");
    private final JButton nextTeam = new JButton("Nova partida da equipa");
    private final Consumer<Boolean> membershipChanged;
    private SharedRoom room;
    private long generation;

    RoomPanel(Consumer<Boolean> membershipChanged) {
        super(new BorderLayout(0, 12));
        this.membershipChanged = membershipChanged;
        setOpaque(false);
        var counts = new JPanel(new GridLayout(2, 2, 10, 8));
        counts.setOpaque(false);
        counts.add(card("Total do grupo", total));
        counts.add(card("Os meus baús nesta partida", own));
        counts.add(card("A minha média por partida", personalAverage));
        counts.add(card("Média da equipa por partida", teamAverage));
        add(counts, BorderLayout.CENTER);
        var actions = new JPanel(new BorderLayout(0, 6));
        actions.setOpaque(false);
        actions.add(status, BorderLayout.NORTH);
        actions.add(settings, BorderLayout.CENTER);
        var matches = new JPanel(new GridLayout(1, 2, 10, 0));
        matches.setOpaque(false);
        matches.add(nextPersonal);
        matches.add(nextTeam);
        actions.add(matches, BorderLayout.SOUTH);
        add(actions, BorderLayout.SOUTH);
        settings.addActionListener(event -> manage());
        nextPersonal.addActionListener(event -> nextMatch(false));
        nextTeam.addActionListener(event -> nextMatch(true));
        nextPersonal.setEnabled(false);
        nextTeam.setEnabled(false);
    }

    private void nextMatch(boolean team) {
        if (room == null || (team && !room.score().host)) {
            return;
        }
        String title = team ? "Nova partida da equipa" : "Nova partida pessoal";
        String message = team
                ? "Registar esta partida da equipa para a média? O total do grupo mantém-se."
                : "Registar esta partida para a tua média e pôr a tua contagem a zero? O total do grupo mantém-se.";
        var options = new Object[]{title, "Cancelar"};
        if (JOptionPane.showOptionDialog(this, message, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[1]) != 0) {
            return;
        }
        room.score().nextMatch(team);
        showCounts(room.score());
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
            if (room.score().host) {
                total.setText(Long.toString(room.score().totals().total()));
            }
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
                null, options, options[options.length - 1]);
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
            showError("Não foi possível abrir a sala. ", error);
        }
    }

    private void joined(String message) {
        showCounts(room.score());
        status.setText(message);
        nextPersonal.setEnabled(true);
        nextTeam.setEnabled(room.score().host);
        membershipChanged.accept(true);
    }

    private void showCounts(RoomScore score) {
        var totals = score.totals();
        total.setText(Long.toString(totals.total()));
        own.setText(Long.toString(score.ownMatch()));
        showAverage(personalAverage, score.personalGames());
        showAverage(teamAverage, totals.team());
    }

    private static void showAverage(JLabel label, RoomScore.Games games) {
        label.setText(games.completed() == 0 ? "—" : "%.2f".formatted((double) games.chests() / games.completed()));
        label.setToolTipText(games.completed() + " partidas terminadas");
    }

    private void showError(String message, Exception error) {
        JOptionPane.showMessageDialog(this, message + error.getMessage(),
                "Contagem em grupo", JOptionPane.ERROR_MESSAGE);
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
                showCounts(room.score());
                if (view.online()) {
                    status.setText(room.score().host ? "Sala aberta neste PC"
                            : view.team() == null ? "Ligado à sala · médias da equipa indisponíveis" : "Ligado à sala");
                } else {
                    status.setText(view.issue() + " · a tentar novamente…");
                }
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
        for (var label : new JLabel[]{total, own, personalAverage, teamAverage}) {
            label.setText("—");
            label.setToolTipText(null);
        }
        status.setText("Sem sala");
        nextPersonal.setEnabled(false);
        nextTeam.setEnabled(false);
        membershipChanged.accept(false);
    }
}
