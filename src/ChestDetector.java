import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ChestDetector {
    static final int WINDOW = 512;
    static final int HOP = 320;
    static final int BANDS = 24;
    private static final float MIN_RMS = 0.0007f;
    private static final double[] HANN = new double[WINDOW];
    private static final int[] REVERSED = new int[WINDOW];
    private static final int[] BAND_BY_BIN = new int[WINDOW / 2];
    private static final double[] COS = new double[WINDOW / 2];
    private static final double[] SIN = new double[WINDOW / 2];
    private static final float[] SILENCE = new float[BANDS + 1];

    static {
        for (int i = 0; i < WINDOW; i++) {
            HANN[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1));
            REVERSED[i] = Integer.reverse(i) >>> (32 - Integer.numberOfTrailingZeros(WINDOW));
        }
        for (int bin = 0; bin < WINDOW / 2; bin++) {
            COS[bin] = Math.cos(2 * Math.PI * bin / WINDOW);
            SIN[bin] = -Math.sin(2 * Math.PI * bin / WINDOW);
            double frequency = (double) bin * WindowsAudio.SAMPLE_RATE / WINDOW;
            BAND_BY_BIN[bin] = frequency < 94 ? -1 : Math.min(BANDS - 1,
                    (int) (Math.log(frequency / 94) / Math.log(8000.0 / 94) * BANDS));
        }
    }

    private final SpectralAnalysis analysis = new SpectralAnalysis();
    private final List<float[][]> references;
    private final float[][] frames;
    private final float[] samples = new float[WINDOW];
    private int sampleCount;
    private int frameIndex;
    private long framesReceived;
    private long lastDetection = -1000;
    private boolean armed = true;
    private double bestSimilarity = Double.NaN;
    private double recentSimilarity;

    ChestDetector(List<float[][]> references) {
        this.references = List.copyOf(references);
        frames = new float[references.stream().mapToInt(reference -> reference.length).max().orElseThrow()][];
    }

    static float[][] reference(float[] clip) {
        var analysis = new SpectralAnalysis();
        List<float[]> frames = new ArrayList<>();
        float maxRms = 0;
        for (int offset = 0; offset + WINDOW <= clip.length; offset += HOP) {
            float[] frame = analysis.frame(clip, offset);
            frames.add(frame);
            maxRms = Math.max(maxRms, frame[0]);
        }
        long audibleFrames = frames.stream().filter(frame -> frame[0] > MIN_RMS).count();
        if (audibleFrames < 12) {
            throw new IllegalArgumentException("A seleção tem pouco som. Escolhe o trecho da abertura ou volta a gravar.");
        }
        // Weight louder moments so quiet gaps and background audio matter less.
        for (float[] frame : frames) {
            if (frame != SILENCE) {
                frame[0] = (float) Math.pow(frame[0] / maxRms, 1.3);
            }
        }
        return frames.toArray(float[][]::new);
    }

    int accept(float[] audio, double threshold) {
        int detections = 0;
        for (float sample : audio) {
            samples[sampleCount++] = sample;
            if (sampleCount == WINDOW) {
                if (observe(analysis.frame(samples, 0), threshold)) {
                    detections++;
                }
                sampleCount = WINDOW - HOP;
                System.arraycopy(samples, HOP, samples, 0, sampleCount);
            }
        }
        return detections;
    }

    double bestSimilarity() {
        return bestSimilarity;
    }

    double takeRecentSimilarity() {
        if (Double.isNaN(bestSimilarity)) {
            return Double.NaN;
        }
        double similarity = recentSimilarity;
        recentSimilarity = 0;
        return similarity;
    }

    private boolean observe(float[] frame, double threshold) {
        frames[frameIndex] = frame;
        frameIndex = (frameIndex + 1) % frames.length;
        framesReceived++;
        double best = Double.NEGATIVE_INFINITY;
        for (float[][] reference : references) {
            if (framesReceived < reference.length) {
                continue;
            }
            int index = Math.floorMod(frameIndex - reference.length, frames.length);
            double score = 0;
            double weights = 0;
            for (float[] expected : reference) {
                float[] actual = frames[index];
                double dot = 0;
                for (int band = 1; band <= BANDS; band++) {
                    dot += expected[band] * actual[band];
                }
                score += dot * expected[0];
                weights += expected[0];
                index = (index + 1) % frames.length;
            }
            best = Math.max(best, score / weights);
        }
        if (Double.isFinite(best)) {
            double similarity = Math.clamp(best, 0, 1);
            bestSimilarity = Double.isNaN(bestSimilarity) ? similarity : Math.max(bestSimilarity, similarity);
            recentSimilarity = Math.max(recentSimilarity, similarity);
        }
        if (best < threshold - 0.07) {
            armed = true;
        }
        if (armed && best >= threshold && framesReceived - lastDetection >= 35) {
            armed = false;
            lastDetection = framesReceived;
            return true;
        }
        return false;
    }

    private static final class SpectralAnalysis {
        private final double[] real = new double[WINDOW];
        private final double[] imaginary = new double[WINDOW];
        private final double[] bandEnergy = new double[BANDS];

        float[] frame(float[] samples, int offset) {
            double squareSum = 0;
            for (int i = 0; i < WINDOW; i++) {
                float sample = samples[offset + i];
                squareSum += sample * sample;
                real[REVERSED[i]] = HANN[i] * sample;
            }
            float rms = (float) Math.sqrt(squareSum / WINDOW);
            if (rms < MIN_RMS) {
                return SILENCE;
            }
            Arrays.fill(imaginary, 0);
            fft();
            Arrays.fill(bandEnergy, 0);
            double peak = 0;
            for (int bin = 1; bin < BAND_BY_BIN.length; bin++) {
                int band = BAND_BY_BIN[bin];
                if (band >= 0) {
                    bandEnergy[band] += real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
                    peak = Math.max(peak, bandEnergy[band]);
                }
            }
            if (peak == 0) {
                return SILENCE;
            }
            float[] result = new float[BANDS + 1];
            double baseline = 0;
            double floor = peak * 0.003;
            for (int band = 0; band < BANDS; band++) {
                result[band + 1] = (float) Math.log1p(bandEnergy[band] / floor);
                baseline += result[band + 1];
            }
            baseline /= BANDS;
            double magnitude = 0;
            for (int band = 1; band <= BANDS; band++) {
                result[band] -= (float) baseline;
                magnitude += result[band] * result[band];
            }
            if (magnitude < 1e-8) {
                return SILENCE;
            }
            float norm = (float) Math.sqrt(magnitude);
            for (int band = 1; band <= BANDS; band++) {
                result[band] /= norm;
            }
            result[0] = rms;
            return result;
        }

        private void fft() {
            for (int size = 2; size <= WINDOW; size *= 2) {
                int half = size / 2;
                int stride = WINDOW / size;
                for (int start = 0; start < WINDOW; start += size) {
                    for (int position = 0; position < half; position++) {
                        int even = start + position;
                        int odd = even + half;
                        int phase = position * stride;
                        double r = real[odd] * COS[phase] - imaginary[odd] * SIN[phase];
                        double i = real[odd] * SIN[phase] + imaginary[odd] * COS[phase];
                        real[odd] = real[even] - r;
                        imaginary[odd] = imaginary[even] - i;
                        real[even] += r;
                        imaginary[even] += i;
                    }
                }
            }
        }
    }
}
