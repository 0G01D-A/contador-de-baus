import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public final class Main {
    private static final Color INK = new Color(23, 29, 43);
    private static final Color MUTED = new Color(78, 91, 114);
    private static final Color BLUE = new Color(38, 74, 182);
    private static final int PREVIEW_SECONDS = 5;
    private final JFrame window = new JFrame("Contador de Baús");
    private final JLabel sessionLabel = label("0", 42, BLUE);
    private final JLabel totalLabel = label("0", 42, INK);
    private final JLabel status = label("Grava uma amostra para começar.", 14, INK);
    private final JLabel samplesLabel = label("", 13, MUTED);
    private final JLabel similarityLabel = label("", 13, MUTED);
    private final JProgressBar level = new JProgressBar(0, 100);
    private final JProgressBar liveSimilarity = new JProgressBar(0, 100);
    private final JButton monitorButton = new JButton("Iniciar contagem");
    private final JButton calibrateButton = new JButton("Gravar som de baú");
    private final JButton addButton = new JButton("+1");
    private final JButton subtractButton = new JButton("Remover 1 baú");
    private final JButton resetButton = new JButton("Repor contagem");
    private final JButton reviewButton = new JButton("Rever amostras");
    private final JButton previewButton = new JButton("Ouvir áudio recebido");
    private final JCheckBox addShortcut = new JCheckBox("Atalho global F9 (+1)", true);
    private final JCheckBox subtractShortcut = new JCheckBox("Atalho global F8 (−1)", true);
    private final JSlider sensitivity = new JSlider(50, 95, 80);
    private final CounterStore store;
    private CounterStore.State state;
    private long session;
    private volatile double threshold = sensitivity.getValue() / 100.0;
    private double bestSimilarity = Double.NaN;
    private Timer countdown;
    private Thread task;
    private WindowsHotkey addHotkey;
    private WindowsHotkey subtractHotkey;
    private boolean recording;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (!System.getProperty("os.name").startsWith("Windows")) {
                    throw new IOException("O contador necessita do Windows.");
                }
                String localAppData = System.getenv("LOCALAPPDATA");
                Path data = localAppData == null ? Path.of(System.getProperty("user.home")) : Path.of(localAppData);
                data = data.resolve("ContadorDeBaus");
                CounterStore store = new CounterStore(data);
                FileChannel lock = FileChannel.open(data.resolve("contador.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                Main app;
                try {
                    if (lock.tryLock() == null) {
                        throw new IOException("O contador já está aberto.");
                    }
                    app = new Main(store, store.load());
                } catch (IOException | RuntimeException error) {
                    lock.close();
                    throw error;
                }
                app.window.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent event) {
                        app.addHotkey.close();
                        app.subtractHotkey.close();
                        app.stopTask();
                        try {
                            lock.close();
                        } catch (IOException error) {
                            System.err.println(error.getMessage());
                        }
                    }
                });
                app.addHotkey = app.startShortcut(app.addShortcut, WindowsHotkey.VK_F9, "F9", 1);
                app.subtractHotkey = app.startShortcut(app.subtractShortcut, WindowsHotkey.VK_F8, "F8", -1);
                app.window.setVisible(true);
                if (!app.state.samples().isEmpty()) {
                    app.startMonitoring();
                }
            } catch (IOException error) {
                JOptionPane.showMessageDialog(null, error.getMessage(), "Contador de Baús", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private Main(CounterStore store, CounterStore.State state) {
        this.store = store;
        this.state = state;
        JPanel content = new JPanel();
        content.setBackground(new Color(242, 245, 252));
        content.setBorder(new EmptyBorder(24, 26, 24, 26));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JLabel title = label("Contador de Baús", 31, INK);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(18));

        JPanel scores = new JPanel(new GridLayout(1, 2, 12, 0));
        scores.setOpaque(false);
        scores.setAlignmentX(Component.LEFT_ALIGNMENT);
        scores.add(scoreCard("Nesta sessão", sessionLabel));
        JPanel totalCard = scoreCard("Total guardado", totalLabel);
        resetButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        resetButton.addActionListener(event -> resetCount());
        totalCard.add(Box.createVerticalStrut(4));
        totalCard.add(resetButton);
        scores.add(totalCard);
        content.add(scores);
        content.add(Box.createVerticalStrut(16));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        monitorButton.addActionListener(event -> {
            if (task == null) {
                startMonitoring();
            } else {
                stopTask();
                status.setText("Contagem em pausa.");
            }
        });
        calibrateButton.addActionListener(event -> startCalibration());
        addButton.setToolTipText("Adicionar um baú manualmente");
        subtractButton.setToolTipText("Retira um baú do total e da sessão se ainda houver baús nesta sessão.");
        addButton.addActionListener(event -> changeCount(1));
        subtractButton.addActionListener(event -> changeCount(-1));
        actions.add(calibrateButton);
        actions.add(monitorButton);
        actions.add(addButton);
        actions.add(subtractButton);
        content.add(actions);
        content.add(Box.createVerticalStrut(6));
        JPanel audioActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        audioActions.setOpaque(false);
        audioActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        reviewButton.addActionListener(event -> reviewSamples());
        previewButton.addActionListener(event -> previewReceivedAudio());
        audioActions.add(reviewButton);
        audioActions.add(previewButton);
        content.add(audioActions);
        JPanel shortcuts = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        shortcuts.setOpaque(false);
        shortcuts.setAlignmentX(Component.LEFT_ALIGNMENT);
        addShortcut.setOpaque(false);
        subtractShortcut.setOpaque(false);
        shortcuts.add(addShortcut);
        shortcuts.add(subtractShortcut);
        content.add(Box.createVerticalStrut(6));
        content.add(shortcuts);
        content.add(Box.createVerticalStrut(15));

        JPanel tuning = new JPanel(new GridLayout(3, 2, 12, 4));
        tuning.setOpaque(false);
        tuning.setAlignmentX(Component.LEFT_ALIGNMENT);
        tuning.add(label("Nível do som do Windows", 13, MUTED));
        tuning.add(level);
        tuning.add(label("Semelhança agora", 13, MUTED));
        liveSimilarity.setStringPainted(true);
        liveSimilarity.setToolTipText("Semelhança do áudio recebido com a amostra mais parecida.");
        updateLiveSimilarity(Double.NaN);
        tuning.add(liveSimilarity);
        tuning.add(label("Mais deteções ← Sensibilidade → Menos", 12, MUTED));
        sensitivity.setOpaque(false);
        sensitivity.setToolTipText("Move para a esquerda se a aplicação falhar aberturas; para a direita se contar outros sons.");
        sensitivity.addChangeListener(event -> {
            threshold = sensitivity.getValue() / 100.0;
            refreshSimilarity();
        });
        tuning.add(sensitivity);
        content.add(tuning);
        content.add(Box.createVerticalStrut(4));
        similarityLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        similarityLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, similarityLabel.getPreferredSize().height));
        content.add(similarityLabel);
        refreshSimilarity();
        content.add(Box.createVerticalStrut(15));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        samplesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(status);
        content.add(Box.createVerticalStrut(4));
        content.add(samplesLabel);
        window.setContentPane(content);
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        window.setMinimumSize(new Dimension(560, 350));
        window.pack();
        window.setLocationRelativeTo(null);
        refresh();
    }

    private void refreshSimilarity() {
        String best = Double.isNaN(bestSimilarity) ? "—" : "%.1f%%".formatted(bestSimilarity * 100);
        similarityLabel.setText("Melhor semelhança: %s  ·  Mínimo para contar: %d%%".formatted(best, sensitivity.getValue()));
    }

    private void updateLiveSimilarity(double similarity) {
        liveSimilarity.setValue(Double.isNaN(similarity) ? 0 : (int) Math.round(similarity * 100));
        liveSimilarity.setString(Double.isNaN(similarity) ? "—" : "%.1f%%".formatted(similarity * 100));
    }

    private WindowsHotkey startShortcut(JCheckBox shortcut, int key, String keyName, int amount) {
        String label = shortcut.getText();
        var hotkey = new WindowsHotkey(key,
                () -> SwingUtilities.invokeLater(() -> {
                    if (window.isDisplayable() && shortcut.isSelected()) {
                        changeCount(amount);
                    }
                }),
                registered -> SwingUtilities.invokeLater(() -> {
                    if (window.isDisplayable() && shortcut.isSelected()) {
                        if (registered) {
                            shortcut.setText(label);
                        } else {
                            shortcut.setSelected(false);
                            shortcut.setText(keyName + " indisponível");
                            shortcut.setToolTipText("Marca para tentar ativar o atalho de novo.");
                        }
                    }
                }));
        shortcut.addActionListener(event -> {
            shortcut.setText(label);
            hotkey.setEnabled(shortcut.isSelected());
        });
        hotkey.setEnabled(shortcut.isSelected());
        return hotkey;
    }

    private static JPanel scoreCard(String name, JLabel score) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(new EmptyBorder(13, 16, 11, 16));
        card.add(label(name, 15, MUTED));
        card.add(score);
        return card;
    }

    private static JLabel label(String text, int size, Color color) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(size >= 30 ? Font.BOLD : Font.PLAIN, (float) size));
        return label;
    }

    private void refresh() {
        sessionLabel.setText(String.valueOf(session));
        totalLabel.setText(String.valueOf(state.total()));
        int count = state.samples().size();
        samplesLabel.setText(count == 1 ? "1 amostra guardada" : count + " amostras guardadas");
        subtractButton.setEnabled(state.total() > 0);
        resetButton.setEnabled(state.total() > 0);
        monitorButton.setEnabled(!recording && count > 0);
        monitorButton.setText(task != null && !recording ? "Pausar contagem" : "Iniciar contagem");
        calibrateButton.setEnabled(!recording);
        reviewButton.setEnabled(!recording && count > 0);
        previewButton.setEnabled(!recording);
    }

    private boolean save(CounterStore.State updated) {
        try {
            store.save(updated);
            state = updated;
            return true;
        } catch (IOException error) {
            showError("Não foi possível guardar os dados. " + error.getMessage());
            return false;
        }
    }

    private void changeCount(int amount) {
        if ((amount < 0 && state.total() < -(long) amount)
                || (amount > 0 && state.total() > Long.MAX_VALUE - amount)) {
            return;
        }
        if (save(new CounterStore.State(state.total() + amount, state.samples()))) {
            session = Math.max(0, session + amount);
            refresh();
        }
    }

    private void resetCount() {
        Object[] options = {"Repor contagem", "Cancelar"};
        int choice = JOptionPane.showOptionDialog(window,
                "Queres repor a contagem? A sessão e o total guardado vão ficar a zero. As amostras mantêm-se.",
                "Repor contagem", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        if (choice == 0 && save(new CounterStore.State(0, state.samples()))) {
            session = 0;
            refresh();
        }
    }

    private void stopTask() {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
        if (task != null) {
            task.interrupt();
            task = null;
        }
        recording = false;
        level.setValue(0);
        updateLiveSimilarity(Double.NaN);
        bestSimilarity = Double.NaN;
        refreshSimilarity();
        refresh();
    }

    private void startMonitoring() {
        var detector = new ChestDetector(state.samples().stream().map(CounterStore.Sample::fingerprint).toList());
        status.setText("A ligar à saída de áudio…");
        startTask((audio, sender) -> {
            post(sender, () -> status.setText("A ouvir a saída de áudio do Windows."));
            long nextMeter = 0;
            float peak = 0;
            while (!sender.isInterrupted()) {
                float[] chunk = audio.poll();
                peak = Math.max(peak, peak(chunk));
                int detections = detector.accept(chunk, threshold);
                if (detections > 0) {
                    post(sender, () -> changeCount(detections));
                }
                long now = System.nanoTime();
                if (now >= nextMeter) {
                    int value = (int) (Math.min(1, Math.sqrt(peak)) * 100);
                    double similarity = detector.bestSimilarity();
                    double current = detector.takeRecentSimilarity();
                    post(sender, () -> {
                        level.setValue(value);
                        updateLiveSimilarity(current);
                        if (Double.compare(bestSimilarity, similarity) != 0) {
                            bestSimilarity = similarity;
                            refreshSimilarity();
                        }
                    });
                    peak = 0;
                    nextMeter = now + 100_000_000L;
                }
                if (chunk.length == 0) {
                    Thread.sleep(8);
                }
            }
        });
    }

    private void startCalibration() {
        int answer = JOptionPane.showConfirmDialog(window,
                """
                Deixa um baú por abrir e usa a saída de som predefinida do Windows.
                Depois de confirmares, volta ao Fortnite em 5 segundos.
                Abre um baú durante os 7 segundos seguintes.
                """, "Gravar som de baú", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        boolean resume = task != null;
        stopTask();
        recording = true;
        refresh();
        int[] seconds = {5};
        status.setText("Volta ao jogo. A gravação começa em 5 s.");
        countdown = new Timer(1000, event -> {
            seconds[0]--;
            if (seconds[0] > 0) {
                status.setText("A gravação começa em " + seconds[0] + " s.");
                return;
            }
            countdown.stop();
            countdown = null;
            status.setText("A gravar. Abre um baú agora.");
            record(CounterStore.RECORDING_SECONDS, clip -> finishCalibration(clip, resume));
        });
        countdown.start();
    }

    private void record(int seconds, Consumer<float[]> completion) {
        startTask((audio, sender) -> {
            float[] clip = new float[WindowsAudio.SAMPLE_RATE * seconds];
            int written = 0;
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            while (written < clip.length && System.nanoTime() < end && !sender.isInterrupted()) {
                float[] chunk = audio.poll();
                int length = Math.min(chunk.length, clip.length - written);
                System.arraycopy(chunk, 0, clip, written, length);
                written += length;
                if (length == 0) {
                    Thread.sleep(8);
                }
            }
            float[] result = Arrays.copyOf(clip, written);
            post(sender, () -> completion.accept(result));
        });
    }

    private void finishCalibration(float[] clip, boolean resume) {
        stopTask();
        boolean saved = false;
        if (clip.length < CounterStore.MIN_CLIP_MS * (WindowsAudio.SAMPLE_RATE / 1000) || peak(clip) < 0.001f) {
            showError("A gravação está sem som. Verifica se o Fortnite usa a saída predefinida do Windows.");
        } else {
            window.toFront();
            int samplesPerMs = WindowsAudio.SAMPLE_RATE / 1000;
            float[] wholeMilliseconds = Arrays.copyOf(clip, clip.length / samplesPerMs * samplesPerMs);
            CounterStore.Sample sample = new CalibrationDialog(window, AudioPreview.toPcm(wholeMilliseconds),
                    -1, -1).choose();
            if (sample != null) {
                List<CounterStore.Sample> samples = new ArrayList<>(state.samples());
                if (samples.size() == CounterStore.MAX_REFERENCES) {
                    samples.removeFirst();
                }
                samples.add(sample);
                saved = save(new CounterStore.State(state.total(), samples));
            }
        }
        refresh();
        if (saved || resume) {
            startMonitoring();
        } else {
            showIdleStatus();
        }
    }

    private void showIdleStatus() {
        status.setText(state.samples().isEmpty() ? "Grava uma amostra para começar." : "Contagem em pausa.");
    }

    private void reviewSamples() {
        boolean resume = task != null;
        stopTask();
        status.setText("Contagem em pausa durante a reprodução.");
        try (var preview = new AudioPreview()) {
            var list = new DefaultListModel<String>();
            var choices = new JList<>(list);
            choices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            var details = new JLabel();
            var listen = new JButton("Ouvir amostra");
            var edit = new JButton("Editar trecho");
            var remove = new JButton("Remover amostra");
            Runnable updateControls = () -> {
                preview.close();
                int index = choices.getSelectedIndex();
                boolean selected = index >= 0 && index < state.samples().size();
                boolean available = selected && state.samples().get(index).recording() != null;
                listen.setEnabled(available);
                edit.setEnabled(available);
                remove.setEnabled(selected);
                if (!selected) {
                    details.setText("Não há nenhuma amostra selecionada.");
                } else if (available) {
                    details.setText("Trecho usado para reconhecer o som do baú.");
                } else {
                    details.setText("Só foi guardada a referência acústica. Grava novamente para poderes editar.");
                }
            };
            choices.addListSelectionListener(event -> updateControls.run());
            listen.addActionListener(event -> preview.play(choices,
                    state.samples().get(choices.getSelectedIndex()).selectedAudio()));
            edit.addActionListener(event -> {
                preview.close();
                int index = choices.getSelectedIndex();
                CounterStore.Sample sample = state.samples().get(index);
                CounterStore.Sample edited = new CalibrationDialog(SwingUtilities.getWindowAncestor(choices),
                        sample.recording(), sample.startMs(), sample.endMs()).choose();
                if (edited != null) {
                    List<CounterStore.Sample> samples = new ArrayList<>(state.samples());
                    samples.set(index, edited);
                    if (save(new CounterStore.State(state.total(), samples))) {
                        refreshSampleList(list, choices, index);
                    }
                }
            });
            remove.addActionListener(event -> {
                preview.close();
                int index = choices.getSelectedIndex();
                int answer = JOptionPane.showConfirmDialog(choices,
                        "Remover a amostra " + (index + 1) + " e deixar de a usar na deteção?",
                        "Remover amostra", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer == JOptionPane.YES_OPTION) {
                    List<CounterStore.Sample> samples = new ArrayList<>(state.samples());
                    samples.remove(index);
                    if (save(new CounterStore.State(state.total(), samples))) {
                        refreshSampleList(list, choices, index);
                        updateControls.run();
                    }
                }
            });
            refreshSampleList(list, choices, 0);
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.add(new JScrollPane(choices), BorderLayout.CENTER);
            JPanel controls = new JPanel(new BorderLayout(0, 6));
            controls.add(details, BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
            actions.add(listen);
            actions.add(edit);
            actions.add(remove);
            controls.add(actions, BorderLayout.SOUTH);
            panel.add(controls, BorderLayout.SOUTH);
            JOptionPane.showMessageDialog(window, panel, "Amostras guardadas", JOptionPane.PLAIN_MESSAGE);
        } finally {
            resumeAfterPreview(resume);
        }
    }

    private void refreshSampleList(DefaultListModel<String> list, JList<String> choices, int selected) {
        list.clear();
        for (int i = 0; i < state.samples().size(); i++) {
            CounterStore.Sample sample = state.samples().get(i);
            String duration = sample.recording() == null ? "sem gravação original"
                    : "%.2f s".formatted((sample.endMs() - sample.startMs()) / 1000.0);
            list.addElement("Amostra " + (i + 1) + " · " + duration);
        }
        choices.setSelectedIndex(Math.min(selected, list.size() - 1));
        refresh();
    }

    private void previewReceivedAudio() {
        boolean resume = task != null;
        stopTask();
        recording = true;
        status.setText("A gravar o áudio do Windows durante " + PREVIEW_SECONDS + " segundos…");
        record(PREVIEW_SECONDS, clip -> {
            stopTask();
            try (var preview = new AudioPreview()) {
                if (clip.length == 0 || peak(clip) < 0.001f) {
                    showError("Não foi captado som audível. Confirma a saída predefinida do Windows.");
                    return;
                }
                var listen = new JButton("Reproduzir gravação");
                byte[] pcm = AudioPreview.toPcm(clip);
                listen.addActionListener(event -> preview.play(listen, pcm));
                var panel = new JPanel(new BorderLayout(0, 8));
                panel.add(new JLabel("Gravação temporária da saída predefinida do Windows."), BorderLayout.NORTH);
                panel.add(listen, BorderLayout.CENTER);
                JOptionPane.showMessageDialog(window, panel, "Áudio recebido", JOptionPane.PLAIN_MESSAGE);
            } finally {
                resumeAfterPreview(resume);
            }
        });
    }

    private void resumeAfterPreview(boolean resume) {
        if (!window.isDisplayable()) {
            return;
        }
        if (resume && !state.samples().isEmpty()) {
            startMonitoring();
        } else {
            showIdleStatus();
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(window, message, "Contador de Baús", JOptionPane.ERROR_MESSAGE);
    }

    private void startTask(AudioTask action) {
        task = Thread.ofPlatform().daemon().name("Windows audio capture").unstarted(() -> {
            Thread sender = Thread.currentThread();
            try (var audio = new WindowsAudio()) {
                action.run(audio, sender);
            } catch (InterruptedException ignored) {
                sender.interrupt();
            } catch (RuntimeException | LinkageError error) {
                post(sender, () -> {
                    stopTask();
                    status.setText("A captura de áudio parou.");
                    showError("A captura de áudio falhou. " + error.getMessage()
                            + "\nVerifica a saída de áudio e volta a iniciar.");
                });
            }
        });
        task.start();
        refresh();
    }

    private void post(Thread sender, Runnable update) {
        SwingUtilities.invokeLater(() -> {
            if (task == sender) {
                update.run();
            }
        });
    }

    static float peak(float[] samples) {
        float peak = 0;
        for (float sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private interface AudioTask {
        void run(WindowsAudio audio, Thread sender) throws InterruptedException;
    }

    @SuppressWarnings("serial")
    private static final class CalibrationDialog extends JDialog {
        private static final int SAMPLES_PER_MS = WindowsAudio.SAMPLE_RATE / 1000;
        private final byte[] recording;
        private final float[] audio;
        private final JPanel waveform;
        private final JSlider from;
        private final JSlider to;
        private final JLabel interval = new JLabel();
        private final JButton save = new JButton("Guardar amostra");
        private final JButton listen = new JButton("Ouvir trecho");
        private final float[] bars = new float[350];
        private final AudioPreview preview = new AudioPreview();
        private CounterStore.Sample result;

        CalibrationDialog(Window owner, byte[] recording, int initialStart, int initialEnd) {
            super(owner, initialStart < 0 ? "Selecionar som do baú" : "Editar trecho", ModalityType.APPLICATION_MODAL);
            this.recording = recording;
            audio = AudioPreview.fromPcm(recording);
            int duration = audio.length / SAMPLES_PER_MS;
            int loudest = 0;
            for (int i = 1; i < audio.length; i++) {
                if (Math.abs(audio[i]) > Math.abs(audio[loudest])) {
                    loudest = i;
                }
            }
            int start = Math.clamp(loudest / SAMPLES_PER_MS - 350, 0, Math.max(0, duration - 1500));
            from = new JSlider(0, duration, initialStart < 0 ? start : initialStart);
            to = new JSlider(0, duration, initialEnd < 0 ? Math.min(duration, start + 1500) : initialEnd);
            for (int i = 0; i < audio.length; i++) {
                int bar = i * bars.length / audio.length;
                bars[bar] = Math.max(bars[bar], Math.abs(audio[i]));
            }
            waveform = new JPanel() {
                @Override
                protected void paintComponent(Graphics graphics) {
                    super.paintComponent(graphics);
                    int width = getWidth();
                    int height = getHeight();
                    int left = from.getValue() * width / duration;
                    int right = to.getValue() * width / duration;
                    graphics.setColor(new Color(219, 230, 255));
                    graphics.fillRect(left, 0, right - left, height);
                    float scale = Math.max(0.001f, peak(bars));
                    for (int i = 0; i < bars.length; i++) {
                        int x = i * width / bars.length;
                        int amplitude = (int) (bars[i] / scale * (height / 2 - 3));
                        graphics.setColor(x >= left && x <= right ? BLUE : MUTED);
                        graphics.drawLine(x, height / 2 - amplitude, x, height / 2 + amplitude);
                    }
                }
            };
            waveform.setBackground(Color.WHITE);
            waveform.setPreferredSize(new Dimension(475, 85));
            JPanel content = new JPanel();
            content.setBorder(new EmptyBorder(15, 17, 14, 17));
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            addCentered(content, new JLabel("Seleciona entre 0,6 e 2,5 segundos com o som da abertura."));
            content.add(Box.createVerticalStrut(8));
            addCentered(content, waveform);
            JPanel sliders = new JPanel(new GridLayout(2, 2, 6, 0));
            sliders.add(new JLabel("Início"));
            sliders.add(from);
            sliders.add(new JLabel("Fim"));
            sliders.add(to);
            addCentered(content, sliders);
            addCentered(content, interval);
            from.addChangeListener(event -> updateInterval());
            to.addChangeListener(event -> updateInterval());
            listen.addActionListener(event -> preview.play(this, AudioPreview.toPcm(selection())));
            save.addActionListener(event -> {
                try {
                    float[] samples = selection();
                    result = new CounterStore.Sample(ChestDetector.reference(samples), this.recording,
                            from.getValue(), to.getValue());
                    dispose();
                } catch (IllegalArgumentException error) {
                    JOptionPane.showMessageDialog(this, error.getMessage(), "Amostra", JOptionPane.WARNING_MESSAGE);
                }
            });
            JButton cancel = new JButton("Cancelar");
            cancel.addActionListener(event -> dispose());
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER));
            actions.add(listen);
            actions.add(save);
            actions.add(cancel);
            addCentered(content, actions);
            setContentPane(content);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            updateInterval();
            pack();
            setLocationRelativeTo(owner);
        }

        private static void addCentered(JPanel parent, JComponent child) {
            child.setAlignmentX(Component.CENTER_ALIGNMENT);
            parent.add(child);
        }

        private void updateInterval() {
            int length = to.getValue() - from.getValue();
            interval.setText("%.2f s a %.2f s".formatted(from.getValue() / 1000.0, to.getValue() / 1000.0));
            save.setEnabled(length >= CounterStore.MIN_CLIP_MS && length <= CounterStore.MAX_CLIP_MS);
            listen.setEnabled(length > 0);
            waveform.repaint();
        }

        private float[] selection() {
            return Arrays.copyOfRange(audio, from.getValue() * SAMPLES_PER_MS, to.getValue() * SAMPLES_PER_MS);
        }

        CounterStore.Sample choose() {
            setVisible(true);
            return result;
        }

        @Override
        public void dispose() {
            preview.close();
            super.dispose();
        }
    }
}
