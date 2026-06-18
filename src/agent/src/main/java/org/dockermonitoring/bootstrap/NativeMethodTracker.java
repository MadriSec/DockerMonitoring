package org.dockermonitoring.bootstrap;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Singleton that records native method invocations at runtime.
 *
 * <p>Uses a {@link ConcurrentHashMap} with {@link LongAdder} values for
 * lock-free, low-overhead counting. The first invocation of a method
 * creates the entry (putIfAbsent); subsequent calls only increment the
 * counter, which is a few nanoseconds on the hot path.
 *
 * <p>On JVM shutdown, the recorded methods are dumped to a file whose
 * path is controlled by the {@code dockermonitoring.output.file} system property.
 * A periodic flush (every 30s) also writes the file, guarding against
 * cases where the shutdown hook doesn't fire (e.g. SIGKILL from Docker).
 *
 * <p><strong>Important:</strong> This class must NOT reference any OTel API
 * classes (GlobalOpenTelemetry, LongCounter, etc.) because it is injected
 * as a helper class into the target classloader (including the bootstrap
 * classloader for JDK classes). OTel API classes are not available in the
 * bootstrap classloader, so any reference would cause NoClassDefFoundError
 * and silently break the advice (suppressed by ByteBuddy).
 */
public final class NativeMethodTracker {

    private static final Logger logger = Logger.getLogger(NativeMethodTracker.class.getName());

    private static final String OUTPUT_FILE_PROPERTY = "dockermonitoring.output.file";
    private static final String DEFAULT_OUTPUT_FILE = "runtime_native_methods.txt";
    private static final long FLUSH_INTERVAL_MS = 30_000; // 30 seconds

    private static final NativeMethodTracker INSTANCE = new NativeMethodTracker();

    private final ConcurrentHashMap<String, LongAdder> invocations = new ConcurrentHashMap<>();
    private volatile Timer flushTimer;

    private NativeMethodTracker() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::dumpOnShutdown, "dockermonitoring-dump"));
        startPeriodicFlush();
    }

    public static NativeMethodTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Records a single invocation of a native method.
     *
     * @param methodKey fully-qualified method key, e.g. {@code java.lang.System.nanoTime}
     */
    public void recordInvocation(String methodKey) {
        invocations.computeIfAbsent(methodKey, k -> new LongAdder()).increment();
    }

    /**
     * Returns an unmodifiable view of the methods recorded so far.
     * Each key is a method identifier; the value is the invocation count.
     */
    public Map<String, LongAdder> getRecordedMethods() {
        return Collections.unmodifiableMap(invocations);
    }

    /**
     * Returns the set of method keys that have been invoked at least once.
     */
    public Set<String> getRecordedMethodNames() {
        return Collections.unmodifiableSet(invocations.keySet());
    }

    /**
     * Writes all recorded methods to the specified file.
     * Format: one line per method, tab-separated: {@code className.methodName\tcount}
     */
    public void dumpToFile(Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            invocations.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        try {
                            writer.write(entry.getKey());
                            writer.write('\t');
                            writer.write(Long.toString(entry.getValue().sum()));
                            writer.newLine();
                        } catch (IOException e) {
                            logger.log(Level.WARNING, "Failed to write entry: " + entry.getKey(), e);
                        }
                    });
        }
        // When running inside a Docker container as root, bind-mounted files
        // would otherwise be created mode 0644 and unreadable by the non-root
        // host user driving the capture. Force 0666 so the host can always
        // read the output regardless of which UID wrote it. Silently ignored
        // on non-POSIX filesystems (e.g. Windows).
        try {
            Files.setPosixFilePermissions(outputPath, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE));
        } catch (UnsupportedOperationException | IOException ignore) {
            // Non-POSIX filesystem or insufficient perms — benign.
        }
        String msg = "[DockerMonitoring] dumped " + invocations.size()
                + " native method invocations to " + outputPath;
        logger.info(msg);
        System.err.println(msg);
    }

    /** Resets all recorded state. Intended for testing only. */
    void reset() {
        invocations.clear();
        if (flushTimer != null) {
            flushTimer.cancel();
            flushTimer = null;
        }
    }

    /**
     * Starts a daemon timer that flushes recorded methods to disk every 30s.
     * This guards against cases where the JVM shutdown hook doesn't fire
     * (e.g. Docker sends SIGKILL after timeout, or the entrypoint doesn't
     * propagate SIGTERM to the child JVM process).
     */
    private void startPeriodicFlush() {
        String outputFile = System.getProperty(OUTPUT_FILE_PROPERTY);
        if (outputFile == null || outputFile.isEmpty()) {
            return; // No output path configured — skip periodic flush
        }

        flushTimer = new Timer("dockermonitoring-flush", true); // daemon thread
        flushTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (invocations.isEmpty()) {
                    return;
                }
                try {
                    dumpToFile(Paths.get(outputFile));
                } catch (Exception e) {
                    // Swallow — periodic flush is best-effort
                    System.err.println("[DockerMonitoring] periodic flush failed: " + e.getMessage());
                }
            }
        }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS);
    }

    private void dumpOnShutdown() {
        // Cancel periodic flush — we're doing the final dump now
        if (flushTimer != null) {
            flushTimer.cancel();
        }

        if (invocations.isEmpty()) {
            System.err.println("[DockerMonitoring] shutdown hook: no native method invocations recorded.");
            return;
        }

        String outputFile = System.getProperty(OUTPUT_FILE_PROPERTY, DEFAULT_OUTPUT_FILE);
        try {
            dumpToFile(Paths.get(outputFile));
        } catch (Exception e) {
            System.err.println("[DockerMonitoring] shutdown: failed to dump to " + outputFile + ": " + e);
        }
    }
}
