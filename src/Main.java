import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;

public final class Main {
    private final JFrame window = new JFrame("Contador de Baús");
    private final JButton add = new JButton();
    private final JButton subtract = new JButton();
    private final JLabel shortcutStatus = new JLabel("Entra numa sala para ativar os atalhos.", SwingConstants.CENTER);
    private final RoomPanel group = new RoomPanel(this::updateMembership);
    private final Path settingsFile;
    private Bindings binds;
    private WindowsHotkey addHotkey;
    private WindowsHotkey subtractHotkey;
    private boolean joined;
    private boolean addAvailable;
    private boolean subtractAvailable;
    private long shortcutGeneration;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                String localAppData = System.getenv("LOCALAPPDATA");
                Path base = Path.of(localAppData == null ? System.getProperty("user.home") : localAppData);
                Path data = Files.createDirectories(base.resolve("ContadorDeBaus"));
                FileChannel lock = FileChannel.open(data.resolve("contador.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                try {
                    if (lock.tryLock() == null) {
                        throw new IOException("O contador já está aberto.");
                    }
                    Main app = new Main(data.resolve("atalhos.properties"));
                    app.window.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            app.group.close();
                            try {
                                lock.close();
                            } catch (IOException error) {
                                System.err.println(error.getMessage());
                            }
                        }
                    });
                    app.window.setVisible(true);
                } catch (IOException | RuntimeException error) {
                    lock.close();
                    throw error;
                }
            } catch (IOException error) {
                JOptionPane.showMessageDialog(null, error.getMessage(), "Contador de Baús", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private Main(Path settingsFile) {
        this.settingsFile = settingsFile;
        try {
            binds = Bindings.load(settingsFile);
        } catch (IOException | IllegalArgumentException error) {
            binds = Bindings.DEFAULT;
            System.err.println("Não foi possível ler os atalhos: " + error.getMessage());
        }

        var content = new JPanel(new BorderLayout(0, 16));
        content.setBackground(new Color(242, 245, 252));
        content.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        var title = new JLabel("Contador de Baús");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 29f));
        content.add(title, BorderLayout.NORTH);
        content.add(group, BorderLayout.CENTER);

        var buttons = new JPanel(new GridLayout(1, 2, 10, 0));
        buttons.setOpaque(false);
        add.addActionListener(event -> group.adjust(1));
        subtract.addActionListener(event -> group.adjust(-1));
        buttons.add(add);
        buttons.add(subtract);
        var footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        footer.add(buttons, BorderLayout.NORTH);
        footer.add(shortcutStatus, BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH);

        var menu = new JMenuBar();
        var settings = new JMenu("Definições");
        var shortcuts = new JMenuItem("Configurar atalhos");
        shortcuts.addActionListener(event -> configure());
        settings.add(shortcuts);
        menu.add(settings);
        window.setJMenuBar(menu);
        window.setContentPane(content);
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        window.setMinimumSize(new Dimension(460, 360));
        window.pack();
        window.setLocationRelativeTo(null);
        refresh();
    }

    private void configure() {
        var addKey = new JComboBox<>(Bindings.KEYS.toArray(Bindings.Key[]::new));
        var subtractKey = new JComboBox<>(Bindings.KEYS.toArray(Bindings.Key[]::new));
        var addModifier = new JComboBox<>(Bindings.Modifiers.values());
        var subtractModifier = new JComboBox<>(Bindings.Modifiers.values());
        addKey.setSelectedItem(binds.add().key());
        addModifier.setSelectedItem(binds.add().modifiers());
        subtractKey.setSelectedItem(binds.subtract().key());
        subtractModifier.setSelectedItem(binds.subtract().modifiers());

        var options = new JPanel(new GridLayout(2, 3, 8, 8));
        options.add(new JLabel("Adicionar baú"));
        options.add(addModifier);
        options.add(addKey);
        options.add(new JLabel("Retirar baú"));
        options.add(subtractModifier);
        options.add(subtractKey);
        var choices = new Object[]{"Guardar", "Cancelar"};
        while (JOptionPane.showOptionDialog(window, options, "Configurar atalhos", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]) == 0) {
            try {
                var updated = new Bindings(new Bindings.Bind((Bindings.Key) addKey.getSelectedItem(),
                        (Bindings.Modifiers) addModifier.getSelectedItem()),
                        new Bindings.Bind((Bindings.Key) subtractKey.getSelectedItem(),
                                (Bindings.Modifiers) subtractModifier.getSelectedItem()));
                updated.save(settingsFile);
                binds = updated;
                restartHotkeys();
                return;
            } catch (IOException | IllegalArgumentException error) {
                JOptionPane.showMessageDialog(window, error.getMessage(), "Configurar atalhos",
                        JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void updateMembership(boolean active) {
        joined = active;
        restartHotkeys();
    }

    private void restartHotkeys() {
        long generation = ++shortcutGeneration;
        if (addHotkey != null) {
            addHotkey.close();
            subtractHotkey.close();
            addHotkey.awaitClosed();
            subtractHotkey.awaitClosed();
            addHotkey = null;
            subtractHotkey = null;
        }
        addAvailable = true;
        subtractAvailable = true;
        if (joined) {
            addHotkey = register(binds.add(), 1, generation);
            subtractHotkey = register(binds.subtract(), -1, generation);
        }
        refresh();
    }

    private WindowsHotkey register(Bindings.Bind bind, int amount, long generation) {
        var hotkey = new WindowsHotkey(bind.key().code(), bind.modifiers().flags,
                () -> SwingUtilities.invokeLater(() -> {
                    if (generation == shortcutGeneration && joined && window.isDisplayable()) {
                        group.adjust(amount);
                    }
                }),
                available -> SwingUtilities.invokeLater(() -> {
                    if (generation == shortcutGeneration) {
                        if (amount > 0) {
                            addAvailable = available;
                        } else {
                            subtractAvailable = available;
                        }
                        refresh();
                    }
                }));
        hotkey.setEnabled(true);
        return hotkey;
    }

    private void refresh() {
        add.setText("Adicionar 1  [" + binds.add() + "]");
        subtract.setText("Retirar 1  [" + binds.subtract() + "]");
        add.setEnabled(joined);
        subtract.setEnabled(joined);
        if (!joined) {
            shortcutStatus.setText("Entra numa sala para ativar os atalhos.");
        } else if (!addAvailable || !subtractAvailable) {
            String unavailable = !addAvailable && !subtractAvailable ? "Os dois atalhos estão indisponíveis."
                    : "Atalho indisponível: " + (!addAvailable ? binds.add() : binds.subtract()) + ".";
            shortcutStatus.setText(unavailable + " Escolhe outra combinação.");
        } else {
            shortcutStatus.setText("Atalhos ativos mesmo com a janela minimizada.");
        }
    }
}
