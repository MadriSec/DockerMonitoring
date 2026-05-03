# seccomp-exporter

Reads **newline-delimited JSON** (stdin), typically from **sysdig** (`-j`), and exposes **Prometheus** metrics on `/metrics`.

This is a **prototype**: syscall / seccomp event shapes depend on [sysdig](https://sysdig.com/) version, capture mode (live vs file), and filters. **Pin one sysdig version**, validate a sample line, then set `-syscall-field` and `-container-field` to match.

## Build

```bash
cd DockerMonitoring/seccomp-exporter
go build -o seccomp-exporter .
```

## Run (pipe from sysdig)

Bind metrics (example **9101**):

```bash
sysdig -pc -j 'container.name=ubuntu and evt.type!=switch' 2>/dev/null \
  | ./seccomp-exporter \
      --listen=:9101 \
      --syscall-field=evt.type \
      --container-field=container.name \
      --allowlist-file=./examples/seccomp-allowlist.txt
```

Scrape `http://127.0.0.1:9101/metrics`.

### Flags

| Flag | Default | Purpose |
|------|---------|---------|
| `-listen` | `:9101` | HTTP bind address |
| `-container` | *(empty)* | Fixed `container` label; overrides JSON extraction |
| `-syscall-field` | `evt.type` | Dot path into each JSON object for the syscall / event type |
| `-container-field` | `container.name` | Dot path for `container` label |
| `-allowlist-file` | *(none)* | Allowed syscall names (one per line). Drives `seccomp_unexpected_syscall_total`, coverage gauges |
| `-blocked-if-evt-type-prefix` | *(empty)* | If set, increments `seccomp_syscall_blocked_total` when `evt.type` has this prefix (tune per sysdig output) |

## Metrics

| Name | Type | Notes |
|------|------|--------|
| `seccomp_syscall_observed_total{syscall,container}` | Counter | Every parsed line with a syscall / evt.type |
| `seccomp_syscall_blocked_total{syscall,container}` | Counter | Optional heuristic via `-blocked-if-evt-type-prefix` |
| `seccomp_unexpected_syscall_total{syscall,container}` | Counter | Syscall not in allowlist |
| `seccomp_profile_allowlist_size` | Gauge | Lines in allowlist file |
| `seccomp_observed_distinct_syscalls` | Gauge | Distinct syscall values seen |
| `seccomp_allowlisted_syscalls_observed` | Gauge | Allowlist entries seen at least once |
| `seccomp_profile_coverage_ratio` | Gauge | `allowlisted_syscalls_observed / allowlist_size` |
| `seccomp_exporter_lines_read_total` | Counter | Stdin lines |
| `seccomp_exporter_json_parse_errors_total` | Counter | Invalid JSON |

## Grafana

- **Unexpected syscalls:** `increase(seccomp_unexpected_syscall_total[5m]) > 0`
- **Blocked spike:** `increase(seccomp_syscall_blocked_total[5m])` after deploy
- **Coverage:** panel using `seccomp_profile_coverage_ratio` or ratio of `seccomp_allowlisted_syscalls_observed` / `seccomp_profile_allowlist_size`

## Allowlist file format

One syscall name per line (must match the string extracted by `-syscall-field`). Lines starting with `#` and blank lines are ignored.

See [`examples/seccomp-allowlist.txt`](examples/seccomp-allowlist.txt).

## Sysdig version

Document the exact `sysdig --version`, CLI invocation, and a **redacted sample JSON line** in [`../docs/seccomp-sysdig.md`](../docs/seccomp-sysdig.md) once you lock a schema.
