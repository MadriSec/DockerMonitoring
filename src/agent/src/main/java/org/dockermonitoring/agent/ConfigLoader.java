package org.dockermonitoring.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the list of native methods to wrap from the file pointed to by the
 * {@code dockermonitoring.native.methods.file} system property.
 *
 * <p>File format: one fully-qualified method per line, e.g.
 * <pre>
 * # comments start with '#'
 * com.example.Foo.computeHash
 * com.example.Foo.anotherNative
 * org.hyperic.sigar.Sigar.getPid
 * </pre>
 *
 * <p>Lines without a dot, blank lines, and lines beginning with {@code #}
 * are skipped. The last dot separates the class name (left) from the method
 * name (right), so overloaded method names collapse into a single entry
 * (both overloads will be wrapped; the config file doesn't need descriptors).
 */
final class ConfigLoader {

    static final String CONFIG_PROPERTY = "dockermonitoring.native.methods.file";

    private ConfigLoader() {}

    /**
     * Loads the config file, if present, and returns an immutable map
     * of {@code dotClassName -> Set<methodName>}. Returns an empty map
     * when the property is unset or the file is missing/empty; the
     * caller treats an empty map as "no-op mode".
     */
    static Map<String, Set<String>> load() {
        String path = System.getProperty(CONFIG_PROPERTY);
        if (path == null || path.isEmpty()) {
            System.err.println("[DockerMonitoring] " + CONFIG_PROPERTY + " not set; "
                    + "no native methods will be wrapped");
            return Collections.emptyMap();
        }

        Path file = Paths.get(path);
        if (!Files.isRegularFile(file)) {
            System.err.println("[DockerMonitoring] config file not found: " + file);
            return Collections.emptyMap();
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[DockerMonitoring] failed to read " + file + ": " + e);
            return Collections.emptyMap();
        }

        Map<String, Set<String>> out = new HashMap<>();
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int lastDot = line.lastIndexOf('.');
            if (lastDot <= 0 || lastDot == line.length() - 1) {
                System.err.println("[DockerMonitoring] skipping malformed line " + lineNo
                        + " in " + file + ": '" + raw + "'");
                continue;
            }
            String className = line.substring(0, lastDot);
            String methodName = line.substring(lastDot + 1);
            out.computeIfAbsent(className, k -> new HashSet<>()).add(methodName);
        }

        return Collections.unmodifiableMap(out);
    }
}
