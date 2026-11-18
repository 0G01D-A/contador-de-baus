import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

record Bindings(Bind add, Bind subtract) {
    static final List<Key> KEYS = keys();
    static final Bindings DEFAULT = new Bindings(new Bind(key(0x78), Modifiers.NONE),
            new Bind(key(0x77), Modifiers.NONE));

    record Key(int code, String name) {
        @Override public String toString() { return name; }
    }

    enum Modifiers {
        NONE(0, ""), CTRL(2, "Ctrl"), ALT(1, "Alt"), SHIFT(4, "Shift"),
        CTRL_SHIFT(6, "Ctrl + Shift"), CTRL_ALT(3, "Ctrl + Alt"),
        ALT_SHIFT(5, "Alt + Shift"), CTRL_ALT_SHIFT(7, "Ctrl + Alt + Shift");

        final int flags;
        final String label;

        Modifiers(int flags, String label) {
            this.flags = flags;
            this.label = label;
        }

        @Override public String toString() { return label.isEmpty() ? "Sem modificador" : label; }
    }

    record Bind(Key key, Modifiers modifiers) {
        @Override public String toString() {
            return modifiers == Modifiers.NONE ? key.name() : modifiers.label + " + " + key.name();
        }
    }

    Bindings {
        if (add.equals(subtract)) {
            throw new IllegalArgumentException("Escolhe atalhos diferentes para adicionar e retirar.");
        }
    }

    static Bindings load(Path file) throws IOException {
        if (Files.notExists(file)) {
            return DEFAULT;
        }
        var values = new Properties();
        try (var input = Files.newInputStream(file)) {
            values.load(input);
        }
        return new Bindings(read(values, "add"), read(values, "subtract"));
    }

    void save(Path file) throws IOException {
        var values = new Properties();
        write(values, "add", add);
        write(values, "subtract", subtract);
        Path temp = Files.createTempFile(file.getParent(), "atalhos-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temp)) {
                values.store(output, "Contador de Baus");
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Bind read(Properties values, String name) {
        return new Bind(key(Integer.parseInt(values.getProperty(name + ".key"))),
                Modifiers.valueOf(values.getProperty(name + ".modifiers", "")));
    }

    private static void write(Properties values, String name, Bind bind) {
        values.setProperty(name + ".key", Integer.toString(bind.key().code()));
        values.setProperty(name + ".modifiers", bind.modifiers().name());
    }

    private static Key key(int code) {
        return KEYS.stream().filter(key -> key.code() == code).findFirst().orElseThrow(
                () -> new IllegalArgumentException("Tecla desconhecida."));
    }

    private static List<Key> keys() {
        var keys = new ArrayList<Key>();
        for (int i = 1; i <= 11; i++) {
            keys.add(new Key(0x6f + i, "F" + i));
        }
        for (char key = 'A'; key <= 'Z'; key++) {
            keys.add(new Key(key, Character.toString(key)));
        }
        for (int i = 0; i <= 9; i++) {
            keys.add(new Key('0' + i, Integer.toString(i)));
            keys.add(new Key(0x60 + i, "Num " + i));
        }
        return List.copyOf(keys);
    }
}
