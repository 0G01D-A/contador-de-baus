import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

final class CounterStore {
    static final int MAX_REFERENCES = 6;
    static final int MIN_CLIP_MS = 600;
    static final int MAX_CLIP_MS = 2500;
    private static final int FINGERPRINT_ONLY_FORMAT = 0x46434301;
    private static final int FORMAT = 0x46434302;
    private static final int PCM_BYTES_PER_MS = WindowsAudio.SAMPLE_RATE / 1000 * 2;
    private final Path file;

    CounterStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("contador.dat");
    }

    record Sample(float[][] fingerprint, byte[] pcm) {}

    record State(long total, List<Sample> samples) {
        State {
            samples = List.copyOf(samples);
        }
    }

    State load() throws IOException {
        if (Files.notExists(file)) {
            return new State(0, List.of());
        }
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int version = input.readInt();
            if (version != FORMAT && version != FINGERPRINT_ONLY_FORMAT) {
                throw new IOException("Formato de dados desconhecido.");
            }
            long total = input.readLong();
            int count = input.readInt();
            if (total < 0 || count < 0 || count > MAX_REFERENCES) {
                throw new IOException("Dados do contador inválidos.");
            }
            List<Sample> samples = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int length = input.readInt();
                if (length < 12 || length > MAX_CLIP_MS / 20) {
                    throw new IOException("Amostra inválida.");
                }
                float[][] reference = new float[length][ChestDetector.BANDS + 1];
                double weight = 0;
                for (float[] frame : reference) {
                    for (int i = 0; i < frame.length; i++) {
                        float value = input.readFloat();
                        if (!Float.isFinite(value) || value < (i == 0 ? 0 : -1) || value > 1) {
                            throw new IOException("Amostra inválida.");
                        }
                        frame[i] = value;
                    }
                    weight += frame[0];
                }
                if (weight == 0) {
                    throw new IOException("Amostra sem som.");
                }
                byte[] pcm = null;
                if (version == FORMAT) {
                    int bytes = input.readInt();
                    if (bytes != 0) {
                        if (bytes < MIN_CLIP_MS * PCM_BYTES_PER_MS || bytes > MAX_CLIP_MS * PCM_BYTES_PER_MS
                                || bytes % 2 != 0) {
                            throw new IOException("Duração da gravação inválida.");
                        }
                        pcm = new byte[bytes];
                        input.readFully(pcm);
                    }
                }
                samples.add(new Sample(reference, pcm));
            }
            return new State(total, samples);
        }
    }

    void save(State state) throws IOException {
        Path temporary = file.resolveSibling("contador.tmp");
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(FORMAT);
                output.writeLong(state.total());
                output.writeInt(state.samples().size());
                for (Sample sample : state.samples()) {
                    float[][] reference = sample.fingerprint();
                    output.writeInt(reference.length);
                    for (float[] frame : reference) {
                        for (float value : frame) {
                            output.writeFloat(value);
                        }
                    }
                    byte[] pcm = sample.pcm();
                    output.writeInt(pcm == null ? 0 : pcm.length);
                    if (pcm != null) {
                        output.write(pcm);
                    }
                }
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
