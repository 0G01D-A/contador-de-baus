import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class SharedRoom implements AutoCloseable {
    static final int PORT = 47855;
    private static final long MAX_CONTRIBUTION = 1_000_000_000L;
    private static final int MAX_PARTICIPANTS = 64;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REQUEST_BYTES = 40;
    private static final int RESPONSE_BYTES = 16;
    private static final int TIMEOUT_MS = 1500;
    private final Invite invitation;
    private final Server server;
    private final UUID participant = UUID.randomUUID();
    private final Thread client;
    private final Semaphore wake = new Semaphore(0);
    private Contribution contribution = new Contribution(0, 0);

    record View(long total, boolean online) {}
    private record Contribution(long revision, long total) {}

    private record Invite(String host, int port, byte[] key) {
        static Invite parse(String text) {
            URI uri = URI.create(text.strip());
            if (!"baus".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1
                    || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath().length() != 44) {
                throw new IllegalArgumentException("Convite inválido.");
            }
            byte[] key = Base64.getUrlDecoder().decode(uri.getRawPath().substring(1));
            if (key.length != 32) {
                throw new IllegalArgumentException("Convite inválido.");
            }
            return new Invite(uri.getHost(), uri.getPort(), key);
        }

        String link() {
            return "baus://" + host + ":" + port + "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        }
    }

    static SharedRoom host(String address, int port, Consumer<View> update) throws IOException {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        var server = new Server(address, port, key);
        return new SharedRoom(new Invite(address, server.socket.getLocalPort(), key), server, update);
    }

    static SharedRoom join(String invitation, Consumer<View> update) {
        return new SharedRoom(Invite.parse(invitation), null, update);
    }

    private SharedRoom(Invite invitation, Server server, Consumer<View> update) {
        this.invitation = invitation;
        this.server = server;
        client = Thread.ofPlatform().daemon().name("Contagem em grupo").start(() -> sync(update));
    }

    String invitation() {
        return invitation.link();
    }

    synchronized void adjust(int amount) {
        long next = Math.clamp(contribution.total() + amount, 0, MAX_CONTRIBUTION);
        if (next != contribution.total()) {
            contribution = new Contribution(contribution.revision() + 1, next);
            wake.release();
        }
    }

    private synchronized Contribution snapshot() {
        return contribution;
    }

    private void sync(Consumer<View> update) {
        View previous = null;
        while (!Thread.currentThread().isInterrupted()) {
            View view;
            try {
                view = query(invitation.host(), snapshot());
            } catch (IOException | GeneralSecurityException | IllegalArgumentException error) {
                view = new View(previous == null ? 0 : previous.total(), false);
            }
            if (!view.equals(previous)) {
                update.accept(view);
                previous = view;
            }
            try {
                wake.tryAcquire(750, TimeUnit.MILLISECONDS);
                wake.drainPermits();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private View query(String address, Contribution snapshot) throws IOException, GeneralSecurityException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, invitation.port()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            long nonce = RANDOM.nextLong();
            byte[] request = ByteBuffer.allocate(REQUEST_BYTES)
                    .putLong(participant.getMostSignificantBits()).putLong(participant.getLeastSignificantBits())
                    .putLong(snapshot.revision()).putLong(snapshot.total()).putLong(nonce).array();
            send(socket, invitation.key(), request, (byte) 1);
            var response = receive(socket, invitation.key(), RESPONSE_BYTES, (byte) 2);
            if (response.getLong() != nonce) {
                throw new IOException("Resposta da sala inválida.");
            }
            return new View(response.getLong(), true);
        }
    }

    private static void send(Socket socket, byte[] key, byte[] message, byte type)
            throws IOException, GeneralSecurityException {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, iv, type);
        var output = socket.getOutputStream();
        output.write(iv);
        output.write(cipher.doFinal(message));
    }

    private static ByteBuffer receive(Socket socket, byte[] key, int bytes, byte type)
            throws IOException, GeneralSecurityException {
        var input = socket.getInputStream();
        byte[] iv = input.readNBytes(12);
        byte[] encrypted = input.readNBytes(bytes + 16);
        if (iv.length != 12 || encrypted.length != bytes + 16) {
            throw new EOFException();
        }
        return ByteBuffer.wrap(cipher(Cipher.DECRYPT_MODE, key, iv, type).doFinal(encrypted));
    }

    private static Cipher cipher(int mode, byte[] key, byte[] iv, byte type) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(new byte[]{type});
        return cipher;
    }

    @Override
    public void close() {
        client.interrupt();
        if (server != null) {
            server.close();
        }
    }

    private static final class Server implements AutoCloseable {
        private final ServerSocket socket;
        private final byte[] key;
        private final ExecutorService workers = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofVirtual().factory());
        private final Map<UUID, Contribution> participants = new HashMap<>();
        private long total;

        Server(String address, int port, byte[] key) throws IOException {
            this.key = key;
            socket = new ServerSocket();
            try {
                socket.bind(new InetSocketAddress(address, port));
            } catch (IOException error) {
                socket.close();
                throw error;
            }
            Thread.ofPlatform().daemon().name("Anfitrião da sala").start(this::accept);
        }

        private void accept() {
            while (!socket.isClosed()) {
                try {
                    Socket connection = socket.accept();
                    try {
                        workers.execute(() -> handle(connection));
                    } catch (RejectedExecutionException busy) {
                        connection.close();
                    }
                } catch (IOException closed) {
                    break;
                }
            }
        }

        private void handle(Socket connection) {
            try (connection) {
                connection.setSoTimeout(TIMEOUT_MS);
                var request = receive(connection, key, REQUEST_BYTES, (byte) 1);
                var id = new UUID(request.getLong(), request.getLong());
                var contribution = new Contribution(request.getLong(), request.getLong());
                long nonce = request.getLong();
                long roomTotal = update(id, contribution);
                byte[] response = ByteBuffer.allocate(RESPONSE_BYTES).putLong(nonce)
                        .putLong(roomTotal).array();
                send(connection, key, response, (byte) 2);
            } catch (IOException | GeneralSecurityException invalid) {
                // Drop unauthenticated, invalid, or incomplete requests.
            }
        }

        private synchronized long update(UUID id, Contribution next) throws IOException {
            Contribution old = participants.get(id);
            if (next.revision() < 0 || next.total() < 0 || next.total() > MAX_CONTRIBUTION
                    || (old == null && participants.size() >= MAX_PARTICIPANTS)) {
                throw new IOException("Contribuição inválida.");
            }
            // The client sends an absolute total with a revision, so retries cannot count twice.
            if (old == null || old.revision() < next.revision()) {
                participants.put(id, next);
                total += next.total() - (old == null ? 0 : old.total());
            }
            return total;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The room is already closing.
            } finally {
                workers.shutdownNow();
            }
        }
    }
}
