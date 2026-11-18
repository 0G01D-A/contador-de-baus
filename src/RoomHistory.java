import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class RoomHistory {
    private static final int FORMAT = 1;
    private static final long MAX_CONTRIBUTION = 1_000_000_000L;
    private static final int MAX_PARTICIPANTS = 64;
    private final Path file;
    final String invite;
    final boolean host;
    final UUID id;
    private boolean resume;
    private boolean closed;
    private Contribution own;
    private long total;
    private Map<UUID, Contribution> peers;

    record Contribution(long revision, long total) {
        boolean valid() {
            return revision >= 0 && total >= 0 && total <= MAX_CONTRIBUTION;
        }
    }

    private RoomHistory(Path file, String invite, boolean host, UUID id, boolean resume,
                        Contribution own, long total, Map<UUID, Contribution> peers) {
        this.file = file;
        this.invite = invite;
        this.host = host;
        this.id = id;
        this.resume = resume;
        this.own = own;
        this.total = total;
        this.peers = peers;
    }

    static RoomHistory create(Path file, String invite, boolean host) throws IOException {
        UUID id = UUID.randomUUID();
        var own = new Contribution(0, 0);
        var room = new RoomHistory(file, invite, host, id, true, own, 0, host ? Map.of(id, own) : Map.of());
        room.save(true, room.own, 0, room.peers);
        return room;
    }

    static RoomHistory load(Path file) throws IOException {
        if (Files.notExists(file)) {
            return null;
        }
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != FORMAT) {
                throw new IOException("Formato da sala guardada desconhecido.");
            }
            String invite = input.readUTF();
            boolean host = input.readBoolean();
            UUID id = readId(input);
            boolean resume = input.readBoolean();
            Contribution own = readContribution(input);
            long total = input.readLong();
            int size = input.readInt();
            if (total < 0 || total > MAX_CONTRIBUTION * MAX_PARTICIPANTS || size < 0 || size > MAX_PARTICIPANTS
                    || (!host && size != 0)) {
                throw new IOException("Dados da sala inválidos.");
            }
            var peers = new HashMap<UUID, Contribution>();
            for (int i = 0; i < size; i++) {
                if (peers.put(readId(input), readContribution(input)) != null) {
                    throw new IOException("Participante duplicado.");
                }
            }
            if (host && total != peers.values().stream().mapToLong(Contribution::total).sum()) {
                throw new IOException("Total da sala inválido.");
            }
            return new RoomHistory(file, invite, host, id, resume, own, total, peers);
        }
    }

    synchronized Contribution own() { return own; }
    synchronized long total() { return total; }
    synchronized boolean shouldResume() { return resume; }

    synchronized void setResume(boolean value) throws IOException {
        if (resume != value) {
            save(value, own, total, peers);
            resume = value;
        }
    }

    synchronized long adjust(int amount) throws IOException {
        long next = Math.clamp(own.total() + amount, 0, MAX_CONTRIBUTION);
        if (next != own.total()) {
            var contribution = new Contribution(own.revision() + 1, next);
            if (host) {
                // Commit the host's contribution and the room total together.
                var updated = new HashMap<>(peers);
                var previous = updated.put(id, contribution);
                long groupTotal = total + next - (previous == null ? 0 : previous.total());
                save(resume, contribution, groupTotal, updated);
                peers = updated;
                total = groupTotal;
            } else {
                save(resume, contribution, total, peers);
            }
            own = contribution;
        }
        return own.total();
    }

    synchronized void received(long value) throws IOException {
        if (!host && value != total) {
            if (value < 0 || value > MAX_CONTRIBUTION * MAX_PARTICIPANTS) {
                throw new IOException("Total recebido inválido.");
            }
            save(resume, own, value, peers);
            total = value;
        }
    }

    synchronized long accept(UUID id, Contribution next) throws IOException {
        Contribution old = peers.get(id);
        if (!next.valid() || (old == null && peers.size() >= MAX_PARTICIPANTS)) {
            throw new IOException("Contribuição inválida.");
        }
        if (old == null || old.revision() < next.revision()) {
            var updated = new HashMap<>(peers);
            updated.put(id, next);
            long groupTotal = total + next.total() - (old == null ? 0 : old.total());
            save(resume, own, groupTotal, updated);
            peers = updated;
            total = groupTotal;
        }
        return total;
    }

    synchronized void close() {
        closed = true;
    }

    private void save(boolean resume, Contribution own, long total, Map<UUID, Contribution> peers) throws IOException {
        if (closed) {
            throw new IOException("A sala já foi fechada.");
        }
        Path temp = Files.createTempFile(file.getParent(), "sala-", ".tmp");
        try {
            try (var raw = new FileOutputStream(temp.toFile());
                 var output = new DataOutputStream(new BufferedOutputStream(raw))) {
                output.writeInt(FORMAT);
                output.writeUTF(invite);
                output.writeBoolean(host);
                writeId(output, id);
                output.writeBoolean(resume);
                writeContribution(output, own);
                output.writeLong(total);
                output.writeInt(peers.size());
                for (var entry : peers.entrySet()) {
                    writeId(output, entry.getKey());
                    writeContribution(output, entry.getValue());
                }
                output.flush();
                raw.getFD().sync();
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static UUID readId(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeId(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static Contribution readContribution(DataInputStream input) throws IOException {
        var result = new Contribution(input.readLong(), input.readLong());
        if (!result.valid()) {
            throw new IOException("Contribuição guardada inválida.");
        }
        return result;
    }

    private static void writeContribution(DataOutputStream output, Contribution value) throws IOException {
        output.writeLong(value.revision());
        output.writeLong(value.total());
    }
}
