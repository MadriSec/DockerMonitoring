# DockerMonitoring

Single project for **Java native-method runtime visibility** and **seccomp / syscall observability** (Prometheus + Grafana).

## Components

| Path | Purpose |
|------|--------|
| [`agent/`](agent/) | `-javaagent` JAR: wraps configured JNI natives, counts calls, optional OTLP + Prometheus scrape, `/proc/self/maps` `.so` discovery (Linux). |
| [`seccomp-exporter/`](seccomp-exporter/) | Go service: **sysdig** NDJSON on stdin → Prometheus `/metrics` (`seccomp_*` counters; allowlist + coverage). |
| [`docs/`](docs/) | Runbooks: seccomp + sysdig pinning, allowlist format, composing with Prometheus. |
| Observability stack | [../observability](../observability) — Prometheus (2s scrape) + Grafana dashboard UID `echotrace-native`. From repo root: `docker compose up --build`. |

## Observability + capture (repo root)

From **`DockerMonitoring/`**: **`./start_dashboard.sh`** and **`./otel_capture.sh`** (wrappers → repo root).

From **repo root** (`Echotrace/`): **`./start_dashboard.sh`** and **`./otel_capture.sh`**.

1. **`./start_dashboard.sh`** uses **Grafana :3030** and **Prometheus :9095** on the host by default (see `observability/docker-compose.yml`) so it does not fight with other stacks on **:3000** / **:9090**.
2. **`otel_capture.sh`** — instrumented twin capture.
3. **Syscall demo metrics** (Prometheus `seccomp_*`): from repo root `./run_seccomp_exporter_demo.sh`, or from here **`./run_seccomp_exporter_demo.sh`** (wrapper).

## Quick start — Java agent

From the **DockerMonitoring/agent** directory (Maven + JDK 11+):

```bash
cd DockerMonitoring/agent
export JAVA_HOME=/path/to/jdk-11+
mvn clean package -DskipTests
./run-sample.sh
```

Artifact: `DockerMonitoring/agent/target/docker-monitoring-agent.jar`

### JVM flags

| Property | Meaning |
|----------|--------|
| `-Ddockermonitoring.native.methods.file=/path/to/list.txt` | One line per `ClassName.methodName` to wrap. |
| `-Ddockermonitoring.output.file=/path/to/runtime_native_methods.txt` | Periodic + shutdown dump (tab-separated counts). |
| `-Ddockermonitoring.metrics.port=9464` | Optional Prometheus text on `http://0.0.0.0:9464/metrics`. |
| `-Ddockermonitoring.metrics.host=0.0.0.0` | Bind address for the Prometheus text exporter (default `0.0.0.0`). |
| `-Ddockermonitoring.maps.poll.seconds=30` | Linux: poll `/proc/self/maps` for `.so` paths (`0` = off). |
| `-Ddockermonitoring.otlp.endpoint=http://collector:4318` | OTLP/HTTP **metrics** base URL (see OpenTelemetry below). |
| `-Ddockermonitoring.otlp.export.interval.seconds=10` | OTLP export interval (seconds). |
| `-Ddockermonitoring.service.name=myapp` | Resource `service.name` on OTLP metrics (optional). |

Native method bytecode uses prefix `$$dm$$_` and `Instrumentation.setNativeMethodPrefix`.

### OpenTelemetry (OTLP/HTTP metrics)

The agent starts `OpenTelemetryBridge` when **either** `-Ddockermonitoring.otlp.endpoint` **or** the standard env var `OTEL_EXPORTER_OTLP_ENDPOINT` is set (non-empty). Use the OTLP/HTTP base URL your collector expects (often `http://otel-collector:4318`). If the SDK fails to export with HTTP 404, try appending `/v1/metrics` to match your OpenTelemetry Java exporter version.

| Input | Meaning |
|--------|--------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Same as `-Ddockermonitoring.otlp.endpoint` if the system property is unset. |
| `OTEL_SERVICE_NAME` | Same as `-Ddockermonitoring.service.name` if the property is unset. |
| `-Ddockermonitoring.otlp.export.interval.seconds` | Export interval; default `10`. |

**Instrument names** (OTLP gauges, dot-separated): `dockermonitoring.native_method.invocations` (attributes `class`, `method`), `dockermonitoring.native_methods.distinct`, `dockermonitoring.native_library.mapped` (attribute `path`). These complement the Prometheus text names `dockermonitoring_*` on `:9464`.

## Docker twin capture

From **`DockerMonitoring/`** ([`otel_capture.sh`](otel_capture.sh)): copies the built agent JAR and a native-methods file **into an already-running container** and prints JVM flags (`-javaagent`, `-Ddockermonitoring.*`, optional OTLP env vars). Restart the JVM (or recreate the workload) with those flags and map **9464** for Prometheus.

```bash
cd DockerMonitoring
./otel_capture.sh my_container /path/to/native_methods.cfg
```

The script runs `mvn package -DskipTests` in `agent/` automatically if `target/docker-monitoring-agent.jar` is missing.

## Seccomp exporter (sysdig pipe)

Build:

```bash
cd DockerMonitoring/seccomp-exporter
go build -o seccomp-exporter .
```

Run (example: pipe sysdig JSON lines — adjust filter to your install):

```bash
sysdig -pc -j ... 2>/dev/null | ./seccomp-exporter --listen=:9101
```

Scrape `http://localhost:9101/metrics`. See [`seccomp-exporter/README.md`](seccomp-exporter/README.md) for formats and `profile_coverage_ratio`.

## End-to-end stack (Tomcat twin + Prometheus + Grafana)

From the **Echotrace repository root** (Docker daemon required):

```bash
docker compose up --build
```

- **Instrumented twin**: Tomcat on [http://localhost:8080](http://localhost:8080), metrics on [http://localhost:9464/metrics](http://localhost:9464/metrics).
- **Prometheus**: [http://localhost:9090](http://localhost:9090) — target `instrumented-twin:9464`, scrape every 2s.
- **Grafana**: [http://localhost:3001](http://localhost:3001) (admin / admin) — dashboards **Echotrace — Native methods** (`echotrace-native`) and **Echotrace — Seccomp / syscalls** (`echotrace-seccomp`). If **3001** is taken, change the host port in [`docker-compose.yml`](../docker-compose.yml).

[`tomcat-twin/`](tomcat-twin/) — Dockerfile and [`native-methods.cfg`](tomcat-twin/native-methods.cfg) listing JDK natives to wrap (extend for your JNI surface).

## Grafana / Prometheus notes

- Metrics use names like `dockermonitoring_native_invocations_total`. The bundled dashboard [`../observability/grafana/dashboards/echotrace-native.json`](../observability/grafana/dashboards/echotrace-native.json) targets these series.
- [`seccomp-exporter/`](seccomp-exporter/) emits `seccomp_*` metrics (`seccomp_unexpected_syscall_total`, `seccomp_profile_coverage_ratio`, etc.); add a Prometheus scrape job and panels/alerts as in [`seccomp-exporter/README.md`](seccomp-exporter/README.md).

## License

Internal / same as parent repository.
