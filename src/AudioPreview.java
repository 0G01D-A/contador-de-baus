import java.awt.Component;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.swing.JOptionPane;

final class AudioPreview implements AutoCloseable {
    private Clip playback;

    static byte[] toPcm(float[] samples) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int value = Math.round(Math.clamp(samples[i], -1f, 1f) * 32767);
            pcm[i * 2] = (byte) value;
            pcm[i * 2 + 1] = (byte) (value >> 8);
        }
        return pcm;
    }

    void play(Component owner, byte[] pcm) {
        close();
        try {
            playback = AudioSystem.getClip();
            playback.open(new AudioFormat(WindowsAudio.SAMPLE_RATE, 16, 1, true, false), pcm, 0, pcm.length);
            playback.start();
        } catch (LineUnavailableException | IllegalArgumentException error) {
            close();
            JOptionPane.showMessageDialog(owner, "Não foi possível reproduzir o trecho. " + error.getMessage());
        }
    }

    @Override
    public void close() {
        if (playback != null) {
            playback.close();
            playback = null;
        }
    }
}
