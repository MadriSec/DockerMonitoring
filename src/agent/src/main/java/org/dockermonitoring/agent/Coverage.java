package org.dockermonitoring.agent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.dockermonitoring.bootstrap.NativeMethodTracker;

/**
 * What happened to each listed native method:
 * <ul>
 *   <li>{@code wrapped}: its class was rewritten, so every call is counted;</li>
 *   <li>{@code preloaded}: its class was already loaded when premain ran, so
 *       it cannot get a wrapper (retransformation cannot add methods) and its
 *       calls are invisible to the agent;</li>
 *   <li>{@code not_loaded}: its class has not been loaded (yet), or the class
 *       has no native method of that name.</li>
 * </ul>
 */
final class Coverage {

    static final String FILE_PROPERTY = "dockermonitoring.coverage.file";

    private static volatile Map<String, Set<String>> listed = Collections.emptyMap();
    private static final Set<String> wrapped = ConcurrentHashMap.newKeySet();
    private static final Set<String> preloadedClasses = ConcurrentHashMap.newKeySet();

    private Coverage() {}

    static void setListed(Map<String, Set<String>> targets) {
        listed = targets;
    }

    static void markWrapped(String dotClassName, List<String> methodNames) {
        for (String m : methodNames) {
            wrapped.add(dotClassName + "." + m);
        }
    }

    static void markPreloaded(String dotClassName) {
        preloadedClasses.add(dotClassName);
    }

    /** method key -> status, for every listed method, sorted by key. */
    static Map<String, String> snapshot() {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : listed.entrySet()) {
            for (String m : e.getValue()) {
                String key = e.getKey() + "." + m;
                String status;
                if (wrapped.contains(key)) {
                    status = "wrapped";
                } else if (preloadedClasses.contains(e.getKey())) {
                    status = "preloaded";
                } else {
                    status = "not_loaded";
                }
                out.put(key, status);
            }
        }
        return out;
    }

    /** Starts a daemon thread that rewrites the coverage file every 30 s and at exit. */
    static void startWriterIfEnabled() {
        String file = System.getProperty(FILE_PROPERTY);
        if (file == null || file.isEmpty()) {
            return;
        }
        Path path = Paths.get(file);
        Thread writer = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30_000);
                    write(path);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    System.err.println("[DockerMonitoring] coverage write failed: " + e);
                }
            }
        }, "dockermonitoring-coverage");
        writer.setDaemon(true);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                write(path);
            } catch (IOException ignored) {
                // best effort
            }
        }, "dockermonitoring-coverage-final"));
    }

    /** Writes {@code status<TAB>Class.method<TAB>invocations}, one listed method per line. */
    static void write(Path path) throws IOException {
        Map<String, LongAdder> counts = NativeMethodTracker.getInstance().getRecordedMethods();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : snapshot().entrySet()) {
                LongAdder n = counts.get(e.getKey());
                w.write(e.getValue() + "\t" + e.getKey() + "\t" + (n == null ? 0 : n.sum()));
                w.newLine();
            }
        }
        tmp.toFile().setReadable(true, false);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
