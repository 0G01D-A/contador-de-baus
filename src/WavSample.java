import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

final class WavSample {
    private WavSample() {}

    static float[] read(Path path) throws IOException {
        var file = path.toFile();
        try {
            if (!AudioFileFormat.Type.WAVE.equals(AudioSystem.getAudioFileFormat(file).getType())) {
                throw new IOException("Seleciona um ficheiro WAV.");
            }
            try (var source = AudioSystem.getAudioInputStream(file)) {
                float rate = source.getFormat().getSampleRate();
                int channels = source.getFormat().getChannels();
                if (!Float.isFinite(rate) || rate < 8000 || rate > 192000 || channels < 1 || channels > 8) {
                    throw new IOException("O formato do áudio não é compatível.");
                }
                var target = new AudioFormat(rate, 16, channels, true, false);
                try (var audio = AudioSystem.getAudioInputStream(target, source)) {
                    int frameSize = target.getFrameSize();
                    int maxFrames = (int) (rate * CounterStore.RECORDING_SECONDS);
                    byte[] pcm = new byte[(maxFrames + 1) * frameSize];
                    int frames = audio.readNBytes(pcm, 0, pcm.length) / frameSize;
                    if (frames > maxFrames || frames * 1000.0 < rate * CounterStore.MIN_CLIP_MS) {
                        throw new IOException("Escolhe um WAV com duração entre 0,6 e 7 segundos.");
                    }
                    return toMono(pcm, frames, channels, rate);
                }
            }
        } catch (UnsupportedAudioFileException | IllegalArgumentException error) {
            throw new IOException("Não foi possível ler o WAV. Experimenta guardar o ficheiro como WAV PCM.", error);
        }
    }

    private static float[] toMono(byte[] pcm, int frames, int channels, float rate) {
        float[] mono = new float[frames];
        int offset = 0;
        for (int frame = 0; frame < frames; frame++) {
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                sum += (short) ((pcm[offset] & 255) | (pcm[offset + 1] << 8));
                offset += 2;
            }
            mono[frame] = sum / (channels * 32768f);
        }
        double step = rate / (double) WindowsAudio.SAMPLE_RATE;
        float[] output = new float[(int) Math.floor(frames / step)];
        for (int i = 0; i < output.length; i++) {
            double start = i * step;
            double end = Math.min(frames, (i + 1) * step);
            double sum = 0;
            for (int frame = (int) start; frame < end; frame++) {
                double overlap = Math.min(frame + 1, end) - Math.max(frame, start);
                sum += mono[frame] * overlap;
            }
            output[i] = (float) (sum / (end - start));
        }
        return output;
    }
}
