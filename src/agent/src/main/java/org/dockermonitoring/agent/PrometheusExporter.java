package org.dockermonitoring.agent;

import org.dockermonitoring.bootstrap.NativeMethodTracker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight Prometheus text-exposition exporter for DockerMonitoring.
 *
 * <p>Starts a small HTTP server (JDK built-in, no new deps) and serves
 * {@code /metrics} in the standard Prometheus text format. On every
 * scrape, the handler pulls the current counters straight out of
 * {@link NativeMethodTracker} — no background aggregation, no additional
 * allocation on the hot path.
 *
 * <p>Lives in the system classloader, not the bootstrap one. The tracker
 * is a bootstrap class but its {@link NativeMethodTracker#getInstance()}
 * is public, so the exporter can reach it via normal delegation.
 *
 * <p>Intentionally does NOT depend on the OpenTelemetry SDK. That SDK is
 * ~5 MB shaded and would have required extensive rework of the agent's
 * classloader layout. Prometheus text format is trivially small, Grafana
 * has first-class support via the Prometheus datasource, and for the
 * "which natives fire" use case we don't need OTel's semantics (traces,
 * logs, resource attributes).
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code dockermonitoring.metrics.port} — port to bind. {@code -1} or
 *       unset = disabled (default). {@code 9464} is the de-facto
 *       Prometheus JVM exporter port.</li>
 *   <li>{@code dockermonitoring.metrics.host} — bind address. Defaults to
 *       {@code 0.0.0.0} so containers expose it through port mappings.</li>
 * </ul>
 */
final class PrometheusExporter {

    static final String PORT_PROPERTY = "dockermonitoring.metrics.port";
    static final String HOST_PROPERTY = "dockermonitoring.metrics.host";
    static final String DEFAULT_HOST = "0.0.0.0";
    static final String METRICS_PATH = "/metrics";

    private PrometheusExporter() {}

    /**
     * Starts the exporter if {@code dockermonitoring.metrics.port} is set to a
     * valid port. Returns silently on any failure — the exporter is a
     * best-effort side channel and must never take the main JVM down.
     */
    static void startIfEnabled() {
        String portStr = System.getProperty(PORT_PROPERTY);
        if (portStr == null || portStr.isEmpty()) {
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            System.err.println("[DockerMonitoring] invalid " + PORT_PROPERTY
                    + "=" + portStr + "; metrics exporter disabled");
            return;
        }
        if (port < 0) {
            return;
        }

        String host = System.getProperty(HOST_PROPERTY, DEFAULT_HOST);

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext(METRICS_PATH, new MetricsHandler());
            server.createContext("/", new IndexHandler());
            // Daemon executor so the JVM can exit cleanly on shutdown.
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "dockermonitoring-prom");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            System.err.println("[DockerMonitoring] Prometheus exporter listening on "
                    + host + ":" + port + METRICS_PATH);
            // HttpServer's internal dispatcher is a non-daemon thread;
            // ensure it's shut down cleanly when the JVM is exiting so we
            // don't hold up shutdown in case the app's main() returns.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { server.stop(0); } catch (Exception ignored) {}
            }, "dockermonitoring-prom-stop"));
        } catch (IOException e) {
            System.err.println("[DockerMonitoring] failed to start Prometheus exporter on "
                    + host + ":" + port + ": " + e.getMessage()
                    + " (continuing without metrics)");
        }
    }

    /** Serves the Prometheus scrape endpoint. */
    private static final class MetricsHandler implements HttpHandler {
        private static final String CONTENT_TYPE =
                "text/plain; version=0.0.4; charset=utf-8";

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = render().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        private String render() {
            Map<String, LongAdder> snapshot = NativeMethodTracker.getInstance().getRecordedMethods();
            StringBuilder sb = new StringBuilder(512 + snapshot.size() * 96);

            sb.append("# HELP dockermonitoring_native_invocations_total ")
                    .append("Native method invocations (instrumented wrappers)\n");
            sb.append("# TYPE dockermonitoring_native_invocations_total counter\n");
            for (Entry<String, LongAdder> e : snapshot.entrySet()) {
                String key = e.getKey();
                int lastDot = key.lastIndexOf('.');
                String className;
                String methodName;
                if (lastDot > 0) {
                    className = key.substring(0, lastDot);
                    methodName = key.substring(lastDot + 1);
                } else {
                    className = "";
                    methodName = key;
                }
                sb.append("dockermonitoring_native_invocations_total{class=\"")
                        .append(escape(className))
                        .append("\",method=\"")
                        .append(escape(methodName))
                        .append("\"} ")
                        .append(e.getValue().sum())
                        .append('\n');
            }

            sb.append("# HELP dockermonitoring_unique_native_methods ")
                    .append("Distinct native methods observed so far\n");
            sb.append("# TYPE dockermonitoring_unique_native_methods gauge\n");
            sb.append("dockermonitoring_unique_native_methods ").append(snapshot.size()).append('\n');

            sb.append("# HELP dockermonitoring_native_library_mapped ")
                    .append("Mapped .so from /proc/self/maps (1 if present)\n");
            sb.append("# TYPE dockermonitoring_native_library_mapped gauge\n");
            for (String path : NativeLibraryMonitor.snapshotMappedLibraries()) {
                sb.append("dockermonitoring_native_library_mapped{path=\"")
                        .append(escape(path))
                        .append("\"} 1\n");
            }

            sb.append("# HELP dockermonitoring_exporter_up Exporter health (always 1)\n");
            sb.append("# TYPE dockermonitoring_exporter_up gauge\n");
            sb.append("dockermonitoring_exporter_up 1\n");
            return sb.toString();
        }

        /**
         * Prometheus label values must escape backslash, newline, and
         * double quote. Class/method names in the JVM can legally contain
         * {@code $} (inner classes), quotes in package-info annotations,
         * etc., so we defensively escape all three even though the common
         * case is a straightforward FQCN.
         */
        private static String escape(String s) {
            if (s.indexOf('\\') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) {
                return s;
            }
            StringBuilder out = new StringBuilder(s.length() + 8);
            for (int i = 0, n = s.length(); i < n; i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\\': out.append("\\\\"); break;
                    case '"':  out.append("\\\""); break;
                    case '\n': out.append("\\n");  break;
                    default:   out.append(c);
                }
            }
            return out.toString();
        }
    }

    /** Redirects {@code /} to {@code /metrics} so browsers see the scrape body immediately. */
    private static final class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Location", METRICS_PATH);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }
    }
}
