import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.AEADBadTagException;
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
    private static final long STATS_INTERVAL_NS = TimeUnit.SECONDS.toNanos(3);
    private final Invite invitation;
    private final Server server;
    private final RoomScore score;
    private final Thread client;
    private final Semaphore wake = new Semaphore(0);

    record View(long total, boolean online, RoomScore.Games team, String issue) {
        View(long total, boolean online, RoomScore.Games team) {
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

    static SharedRoom host(String address, int port, Consumer<View> update) throws IOException {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        ServerSocket socket = listen(address, port);
        var invite = new Invite(address, socket.getLocalPort(), key);
        var score = new RoomScore(true);
        return new SharedRoom(invite, score, new Server(socket, key, score), update);
    }

    static SharedRoom join(String invitation, Consumer<View> update) {
        return new SharedRoom(Invite.parse(invitation), new RoomScore(false), null, update);
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

    private SharedRoom(Invite invitation, RoomScore score, Server server, Consumer<View> update) {
        this.invitation = invitation;
        this.score = score;
        this.server = server;
        client = Thread.ofPlatform().daemon().name("Contagem em grupo").start(() -> sync(update));
    }

    String invitation() {
        return invitation.link();
    }

    long adjust(int amount) {
        long total = score.adjust(amount);
        wake.release();
        return total;
    }

    RoomScore score() {
        return score;
    }

    private void sync(Consumer<View> update) {
        View previous = null;
        long nextStats = System.nanoTime();
        RoomScore.Games remoteTeam = null;
        while (!Thread.currentThread().isInterrupted()) {
            View view;
            try {
                if (score.host) {
                    var totals = score.totals();
                    view = new View(totals.total(), true, totals.team());
                } else {
                    long total = queryTotal();
                    long now = System.nanoTime();
                    if (now - nextStats >= 0) {
                        var games = queryTeam();
                        if (games != null) {
                            remoteTeam = games;
                        }
                        nextStats = now + STATS_INTERVAL_NS;
                    }
                    score.received(total, remoteTeam);
                    view = new View(total, true, remoteTeam);
                }
            } catch (IOException | GeneralSecurityException | IllegalArgumentException error) {
                var totals = score.totals();
                view = new View(totals.total(), false, totals.team(), connectionIssue(error));
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

    private long queryTotal() throws IOException, GeneralSecurityException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(invitation.host(), invitation.port()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            long nonce = RANDOM.nextLong();
            send(socket, invitation.key(), request(nonce), (byte) 1);
            var response = receive(socket, invitation.key(), RESPONSE_BYTES, (byte) 2);
            if (response.getLong() != nonce) {
                throw new IOException("Resposta da sala inválida.");
            }
            return response.getLong();
        }
    }

    private RoomScore.Games queryTeam() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(invitation.host(), invitation.port()), STATS_TIMEOUT_MS);
            socket.setSoTimeout(STATS_TIMEOUT_MS);
            long nonce = RANDOM.nextLong();
            send(socket, invitation.key(), request(nonce), (byte) 4);
            var reply = receive(socket, invitation.key(), STATS_BYTES, (byte) 3);
            if (reply.getLong() == nonce) {
                var games = new RoomScore.Games(reply.getLong(), reply.getLong(), reply.getLong());
                return games.valid() ? games : null;
            }
        } catch (IOException | GeneralSecurityException unavailable) {
            // Hosts without statistics still answer the original count request.
        }
        return null;
    }

    private byte[] request(long nonce) {
        var own = score.own();
        return ByteBuffer.allocate(REQUEST_BYTES)
                .putLong(score.id.getMostSignificantBits()).putLong(score.id.getLeastSignificantBits())
                .putLong(own.revision()).putLong(own.total()).putLong(nonce).array();
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
        return receivePacket(socket, bytes).decode(key, type);
    }

    private record EncryptedPacket(byte[] iv, byte[] data) {
        ByteBuffer decode(byte[] key, byte type) throws GeneralSecurityException {
            return ByteBuffer.wrap(cipher(Cipher.DECRYPT_MODE, key, iv, type).doFinal(data));
        }
    }

    private static EncryptedPacket receivePacket(Socket socket, int bytes) throws IOException {
        var input = socket.getInputStream();
        byte[] iv = input.readNBytes(12);
        byte[] encrypted = input.readNBytes(bytes + 16);
        if (iv.length != 12 || encrypted.length != bytes + 16) {
            throw new EOFException();
        }
        return new EncryptedPacket(iv, encrypted);
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
        private final RoomScore score;

        Server(ServerSocket socket, byte[] key, RoomScore score) {
            this.key = key;
            this.socket = socket;
            this.score = score;
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
                var packet = receivePacket(connection, REQUEST_BYTES);
                ByteBuffer request;
                try {
                    request = packet.decode(key, (byte) 1);
                } catch (AEADBadTagException maybeStats) {
                    var stats = packet.decode(key, (byte) 4);
                    long nonce = stats.getLong(REQUEST_BYTES - Long.BYTES);
                    var games = score.totals().team();
                    byte[] reply = ByteBuffer.allocate(STATS_BYTES).putLong(nonce).putLong(games.completed())
                            .putLong(games.chests()).putLong(games.baseline()).array();
                    send(connection, key, reply, (byte) 3);
                    return;
                }
                var id = new UUID(request.getLong(), request.getLong());
                var contribution = new RoomScore.Contribution(request.getLong(), request.getLong());
                long nonce = request.getLong();
                var totals = score.accept(id, contribution);
                byte[] response = ByteBuffer.allocate(RESPONSE_BYTES).putLong(nonce)
                        .putLong(totals.total()).array();
                send(connection, key, response, (byte) 2);
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
