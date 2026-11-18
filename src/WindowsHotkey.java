import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.lang.foreign.MemorySegment.NULL;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

final class WindowsHotkey implements AutoCloseable {
    private static final int ID = 1;
    private static final int MOD_NOREPEAT = 0x4000;
    private static final int WM_HOTKEY = 0x0312;
    private static final int PM_REMOVE = 1;

    private final LinkedBlockingQueue<Boolean> requests = new LinkedBlockingQueue<>();
    private final Thread worker;

    WindowsHotkey(int key, int modifiers, Runnable pressed, Consumer<Boolean> registrationResult) {
        worker = Thread.ofPlatform().daemon().name("Windows shortcut " + key)
                .start(() -> listen(key, modifiers | MOD_NOREPEAT, pressed, registrationResult));
    }

    void setEnabled(boolean enabled) {
        requests.add(enabled);
    }

    private void listen(int key, int modifiers, Runnable pressed, Consumer<Boolean> registrationResult) {
        try (Arena arena = Arena.ofConfined()) {
            var user32 = SymbolLookup.libraryLookup("user32.dll", arena);
            var linker = Linker.nativeLinker();
            MethodHandle register = linker.downcallHandle(user32.findOrThrow("RegisterHotKey"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            MethodHandle unregister = linker.downcallHandle(user32.findOrThrow("UnregisterHotKey"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MethodHandle peek = linker.downcallHandle(user32.findOrThrow("PeekMessageW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            // Space for the Win32 MSG structure on 64-bit Windows.
            var message = arena.allocate(48, 8);
            boolean registered = false;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Boolean requested = registered ? requests.poll(25, TimeUnit.MILLISECONDS) : requests.take();
                    if (requested != null && requested != registered) {
                        if (requested) {
                            registered = (int) register.invokeExact(NULL, ID, modifiers, key) != 0;
                            registrationResult.accept(registered);
                        } else {
                            int ignored = (int) unregister.invokeExact(NULL, ID);
                            registered = false;
                            // Drop presses queued before the shortcut was disabled.
                            while ((int) peek.invokeExact(message, NULL, WM_HOTKEY, WM_HOTKEY, PM_REMOVE) != 0) {
                                // Drain the thread's hotkey messages.
                            }
                        }
                    }
                    if (registered && (int) peek.invokeExact(message, NULL, WM_HOTKEY, WM_HOTKEY, PM_REMOVE) != 0) {
                        pressed.run();
                    }
                }
            } finally {
                if (registered) {
                    int ignored = (int) unregister.invokeExact(NULL, ID);
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            registrationResult.accept(false);
            System.err.println("Não foi possível registar a tecla " + key + ": " + failure);
        }
    }

    @Override
    public void close() {
        worker.interrupt();
    }

    void awaitClosed() {
        try {
            worker.join(200);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }
}
