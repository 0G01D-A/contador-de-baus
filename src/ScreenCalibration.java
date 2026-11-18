import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

final class ScreenCalibration {
    private ScreenCalibration() {}

    static List<BufferedImage> capture(Rectangle display) throws AWTException, InterruptedException {
        Robot robot = new Robot();
        double scale = Math.min(1, Math.min(900.0 / display.width, 540.0 / display.height));
        int width = (int) (display.width * scale);
        int height = (int) (display.height * scale);
        List<BufferedImage> frames = new ArrayList<>();
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            frames.add(ScreenDetector.resize(robot.createScreenCapture(display), width, height));
            Thread.sleep(140);
        }
        return frames;
    }

    static ScreenDetector.Calibration choose(JFrame owner, Rectangle display, List<BufferedImage> frames) {
        if (frames.isEmpty()) {
            return null;
        }
        JDialog dialog = new JDialog(owner, "Escolher imagem da abertura", true);
        var slider = new JSlider(0, frames.size() - 1, 0);
        var position = new JLabel();
        var save = new JButton("Guardar zona");
        save.setEnabled(false);
        var selection = new Rectangle();
        var result = new ScreenDetector.Calibration[1];
        int width = frames.getFirst().getWidth();
        int height = frames.getFirst().getHeight();
        var preview = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                graphics.drawImage(frames.get(slider.getValue()), 0, 0, getWidth(), getHeight(), null);
                graphics.setColor(new Color(79, 167, 255));
                int x = selection.x * getWidth() / width;
                int y = selection.y * getHeight() / height;
                int w = selection.width * getWidth() / width;
                int h = selection.height * getHeight() / height;
                graphics.drawRect(x, y, w, h);
                graphics.drawRect(x - 1, y - 1, w + 2, h + 2);
            }
        };
        Dimension desktop = Toolkit.getDefaultToolkit().getScreenSize();
        double fit = Math.min(1, Math.min((desktop.width - 80.0) / width, (desktop.height - 200.0) / height));
        preview.setPreferredSize(new Dimension((int) (width * fit), (int) (height * fit)));
        var drag = new MouseAdapter() {
            private Point origin;

            private Point position(MouseEvent event) {
                return new Point(Math.clamp(event.getX() * width / preview.getWidth(), 0, width),
                        Math.clamp(event.getY() * height / preview.getHeight(), 0, height));
            }

            @Override
            public void mousePressed(MouseEvent event) {
                origin = position(event);
                mouseDragged(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (origin == null) {
                    return;
                }
                Point end = position(event);
                selection.setBounds(Math.min(origin.x, end.x), Math.min(origin.y, end.y),
                        Math.abs(origin.x - end.x), Math.abs(origin.y - end.y));
                save.setEnabled(selection.width > 6 && selection.height > 4);
                preview.repaint();
            }
        };
        preview.addMouseListener(drag);
        preview.addMouseMotionListener(drag);
        slider.addChangeListener(event -> {
            position.setText("Imagem " + (slider.getValue() + 1) + " / " + frames.size());
            preview.repaint();
        });
        position.setText("Imagem 1 / " + frames.size());
        save.addActionListener(event -> {
            int left = selection.x * display.width / width;
            int top = selection.y * display.height / height;
            int right = (selection.x + selection.width) * display.width / width;
            int bottom = (selection.y + selection.height) * display.height / height;
            var area = new Rectangle(display.x + left, display.y + top, right - left, bottom - top);
            BufferedImage frame = frames.get(slider.getValue());
            try {
                result[0] = ScreenDetector.calibrate(display, area,
                        frame.getSubimage(selection.x, selection.y, selection.width, selection.height));
                dialog.dispose();
            } catch (IllegalArgumentException error) {
                JOptionPane.showMessageDialog(dialog, error.getMessage(), "Imagem", JOptionPane.WARNING_MESSAGE);
            }
        });
        var cancel = new JButton("Cancelar");
        cancel.addActionListener(event -> dialog.dispose());
        var controls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        controls.add(position);
        controls.add(cancel);
        controls.add(save);
        var bottom = new JPanel(new BorderLayout());
        bottom.add(slider, BorderLayout.NORTH);
        bottom.add(controls, BorderLayout.SOUTH);
        dialog.add(new JLabel("Escolhe a imagem e arrasta sobre um sinal que só aparece quando abres o baú."),
                BorderLayout.NORTH);
        dialog.add(preview, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return result[0];
    }
}
