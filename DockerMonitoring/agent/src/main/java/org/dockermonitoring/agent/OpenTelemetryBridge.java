package org.dockermonitoring.agent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import org.dockermonitoring.bootstrap.NativeMethodTracker;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bridges {@link NativeMethodTracker} and {@link NativeLibraryMonitor} into
 * OpenTelemetry metrics via OTLP/HTTP. Runs entirely in the system
 * classloader — the bootstrap tracker stays OTel-free.
 *
 * <p>Enable by setting {@code dockermonitoring.otlp.endpoint} or the standard
 * {@code OTEL_EXPORTER_OTLP_ENDPOINT} environment variable to the OTLP base
 * URL (e.g. {@code http://localhost:4318}).
 */
final class OpenTelemetryBridge {

    static final String ENDPOINT_PROPERTY = "dockermonitoring.otlp.endpoint";
    static final String INTERVAL_PROPERTY = "dockermonitoring.otlp.export.interval.seconds";
    static final String SERVICE_NAME_PROPERTY = "dockermonitoring.service.name";

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile OpenTelemetrySdk sdk;

    private OpenTelemetryBridge() {}

    static void startIfEnabled() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        String ep = System.getProperty(ENDPOINT_PROPERTY);
        if (ep == null || ep.isEmpty()) {
            String env = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
            if (env != null && !env.isEmpty()) {
                ep = env;
            }
        }
        if (ep == null || ep.isEmpty()) {
            STARTED.set(false);
            return;
        }
        ep = normalizeEndpoint(ep);

        int intervalSec = 10;
        String is = System.getProperty(INTERVAL_PROPERTY);
        if (is != null && !is.isEmpty()) {
            try {
                intervalSec = Math.max(1, Integer.parseInt(is.trim()));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }

        String serviceName = System.getProperty(SERVICE_NAME_PROPERTY);
        if (serviceName == null || serviceName.isEmpty()) {
            serviceName = System.getenv("OTEL_SERVICE_NAME");
        }
        if (serviceName == null || serviceName.isEmpty()) {
            serviceName = "docker-monitoring-agent";
        }

        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), serviceName)));

        OtlpHttpMetricExporter exporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(ep)
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(
                        PeriodicMetricReader.builder(exporter)
                                .setInterval(Duration.ofSeconds(intervalSec))
                                .build())
                .build();

        sdk = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build();

        var meter = sdk.getMeter("org.dockermonitoring.agent");

        meter.gaugeBuilder("dockermonitoring.native_method.invocations")
                .setDescription("Native method invocation counts (instrumented wrappers only)")
                .ofLongs()
                .buildWithCallback(r -> {
                    for (Map.Entry<String, LongAdder> e
                            : NativeMethodTracker.getInstance().getRecordedMethods().entrySet()) {
                        String key = e.getKey();
                        int ld = key.lastIndexOf('.');
                        String cls = ld > 0 ? key.substring(0, ld) : "";
                        String meth = ld > 0 ? key.substring(ld + 1) : key;
                        r.record(e.getValue().sum(), Attributes.of(
                                AttributeKey.stringKey("class"), cls,
                                AttributeKey.stringKey("method"), meth));
                    }
                });

        meter.gaugeBuilder("dockermonitoring.native_methods.distinct")
                .setDescription("Count of distinct instrumented native methods observed")
                .ofLongs()
                .buildWithCallback(r ->
                        r.record(NativeMethodTracker.getInstance().getRecordedMethods().size()));

        meter.gaugeBuilder("dockermonitoring.native_library.mapped")
                .setDescription("Mapped .so path seen in /proc/self/maps (value always 1)")
                .ofLongs()
                .buildWithCallback(r -> {
                    for (String path : NativeLibraryMonitor.snapshotMappedLibraries()) {
                        r.record(1L, Attributes.of(AttributeKey.stringKey("path"), path));
                    }
                });

        OpenTelemetrySdk finalSdk = sdk;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                finalSdk.close();
            } catch (Exception ignored) {
                // ignore
            }
        }, "dockermonitoring-otel-shutdown"));

        System.err.println("[DockerMonitoring] OTLP metrics → " + ep
                + " (every " + intervalSec + "s, service=" + serviceName + ")");
    }

    private static String normalizeEndpoint(String raw) {
        String e = raw.trim();
        if (e.endsWith("/")) {
            return e.substring(0, e.length() - 1);
        }
        return e;
    }
}
