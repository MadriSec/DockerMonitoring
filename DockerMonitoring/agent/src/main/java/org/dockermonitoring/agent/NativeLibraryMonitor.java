package org.dockermonitoring.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discovers mapped native shared libraries by parsing {@code /proc/self/maps}
 * on Linux. Runs on a daemon thread; state is exposed via
 * {@link #snapshotMappedLibraries()}.
 *
 * <p>Cardinality is capped at {@link #MAX_PATHS}; additional paths are dropped
 * with a one-time stderr warning.
 */
final class NativeLibraryMonitor {

    static final String POLL_INTERVAL_PROPERTY = "dockermonitoring.maps.poll.seconds";
    private static final int MAX_PATHS = 512;

    private static final Set<String> MAPPED_SO = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean WARNED_CAP = new AtomicBoolean(false);
    private static volatile boolean started;

    private NativeLibraryMonitor() {}

    static void startIfEnabled() {
        if (!isLinux()) {
            return;
        }
        String secStr = System.getProperty(POLL_INTERVAL_PROPERTY, "30");
        int interval;
        try {
            interval = Integer.parseInt(secStr.trim());
        } catch (NumberFormatException e) {
            System.err.println("[DockerMonitoring] invalid " + POLL_INTERVAL_PROPERTY
                    + "=" + secStr + "; native library monitor disabled");
            return;
        }
        if (interval <= 0) {
            return;
        }
        if (started) {
            return;
        }
        started = true;

        Thread t = new Thread(() -> runLoop(interval), "dockermonitoring-maps");
        t.setDaemon(true);
        t.start();
        try {
            refresh();
        } catch (IOException e) {
            System.err.println("[DockerMonitoring] initial maps read failed: " + e.getMessage());
        }
        System.err.println("[DockerMonitoring] native library monitor: polling /proc/self/maps every "
                + interval + "s");
    }

    static Set<String> snapshotMappedLibraries() {
        return Collections.unmodifiableSet(MAPPED_SO);
    }

    private static void runLoop(int intervalSec) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(intervalSec * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                refresh();
            } catch (IOException e) {
                System.err.println("[DockerMonitoring] maps poll failed: " + e.getMessage());
            }
        }
    }

    static void refresh() throws IOException {
        Path maps = Path.of("/proc/self/maps");
        if (!Files.isReadable(maps)) {
            return;
        }
        for (String line : Files.readAllLines(maps, StandardCharsets.US_ASCII)) {
            String path = parsePathname(line);
            if (path == null || !path.endsWith(".so")) {
                continue;
            }
            ingest(path);
        }
    }

    /**
     * {@code /proc/self/maps} columns: address perms offset dev inode pathname
     * Pathname may contain spaces; split with limit 6.
     */
    private static String parsePathname(String line) {
        String[] parts = line.trim().split("\\s+", 6);
        if (parts.length < 6) {
            return null;
        }
        String path = parts[5].trim();
        if (path.isEmpty() || path.charAt(0) != '/') {
            return null;
        }
        return path;
    }

    private static void ingest(String path) {
        if (MAPPED_SO.contains(path)) {
            return;
        }
        if (MAPPED_SO.size() >= MAX_PATHS) {
            if (WARNED_CAP.compareAndSet(false, true)) {
                System.err.println("[DockerMonitoring] native library path cap reached ("
                        + MAX_PATHS + "); further .so paths ignored");
            }
            return;
        }
        MAPPED_SO.add(path);
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }
}
