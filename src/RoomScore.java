import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class RoomScore {
    private static final long MAX_CONTRIBUTION = 1_000_000_000L;
    private static final int MAX_PARTICIPANTS = 64;
    private static final long MAX_TOTAL = MAX_CONTRIBUTION * MAX_PARTICIPANTS;
    final UUID id = UUID.randomUUID();
    final boolean host;
    private final Map<UUID, Contribution> peers = new HashMap<>();
    private Contribution own = new Contribution(0, 0);
    private Games personal = new Games(0, 0, 0);
    private Games team = new Games(0, 0, 0);
    private long total;

    record Contribution(long revision, long total) {}

    record Games(long completed, long chests, long baseline) {
        Games finish(long current) {
            return new Games(completed + 1, chests + Math.max(0, current - baseline), current);
        }

        boolean valid() {
            return completed >= 0 && chests >= 0 && baseline >= 0 && baseline <= MAX_TOTAL;
        }
    }

    record Totals(long total, Games team) {}

    RoomScore(boolean host) {
        this.host = host;
        if (host) {
            peers.put(id, own);
        }
    }

    synchronized long ownMatch() { return own.total() - personal.baseline(); }
    synchronized Contribution own() { return own; }
    synchronized Games personalGames() { return personal; }
    synchronized Totals totals() { return new Totals(total, team); }

    synchronized void nextMatch(boolean forTeam) {
        if (!forTeam) {
            personal = personal.finish(own.total());
        } else if (host) {
            team = team.finish(total);
        }
    }

    synchronized long adjust(int amount) {
        long next = Math.clamp(own.total() + amount, personal.baseline(), MAX_CONTRIBUTION);
        if (next != own.total()) {
            if (host) {
                total += next - own.total();
            }
            own = new Contribution(own.revision() + 1, next);
            if (host) {
                peers.put(id, own);
            }
        }
        return ownMatch();
    }

    synchronized void received(long remoteTotal, Games remoteTeam) throws IOException {
        if (remoteTotal < 0 || remoteTotal > MAX_TOTAL) {
            throw new IOException("Total recebido inválido.");
        }
        total = remoteTotal;
        if (remoteTeam != null) {
            team = remoteTeam;
        }
    }

    synchronized Totals accept(UUID peer, Contribution next) throws IOException {
        Contribution old = peers.get(peer);
        if (peer.equals(id) || next.revision() < 0 || next.total() < 0 || next.total() > MAX_CONTRIBUTION
                || (old == null && peers.size() >= MAX_PARTICIPANTS)) {
            throw new IOException("Contribuição inválida.");
        }
        if (old == null || old.revision() < next.revision()) {
            peers.put(peer, next);
            total += next.total() - (old == null ? 0 : old.total());
        }
        return totals();
    }
}
