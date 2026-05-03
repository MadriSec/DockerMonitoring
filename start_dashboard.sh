#!/usr/bin/env bash
# =============================================================================
# start_dashboard.sh — Prometheus + Grafana only (observability/docker-compose.yml)
#
# Default host ports avoid collisions with common stacks (3000/9090):
#   Grafana    3030  → container 3000
#   Prometheus 9095 → container 9090
#
# Override: GRAFANA_PORT=… PROMETHEUS_PORT=… ./start_dashboard.sh
#
# Compose path is always next to this script: <repo>/observability/
# Run from repo root: ./start_dashboard.sh
# Or: DockerMonitoring/start_dashboard.sh (wrapper → repo root)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/observability/docker-compose.yml"

# All logging on stderr so command substitutions / pipes never break.
header() { echo -e "\n\033[1;36m==> $1\033[0m" >&2; }
info()   { echo -e "\033[0;32m    $1\033[0m" >&2; }
warn()   { echo -e "\033[0;33m    WARN: $1\033[0m" >&2; }
err()    { echo -e "\033[0;31m    ERR:  $1\033[0m" >&2; }

if ! command -v docker >/dev/null 2>&1; then
    err "docker not found"; exit 1
fi

if docker compose version >/dev/null 2>&1; then
    compose() { docker compose "$@"; }
elif command -v docker-compose >/dev/null 2>&1; then
    compose() { docker-compose "$@"; }
else
    err "Neither 'docker compose' nor 'docker-compose' is available."
    exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
    err "compose file missing: $COMPOSE_FILE"
    err "Use repo root ./start_dashboard.sh (not a copy inside a subfolder without observability/)."
    exit 1
fi

MODE="${1:-up}"

case "$MODE" in
    --stop|stop|down)
        header "Stopping observability stack"
        compose -f "$COMPOSE_FILE" --project-directory "$(dirname "$COMPOSE_FILE")" down
        info "Stack stopped. Volumes prometheus-data + grafana-data preserved."
        info "Remove data too: docker compose -f $COMPOSE_FILE --project-directory $(dirname "$COMPOSE_FILE") down -v"
        exit 0
        ;;
    --status|status)
        header "Observability stack — status"
        compose -f "$COMPOSE_FILE" --project-directory "$(dirname "$COMPOSE_FILE")" ps
        exit 0
        ;;
    up|--up|start|"")
        ;;
    -h|--help|help)
        grep '^#' "$0" | head -26
        exit 0
        ;;
    *)
        err "unknown argument: $MODE"
        err "usage: $0 [up|--stop|--status]"
        exit 1
        ;;
esac

# Defaults: high enough to miss most all-in-one demos; override anytime.
export GRAFANA_PORT="${GRAFANA_PORT:-3030}"
export PROMETHEUS_PORT="${PROMETHEUS_PORT:-9095}"
if [[ -z "${GF_SERVER_ROOT_URL:-}" ]]; then
    export GF_SERVER_ROOT_URL="http://localhost:${GRAFANA_PORT}"
fi

header "Starting Prometheus + Grafana (host Grafana:${GRAFANA_PORT} Prometheus:${PROMETHEUS_PORT})"
compose -f "$COMPOSE_FILE" --project-directory "$(dirname "$COMPOSE_FILE")" up -d

GRAFANA_URL="${GF_SERVER_ROOT_URL}"
PROM_URL="http://localhost:${PROMETHEUS_PORT}"
ATTEMPTS=60

header "Waiting for Grafana (${GRAFANA_URL})"
for i in $(seq 1 "$ATTEMPTS"); do
    if curl -fsS "${GRAFANA_URL}/api/health" >/dev/null 2>&1; then
        info "Grafana is up (attempt $i/$ATTEMPTS)"
        break
    fi
    if [[ $i -eq $ATTEMPTS ]]; then
        warn "Grafana health check timed out."
        warn "Try: docker compose -f $COMPOSE_FILE --project-directory $(dirname "$COMPOSE_FILE") logs grafana"
        exit 1
    fi
    sleep 1
done

header "Waiting for Prometheus (${PROM_URL})"
for i in $(seq 1 "$ATTEMPTS"); do
    if curl -fsS "${PROM_URL}/-/ready" >/dev/null 2>&1; then
        info "Prometheus is up (attempt $i/$ATTEMPTS)"
        break
    fi
    if [[ $i -eq $ATTEMPTS ]]; then
        warn "Prometheus ready check timed out."
        warn "Try: docker compose -f $COMPOSE_FILE --project-directory $(dirname "$COMPOSE_FILE") logs prometheus"
        exit 1
    fi
    sleep 1
done

header "Dashboard ready"
info "Grafana:    ${GRAFANA_URL}/d/echotrace-native  (admin / admin)"
info "            ${GRAFANA_URL}/d/echotrace-seccomp"
info "Prometheus: ${PROM_URL}/targets"
echo "" >&2
info "Capture (repo root): ./otel_capture.sh <container> path/to/native_methods.cfg"
echo "" >&2
info "JNI metrics: observability/prometheus/file_sd/capture.json (otel_capture.sh; refresh ~5s)."
info "Syscalls: run ../run_seccomp_exporter_demo.sh (from DockerMonitoring/) or ./run_seccomp_exporter_demo.sh (repo root); Grafana → Echotrace — Seccomp / syscalls."
echo "" >&2
info "Stop: ./start_dashboard.sh --stop"
echo "" >&2
