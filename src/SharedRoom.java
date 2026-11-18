import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
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
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REQUEST_BYTES = 40;
    private static final int RESPONSE_BYTES = 16;
    private static final int STATS_BYTES = 32;
    private static final int TIMEOUT_MS = 3500;
    private static final int STATS_TIMEOUT_MS = 750;
    private final Invite invitation;
    private final Server server;
    private final RoomHistory history;
    private final Thread client;
    private final Semaphore wake = new Semaphore(0);

    record View(long total, boolean online, RoomHistory.Games team, String issue) {
        View(long total, boolean online, RoomHistory.Games team) {
            this(total, online, team, null);
        }
    }

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

    static SharedRoom host(String address, int port, Path file, Consumer<View> update) throws IOException {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        ServerSocket socket = listen(address, port);
        try {
            var invite = new Invite(address, socket.getLocalPort(), key);
            var history = RoomHistory.create(file, invite.link(), true);
            return new SharedRoom(invite, history, new Server(socket, key, history), update);
        } catch (IOException | RuntimeException error) {
            socket.close();
            throw error;
        }
    }

    static SharedRoom join(String invitation, Path file, Consumer<View> update) throws IOException {
        var invite = Invite.parse(invitation);
        return new SharedRoom(invite, RoomHistory.create(file, invite.link(), false), null, update);
    }

    static SharedRoom resume(RoomHistory history, Consumer<View> update) throws IOException {
        var invite = Invite.parse(history.invite);
        var socket = history.host ? listen(invite.host(), invite.port()) : null;
        try {
            history.setResume(true);
        } catch (IOException error) {
            if (socket != null) {
                socket.close();
            }
            throw error;
        }
        return new SharedRoom(invite, history, socket == null ? null : new Server(socket, invite.key(), history), update);
    }

    private static ServerSocket listen(String address, int port) throws IOException {
        var socket = new ServerSocket();
        try {
            socket.bind(new InetSocketAddress(address, port));
            return socket;
        } catch (IOException | RuntimeException error) {
            socket.close();
            throw error;
        }
    }

    private SharedRoom(Invite invitation, RoomHistory history, Server server, Consumer<View> update) {
        this.invitation = invitation;
        this.history = history;
        this.server = server;
        client = Thread.ofPlatform().daemon().name("Contagem em grupo").start(() -> sync(update));
    }

    String invitation() {
        return invitation.link();
    }

    long adjust(int amount) throws IOException {
        long total = history.adjust(amount);
        wake.release();
        return total;
    }

    RoomHistory history() {
        return history;
    }

    private void sync(Consumer<View> update) {
        View previous = null;
        while (!Thread.currentThread().isInterrupted()) {
            View view;
            try {
                if (history.host) {
                    var score = history.score();
                    view = new View(score.total(), true, score.team());
                } else {
                    view = query(history.own());
                    history.received(view.total(), view.team());
                }
            } catch (IOException | GeneralSecurityException | IllegalArgumentException error) {
                var score = history.score();
                view = new View(score.total(), false, score.team(), connectionIssue(error));
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

    private static String connectionIssue(Exception error) {
        return switch (error) {
            case UnknownHostException ignored -> "Endereço do convite não encontrado";
            case ConnectException ignored -> "O destino recusou a ligação";
            case SocketTimeoutException ignored -> "O anfitrião não respondeu a tempo";
            case EOFException ignored -> "O destino fechou a ligação antes de responder";
            case GeneralSecurityException ignored -> "A resposta não corresponde à chave da sala";
            default -> "Não foi possível confirmar a ligação à sala";
        };
    }

    private View query(RoomHistory.Contribution snapshot) throws IOException, GeneralSecurityException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(invitation.host(), invitation.port()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            long nonce = RANDOM.nextLong();
            byte[] request = ByteBuffer.allocate(REQUEST_BYTES)
                    .putLong(history.id.getMostSignificantBits()).putLong(history.id.getLeastSignificantBits())
                    .putLong(snapshot.revision()).putLong(snapshot.total()).putLong(nonce).array();
            send(socket, invitation.key(), request, (byte) 1);
            var response = receive(socket, invitation.key(), RESPONSE_BYTES, (byte) 2);
            if (response.getLong() != nonce) {
                throw new IOException("Resposta da sala inválida.");
            }
            long total = response.getLong();
            // The authenticated total is sufficient to establish that the room is online.
            try {
                socket.setSoTimeout(STATS_TIMEOUT_MS);
                var stats = receive(socket, invitation.key(), STATS_BYTES, (byte) 3);
                if (stats.getLong() == nonce) {
                    var games = new RoomHistory.Games(stats.getLong(), stats.getLong(), stats.getLong());
                    if (games.valid()) {
                        return new View(total, true, games);
                    }
                }
            } catch (IOException | GeneralSecurityException missingStats) {
                // Legacy hosts or a relay may omit or delay the optional statistics packet.
            }
            return new View(total, true, null);
        }
    }

    private static void send(Socket socket, byte[] key, byte[] message, byte type)
            throws IOException, GeneralSecurityException {
        socket.getOutputStream().write(packet(key, message, type));
    }

    private static byte[] packet(byte[] key, byte[] message, byte type) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, iv, type);
        return ByteBuffer.allocate(iv.length + message.length + 16).put(iv).put(cipher.doFinal(message)).array();
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
        history.close();
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
        private final RoomHistory history;

        Server(ServerSocket socket, byte[] key, RoomHistory history) {
            this.key = key;
            this.socket = socket;
            this.history = history;
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
                var contribution = new RoomHistory.Contribution(request.getLong(), request.getLong());
                long nonce = request.getLong();
                var score = history.accept(id, contribution);
                byte[] response = ByteBuffer.allocate(RESPONSE_BYTES).putLong(nonce)
                        .putLong(score.total()).array();
                var stats = score.team();
                byte[] extension = ByteBuffer.allocate(STATS_BYTES).putLong(nonce).putLong(stats.completed())
                        .putLong(stats.chests()).putLong(stats.baseline()).array();
                byte[] totalPacket = packet(key, response, (byte) 2);
                byte[] statsPacket = packet(key, extension, (byte) 3);
                connection.getOutputStream().write(ByteBuffer.allocate(totalPacket.length + statsPacket.length)
                        .put(totalPacket).put(statsPacket).array());
            } catch (IOException | GeneralSecurityException invalid) {
                // Drop unauthenticated, invalid, or incomplete requests.
            }
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
