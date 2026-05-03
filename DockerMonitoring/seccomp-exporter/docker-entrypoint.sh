#!/usr/bin/env bash
# Default MODE=demo feeds synthetic NDJSON so Grafana seccomp panels work
# everywhere (no kernel driver). For real syscalls on Linux hosts, use
# SECCOMP_EXPORTER_MODE=live and install sysdig in the image or pipe from a
# privileged sysdig container (see seccomp-exporter/README.md).

set -euo pipefail

MODE="${SECCOMP_EXPORTER_MODE:-demo}"
LISTEN="${SECCOMP_LISTEN:-:9101}"
ALLOWLIST="${SECCOMP_ALLOWLIST_FILE:-/etc/seccomp/allowlist.txt}"

args=( --listen="$LISTEN" --syscall-field=evt.type --container-field=container.name )
if [[ -f "$ALLOWLIST" ]]; then
  args+=( --allowlist-file="$ALLOWLIST" )
fi

if [[ "$MODE" == "live" ]]; then
  if ! command -v sysdig >/dev/null 2>&1; then
    echo "seccomp-exporter: MODE=live but sysdig is not installed in this image." >&2
    echo "Use a host-side pipe: sysdig ... | docker run -i ... seccomp-exporter" >&2
    echo "or extend this image with sysdig (Linux + privileged/kube only)." >&2
    exit 1
  fi
  FILTER="${SYSDIG_FILTER:-evt.type!=switch}"
  sysdig -pc -j "$FILTER" 2>/dev/null | exec /usr/local/bin/seccomp-exporter "${args[@]}"
fi

# Demo stream (sysdig-like evt.type + container.name), attributed to the same
# logical twin name as the Prometheus scrape target.
echo "[seccomp-exporter] MODE=demo — synthetic NDJSON → ${LISTEN} (set SECCOMP_EXPORTER_MODE=live + sysdig for real syscalls)" >&2
while true; do
  echo '{"evt":{"type":"read"},"container":{"name":"instrumented-twin"}}'
  echo '{"evt":{"type":"open"},"container":{"name":"instrumented-twin"}}'
  echo '{"evt":{"type":"futex"},"container":{"name":"instrumented-twin"}}'
  echo '{"evt":{"type":"epoll_wait"},"container":{"name":"instrumented-twin"}}'
  echo '{"evt":{"type":"mmap"},"container":{"name":"instrumented-twin"}}'
  sleep 2
done | exec /usr/local/bin/seccomp-exporter "${args[@]}"
