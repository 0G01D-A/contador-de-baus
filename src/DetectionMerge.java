import java.util.ArrayDeque;

final class DetectionMerge {
    private static final long WINDOW_NS = 1_250_000_000L;
    private final ArrayDeque<Long> audio = new ArrayDeque<>();
    private final ArrayDeque<Long> image = new ArrayDeque<>();

    boolean count(boolean fromAudio, long now) {
        var same = fromAudio ? audio : image;
        var other = fromAudio ? image : audio;
        expire(same, now);
        expire(other, now);
        if (!other.isEmpty()) {
            other.removeFirst();
            return false;
        }
        same.addLast(now);
        return true;
    }

    private static void expire(ArrayDeque<Long> timestamps, long now) {
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_NS) {
            timestamps.removeFirst();
        }
    }
}
