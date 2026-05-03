#!/usr/bin/env bash
# Start seccomp-exporter in demo mode (synthetic NDJSON → seccomp_* on /metrics).
# Picks a free host port 9101–9120 if 9101 is busy and updates
# observability/prometheus/file_sd/seccomp.json for Prometheus (observability stack).
#
# Env:
#   SECCOMP_EXPORTER_HOST_PORT — force host bind (e.g. 9102) instead of auto-pick
#   SECCOMP_ALLOWLIST_FILE — host path to allowlist (file); mounted at
#     /etc/seccomp/allowlist.txt inside the container. Relative paths are resolved
#     from this script's repo root (parent of DockerMonitoring/).
#
# Stop: docker rm -f dm-seccomp-demo
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

info() { echo -e "\033[0;32m    $1\033[0m" >&2; }
warn() { echo -e "\033[0;33m    WARN: $1\033[0m" >&2; }
err()  { echo -e "\033[0;31m    ERR:  $1\033[0m" >&2; }

_host_tcp_listening() {
    command -v lsof >/dev/null 2>&1 || return 1
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

_pick_host_port() {
    if [[ -n "${SECCOMP_EXPORTER_HOST_PORT:-}" ]]; then
        echo "${SECCOMP_EXPORTER_HOST_PORT}"
        return
    fi
    local p=9101
    local end=9120
    while _host_tcp_listening "$p"; do
        warn "Host :$p in use — trying next for seccomp-exporter."
        p=$((p + 1))
        if (( p > end )); then
            err "No free port 9101–$end. Stop the other service or set SECCOMP_EXPORTER_HOST_PORT."
            exit 1
        fi
    done
    echo "$p"
}

HOST_PORT="$(_pick_host_port)"
if [[ -z "${SECCOMP_EXPORTER_HOST_PORT:-}" ]] && [[ "$HOST_PORT" != "9101" ]]; then
    info "Binding seccomp-exporter demo to host :${HOST_PORT} (9101 was busy)."
fi

FILE_SD="${ROOT}/observability/prometheus/file_sd/seccomp.json"
mkdir -p "$(dirname "$FILE_SD")"
printf '[{"targets":["host.docker.internal:%s"]}]\n' "$HOST_PORT" > "$FILE_SD"
info "Wrote ${FILE_SD} → :${HOST_PORT}"

docker build -q -t dm-seccomp-exporter -f DockerMonitoring/seccomp-exporter/Dockerfile DockerMonitoring/seccomp-exporter
docker rm -f dm-seccomp-demo >/dev/null 2>&1 || true

ALLOWLIST_VOL=()
if [[ -n "${SECCOMP_ALLOWLIST_FILE:-}" ]]; then
  al="${SECCOMP_ALLOWLIST_FILE}"
  if [[ "$al" != /* ]]; then
    al="${ROOT%/}/${al}"
  fi
  if [[ ! -f "$al" ]]; then
    err "SECCOMP_ALLOWLIST_FILE must be a regular file: $al"
    exit 1
  fi
  ALLOWLIST_VOL=( -v "${al}:/etc/seccomp/allowlist.txt:ro" )
  info "Mounting allowlist: $al"
fi

docker run -d --name dm-seccomp-demo \
  -p "${HOST_PORT}:9101" \
  "${ALLOWLIST_VOL[@]}" \
  -e SECCOMP_EXPORTER_MODE=demo \
  dm-seccomp-exporter

echo ""
echo "Metrics:  http://localhost:${HOST_PORT}/metrics"
echo "Prometheus job docker-monitoring-seccomp refreshes file_sd in ~5s → target UP."
echo "Grafana:  Echotrace — Seccomp / syscalls (uid echotrace-seccomp)"
echo "Stop:     docker rm -f dm-seccomp-demo"
