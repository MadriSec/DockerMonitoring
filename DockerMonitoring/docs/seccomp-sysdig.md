# Seccomp observability with sysdig

## JNI native methods vs Linux syscalls

| Source | What you see in Prometheus | Dashboard |
|--------|------------------------------|-----------|
| **Java agent** (`dockermonitoring_*`) | Which **JNI “native” Java methods** ran and which **`.so`** files are mapped — **not** every `read()`/`write()` syscall | Echotrace — Native methods |
| **seccomp-exporter** + sysdig (or demo NDJSON) | **Syscall / evt counts** (`seccomp_*`) from the kernel audit / sysdig view | Echotrace — Seccomp / syscalls |

To populate **`seccomp_*`** while developing on macOS / Docker Desktop, run from repo root: **`./run_seccomp_exporter_demo.sh`** (synthetic events), then scrape **`:9101`** (already configured in `observability/prometheus/prometheus.host.yml` after you restart the observability stack). For **real** syscalls you need **Linux + sysdig** (or similar) and a narrow filter; see below.

## Goal

Correlate **kernel-side** syscall / audit / seccomp context with the **in-process** view from the DockerMonitoring Java agent (native methods, mapped `.so`, OTLP / Prometheus).

## Event taxonomy

SCMP_ACT_LOG and audit-related signals show up differently depending on:

- Kernel and auditd configuration
- Sysdig / Falco capture mode and version
- Whether you consume **live** sinsp vs **capture** files

**Action:** Pin one sysdig release. Save one real NDJSON line per event class you care about (blocked, unexpected, baseline). Point `seccomp-exporter` flags (`-syscall-field`, `-container-field`, `-blocked-if-evt-type-prefix`) at those fields.

## Narrowing the firehose

Always filter sysdig by workload identity, for example:

- Container ID or name
- cgroup
- Kubernetes pod / namespace (sysdig container labels)

Example pattern (adjust for your install):

```bash
sysdig -pc -j 'container.name=myworkload and evt.type!=switch' ...
```

Pipe stdout into `seccomp-exporter` and scrape `/metrics`.

## Allowlist and coverage

1. Generate or export an allowlist (EchoTrace seccomp profile, OCI default.json, etc.).
2. Pass it to `seccomp-exporter` with `-allowlist-file`.
3. In Grafana, use `seccomp_profile_coverage_ratio` or compute:

   `seccomp_allowlisted_syscalls_observed / seccomp_profile_allowlist_size`

4. Alert on `increase(seccomp_unexpected_syscall_total[5m]) > 0`.

## Full stack

From repo root, `docker compose` brings up the Tomcat twin, Prometheus, and Grafana. Add a **sidecar or host** process that runs sysdig → `seccomp-exporter`, and add a Prometheus scrape job for the exporter port (e.g. 9101).
