import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

final class CounterStore {
    static final int MAX_REFERENCES = 6;
    static final int MIN_CLIP_MS = 600;
    static final int MAX_CLIP_MS = 2500;
    private static final int FORMAT = 0x46434301;
    private final Path file;

    CounterStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("contador.dat");
    }

    record State(long total, List<float[][]> references) {
        State {
            references = List.copyOf(references);
        }
    }

    State load() throws IOException {
        if (Files.notExists(file)) {
            return new State(0, List.of());
        }
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != FORMAT) {
                throw new IOException("Formato de dados desconhecido.");
            }
            long total = input.readLong();
            int count = input.readInt();
            if (total < 0 || count < 0 || count > MAX_REFERENCES) {
                throw new IOException("Dados do contador inválidos.");
            }
            List<float[][]> references = new ArrayList<>();
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
                references.add(reference);
            }
            return new State(total, references);
        }
    }

    void save(State state) throws IOException {
        Path temporary = file.resolveSibling("contador.tmp");
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(FORMAT);
                output.writeLong(state.total());
                output.writeInt(state.references().size());
                for (float[][] reference : state.references()) {
                    output.writeInt(reference.length);
                    for (float[] frame : reference) {
                        for (float value : frame) {
                            output.writeFloat(value);
                        }
                    }
                }
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
