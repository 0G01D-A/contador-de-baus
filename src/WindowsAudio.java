import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.UUID;

import static java.lang.foreign.MemorySegment.NULL;
import static java.lang.foreign.ValueLayout.*;

/** Captures the shared mix of the default Windows playback device through WASAPI. */
final class WindowsAudio implements AutoCloseable {
    static final int SAMPLE_RATE = 16_000;
    private static final int LOOPBACK = 0x00020000;
    private static final int SILENT = 0x2;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OLE = SymbolLookup.libraryLookup("ole32.dll", Arena.global());
    private static final MethodHandle INIT = exported("CoInitializeEx", of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle UNINIT = exported("CoUninitialize", FunctionDescriptor.ofVoid());
    private static final MethodHandle FREE = exported("CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS));
    private static final float[] EMPTY = {};

    private final Arena arena = Arena.ofConfined();
    private final MemorySegment result = arena.allocate(ADDRESS);
    private final MemorySegment frames = arena.allocate(JAVA_INT);
    private final MemorySegment flags = arena.allocate(JAVA_INT);
    private MemorySegment enumerator = NULL;
    private MemorySegment device = NULL;
    private MemorySegment client = NULL;
    private MemorySegment capture = NULL;
    private MethodHandle nextPacket;
    private MethodHandle getBuffer;
    private MethodHandle releaseBuffer;
    private boolean initialized;
    private boolean started;
    private int inputRate;
    private int channels;
    private int blockSize;
    private int sampleBytes;
    private boolean floatingPoint;
    private int phase;
    private int averagedFrames;
    private float sum;

    WindowsAudio() {
        try {
            check("CoInitializeEx", call(INIT, NULL, 2)); // Single-threaded COM apartment.
            initialized = true;
            var create = exported("CoCreateInstance", of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
            check("CoCreateInstance", call(create, guid("BCDE0395-E52F-467C-8E3D-C4579291692E"), NULL, 1,
                    guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), result));
            enumerator = pointer(result);
            check("GetDefaultAudioEndpoint", call(method(enumerator, 4, JAVA_INT, JAVA_INT, ADDRESS),
                    enumerator, 0, 1, result)); // eRender, eMultimedia
            device = pointer(result);
            check("Activate", call(method(device, 3, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), device,
                    guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), 23, NULL, result));
            client = pointer(result);
            check("GetMixFormat", call(method(client, 8, ADDRESS), client, result));
            MemorySegment format = pointer(result);
            try {
                readFormat(format);
                check("Initialize", call(method(client, 3, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS),
                        client, 0, LOOPBACK, 2_000_000L, 0L, format, NULL));
            } finally {
                call(FREE, format);
            }
            check("GetService", call(method(client, 14, ADDRESS, ADDRESS), client,
                    guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), result));
            capture = pointer(result);
            nextPacket = method(capture, 5, ADDRESS);
            getBuffer = method(capture, 3, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
            releaseBuffer = method(capture, 4, JAVA_INT);
            check("Start", call(method(client, 10), client));
            started = true;
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    private void readFormat(MemorySegment pointer) {
        MemorySegment format = pointer.reinterpret(18);
        int tag = unsignedShort(format, 0);
        channels = unsignedShort(format, 2);
        inputRate = format.get(JAVA_INT_UNALIGNED, 4);
        blockSize = unsignedShort(format, 12);
        int bits = unsignedShort(format, 14);
        if (tag == 0xfffe && unsignedShort(format, 16) >= 22) {
            tag = pointer.reinterpret(40).get(JAVA_INT_UNALIGNED, 24);
        }
        floatingPoint = tag == 3 && bits == 32;
        boolean pcm = tag == 1 && (bits == 16 || bits == 24 || bits == 32);
        sampleBytes = bits / 8;
        if ((!floatingPoint && !pcm) || channels == 0 || inputRate < SAMPLE_RATE
                || blockSize < sampleBytes * channels) {
            throw new IllegalStateException("Formato de som incompatível na saída predefinida do Windows.");
        }
    }

    float[] poll() {
        check("GetNextPacketSize", call(nextPacket, capture, frames));
        if (frames.get(JAVA_INT, 0) == 0) {
            return EMPTY;
        }
        int status = call(getBuffer, capture, result, frames, flags, NULL, NULL);
        check("GetBuffer", status);
        int count = frames.get(JAVA_INT, 0);
        if (count == 0) {
            return EMPTY;
        }
        try {
            boolean silent = (flags.get(JAVA_INT, 0) & SILENT) != 0;
            MemorySegment data = silent ? NULL : pointer(result).reinterpret((long) blockSize * count);
            float[] output = new float[(int) (((long) count * SAMPLE_RATE + phase) / inputRate)];
            int written = 0;
            for (int frame = 0; frame < count; frame++) {
                if (!silent) {
                    float mono = 0;
                    long offset = (long) frame * blockSize;
                    for (int channel = 0; channel < channels; channel++) {
                        mono += sample(data, offset + (long) channel * sampleBytes);
                    }
                    sum += mono / channels;
                }
                averagedFrames++;
                phase += SAMPLE_RATE;
                if (phase >= inputRate) {
                    output[written++] = sum / averagedFrames;
                    phase -= inputRate;
                    averagedFrames = 0;
                    sum = 0;
                }
            }
            return output;
        } finally {
            check("ReleaseBuffer", call(releaseBuffer, capture, count));
        }
    }

    private float sample(MemorySegment data, long offset) {
        if (floatingPoint) {
            float sample = data.get(JAVA_FLOAT_UNALIGNED, offset);
            return Float.isFinite(sample) ? sample : 0;
        }
        return switch (sampleBytes) {
            case 2 -> data.get(JAVA_SHORT_UNALIGNED, offset) / 32768f;
            case 3 -> ((data.get(JAVA_BYTE, offset) & 255) | ((data.get(JAVA_BYTE, offset + 1) & 255) << 8)
                    | (data.get(JAVA_BYTE, offset + 2) << 16)) / 8388608f;
            default -> data.get(JAVA_INT_UNALIGNED, offset) / 2147483648f;
        };
    }

    private MemorySegment guid(String value) {
        UUID uuid = UUID.fromString(value);
        MemorySegment segment = arena.allocate(16, 4);
        long high = uuid.getMostSignificantBits();
        segment.set(JAVA_INT, 0, (int) (high >>> 32));
        segment.set(JAVA_SHORT, 4, (short) (high >>> 16));
        segment.set(JAVA_SHORT, 6, (short) high);
        segment.set(JAVA_LONG_UNALIGNED, 8, Long.reverseBytes(uuid.getLeastSignificantBits()));
        return segment;
    }

    private static int unsignedShort(MemorySegment segment, long offset) {
        return Short.toUnsignedInt(segment.get(JAVA_SHORT_UNALIGNED, offset));
    }

    private static MemorySegment pointer(MemorySegment segment) {
        return segment.get(ADDRESS, 0);
    }

    private static MethodHandle exported(String name, FunctionDescriptor signature) {
        return LINKER.downcallHandle(OLE.findOrThrow(name), signature);
    }

    private static FunctionDescriptor of(ValueLayout output, ValueLayout... parameters) {
        return FunctionDescriptor.of(output, parameters);
    }

    private static MethodHandle method(MemorySegment instance, int slot, ValueLayout... parameters) {
        ValueLayout[] signature = new ValueLayout[parameters.length + 1];
        signature[0] = ADDRESS;
        System.arraycopy(parameters, 0, signature, 1, parameters.length);
        MemorySegment table = pointer(instance.reinterpret(ADDRESS.byteSize()));
        MemorySegment function = table.reinterpret((slot + 1) * ADDRESS.byteSize())
                .getAtIndex(ADDRESS, slot);
        return LINKER.downcallHandle(function, of(JAVA_INT, signature));
    }

    private static int call(MethodHandle handle, Object... arguments) {
        try {
            Object value = handle.invokeWithArguments(arguments);
            return value == null ? 0 : (int) value;
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException(error);
        }
    }

    private static void check(String operation, int code) {
        if (code < 0) {
            throw new IllegalStateException(operation + " falhou (0x%08X).".formatted(code));
        }
    }

    @Override
    public void close() {
        if (started) {
            call(method(client, 11), client);
        }
        release(capture);
        release(client);
        release(device);
        release(enumerator);
        if (initialized) {
            call(UNINIT);
        }
        arena.close();
    }

    private static void release(MemorySegment instance) {
        if (!instance.equals(NULL)) {
            call(method(instance, 2), instance);
        }
    }
}
