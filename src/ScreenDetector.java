import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

final class ScreenDetector {
    private static final int WIDTH = 48;
    private static final int HEIGHT = 28;
    private static final int FORMAT = 0x46435301;
    private static final double MATCH = 0.88;
    private static final double RELEASE = 0.76;
    private final Calibration calibration;
    private int matches;
    private int misses;
    private boolean visible;

    record Calibration(Rectangle display, Rectangle area, float[] pattern) {}
    record Reading(double similarity, boolean appeared) {}

    ScreenDetector(Calibration calibration) {
        this.calibration = calibration;
    }

    Reading observe(BufferedImage image) {
        float[] observed = pattern(image);
        double score = 0;
        if (observed != null) {
            for (int i = 0; i < observed.length; i++) {
                score += observed[i] * calibration.pattern()[i];
            }
        }
        score = Math.clamp(score, 0, 1);
        if (visible) {
            misses = score < RELEASE ? misses + 1 : 0;
            if (misses >= 2) {
                visible = false;
            }
            return new Reading(score, false);
        }
        matches = score >= MATCH ? matches + 1 : 0;
        if (matches >= 2) {
            visible = true;
            matches = 0;
            misses = 0;
            return new Reading(score, true);
        }
        return new Reading(score, false);
    }

    static Calibration calibrate(Rectangle display, Rectangle area, BufferedImage image) {
        float[] pattern = pattern(image);
        if (!display.contains(area) || area.width < 20 || area.height < 12 || pattern == null) {
            throw new IllegalArgumentException("Seleciona uma zona maior que tenha texto ou um símbolo bem visível.");
        }
        return new Calibration(new Rectangle(display), new Rectangle(area), pattern);
    }

    private static float[] pattern(BufferedImage image) {
        BufferedImage scaled = resize(image, WIDTH, HEIGHT);
        var output = new float[(WIDTH - 2) * (HEIGHT - 2)];
        double energy = 0;
        int index = 0;
        for (int y = 1; y < HEIGHT - 1; y++) {
            for (int x = 1; x < WIDTH - 1; x++) {
                int center = gray(scaled.getRGB(x, y));
                int edge = 4 * center - gray(scaled.getRGB(x - 1, y)) - gray(scaled.getRGB(x + 1, y))
                        - gray(scaled.getRGB(x, y - 1)) - gray(scaled.getRGB(x, y + 1));
                output[index++] = edge;
                energy += (double) edge * edge;
            }
        }
        if (energy < output.length * 16) {
            return null;
        }
        float scale = (float) Math.sqrt(energy);
        for (int i = 0; i < output.length; i++) {
            output[i] /= scale;
        }
        return output;
    }

    private static int gray(int rgb) {
        return (30 * ((rgb >>> 16) & 255) + 59 * ((rgb >>> 8) & 255) + 11 * (rgb & 255)) / 100;
    }

    static BufferedImage resize(BufferedImage image, int width, int height) {
        var resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    static Rectangle primaryDisplay() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
    }

    static Calibration load(Path file) throws IOException {
        if (Files.notExists(file)) {
            return null;
        }
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != FORMAT) {
                throw new IOException("Formato de amostra visual desconhecido.");
            }
            var display = readRectangle(input);
            var area = readRectangle(input);
            if (!display.contains(area) || area.width < 20 || area.height < 12) {
                throw new IOException("Zona de imagem inválida.");
            }
            var pattern = new float[(WIDTH - 2) * (HEIGHT - 2)];
            for (int i = 0; i < pattern.length; i++) {
                pattern[i] = input.readFloat();
                if (!Float.isFinite(pattern[i]) || Math.abs(pattern[i]) > 1) {
                    throw new IOException("Amostra visual inválida.");
                }
            }
            return new Calibration(display, area, pattern);
        }
    }

    static void save(Path file, Calibration calibration) throws IOException {
        Path temp = file.resolveSibling("imagem.tmp");
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                output.writeInt(FORMAT);
                writeRectangle(output, calibration.display());
                writeRectangle(output, calibration.area());
                for (float value : calibration.pattern()) {
                    output.writeFloat(value);
                }
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Rectangle readRectangle(DataInputStream input) throws IOException {
        return new Rectangle(input.readInt(), input.readInt(), input.readInt(), input.readInt());
    }

    private static void writeRectangle(DataOutputStream output, Rectangle rectangle) throws IOException {
        output.writeInt(rectangle.x);
        output.writeInt(rectangle.y);
        output.writeInt(rectangle.width);
        output.writeInt(rectangle.height);
    }
}
