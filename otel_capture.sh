#!/usr/bin/env bash
# =============================================================================
# otel_capture.sh — Runtime native-method capture (DockerMonitoring javaagent)
#
# Clones a running container via docker inspect + docker run, injecting the
# agent (setNativeMethodPrefix + ASM). Optional: Prometheus scrape (:9464) and
# OTLP via DOCKERMONITORING_OTLP_ENDPOINT.
#
# Prerequisite: ./start_dashboard.sh (Prometheus scrapes host.docker.internal:9464)
#
# Usage:
#   ./otel_capture.sh <container_name> <native_methods_file> [duration_seconds]
#
# Prerequisites: Docker CLI + jq; Maven + JDK 11+ (to build agent if missing)
#
# Output: runtime_native_methods.txt in the current working directory
#
# Env:
#   DOCKERMONITORING_METRICS_PORT (default 9464; legacy ECHOTRACE_METRICS_PORT)
#   DOCKERMONITORING_OTLP_ENDPOINT — e.g. http://host.docker.internal:4318
#   GRAFANA_PORT — summary links (default 3030, matches start_dashboard.sh)
#   PROMETHEUS_PORT — summary links (default 9095)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_PROJECT="${SCRIPT_DIR}/DockerMonitoring/agent"
AGENT_JAR="${AGENT_PROJECT}/target/docker-monitoring-agent.jar"

usage() {
    echo "Usage: $0 <container_name> <native_methods_file> [duration_seconds]"
    echo ""
    echo "  container_name       Docker name or ID"
    echo "  native_methods_file  One ClassName.methodName per line (# comments OK)"
    echo "  duration_seconds     Capture window (default: 120)"
    echo ""
    echo "Start dashboards first: ./start_dashboard.sh"
    exit 1
}

header() { echo -e "\n\033[1;36m==> $1\033[0m" >&2; }
info()   { echo -e "\033[0;32m    $1\033[0m" >&2; }
warn()   { echo -e "\033[0;33m    WARNING: $1\033[0m" >&2; }
err()    { echo -e "\033[0;31m    ERROR: $1\033[0m" >&2; }

if ! command -v jq &>/dev/null; then
    err "jq is required. Install: brew install jq   or   apt install jq"
    exit 1
fi

if [[ $# -lt 2 ]]; then
    usage
fi

CONTAINER="$1"
NATIVE_METHODS_FILE="$2"
DURATION="${3:-120}"
GRAFANA_PORT="${GRAFANA_PORT:-3030}"
PROMETHEUS_PORT="${PROMETHEUS_PORT:-9095}"

if [[ ! -f "$NATIVE_METHODS_FILE" ]]; then
    err "Native methods file not found: $NATIVE_METHODS_FILE"
    exit 1
fi

METHOD_COUNT=$(grep -cvE '^\s*(#|$)' "$NATIVE_METHODS_FILE" || true)
info "Config: container=$CONTAINER, methods=$METHOD_COUNT, duration=${DURATION}s"

header "Step 1: Ensuring DockerMonitoring agent is built"
if [[ ! -f "$AGENT_JAR" ]]; then
    if ! command -v mvn &>/dev/null; then
        err "Maven (mvn) not found — cannot build ${AGENT_JAR}"
        err "Install Maven + JDK 11+, or build once: docker run --rm -v \"$AGENT_PROJECT\":/src -w /src maven:3.9-eclipse-temurin-11 mvn -q -DskipTests package"
        exit 1
    fi
    info "Building agent..."
    mvn -f "${AGENT_PROJECT}/pom.xml" clean package -q -DskipTests
fi
info "Agent JAR: $(du -h "$AGENT_JAR" | cut -f1)"

header "Step 2: Preparing staging directory"
STAGING_DIR=$(mktemp -d "/tmp/dm-agent-XXXXXX")
OUTPUT_HOST_DIR=$(mktemp -d "/tmp/dm-output-XXXXXX")

cleanup_temps() {
    if [[ -n "${TEMP_CONTAINER:-}" ]]; then
        docker rm -f "$TEMP_CONTAINER" >/dev/null 2>&1 || true
    fi
    if [[ -n "${CONTAINER:-}" ]]; then
        docker start "$CONTAINER" >/dev/null 2>&1 || true
    fi
    rm -rf "$STAGING_DIR" "$OUTPUT_HOST_DIR" 2>/dev/null || true
    # Twin is gone — nothing listens on the ephemeral port; reset file_sd so Prom isn't stuck DOWN on :9465.
    _reset_prometheus_capture_target_default
}
# Reset Prometheus file_sd to default port (called on capture exit).
_reset_prometheus_capture_target_default() {
    local dir="${SCRIPT_DIR}/observability/prometheus/file_sd"
    [[ -d "$dir" ]] || return
    printf '[{"targets":["host.docker.internal:9464"]}]\n' > "${dir}/capture.json"
}

trap cleanup_temps EXIT

handle_interrupt() {
    echo ""
    warn "Interrupted — attempting to salvage partial results..."
    if [[ -f "${OUTPUT_HOST_DIR}/runtime_native_methods.txt" ]] && \
       [[ -s "${OUTPUT_HOST_DIR}/runtime_native_methods.txt" ]]; then
        cp "${OUTPUT_HOST_DIR}/runtime_native_methods.txt" "runtime_native_methods.txt"
        info "Saved partial results to runtime_native_methods.txt"
    fi
    if [[ -n "${TEMP_CONTAINER:-}" ]]; then
        docker stop -t 10 "$TEMP_CONTAINER" >/dev/null 2>&1 || true
        docker rm -f "$TEMP_CONTAINER" >/dev/null 2>&1 || true
    fi
    docker start "$CONTAINER" >/dev/null 2>&1 || true
    exit 130
}
trap handle_interrupt INT

chmod 755 "$STAGING_DIR"
chmod 777 "$OUTPUT_HOST_DIR"

STAGE_JAR_NAME="docker-monitoring-agent.jar"
cp "$AGENT_JAR" "${STAGING_DIR}/${STAGE_JAR_NAME}"
NATIVE_ABS="$(cd "$(dirname "$NATIVE_METHODS_FILE")" && pwd)/$(basename "$NATIVE_METHODS_FILE")"
cp "$NATIVE_ABS" "${STAGING_DIR}/native_methods.txt"
chmod 644 "${STAGING_DIR}/${STAGE_JAR_NAME}" "${STAGING_DIR}/native_methods.txt"

info "Agent staged to $STAGING_DIR"
info "Output dir: $OUTPUT_HOST_DIR"

header "Step 3: Inspecting original container"
CONTAINER_STATE=$(docker inspect -f '{{.State.Status}}' "$CONTAINER" 2>/dev/null || echo "not_found")
if [[ "$CONTAINER_STATE" == "not_found" ]]; then
    err "Container '$CONTAINER' not found"
    exit 1
fi
info "Container state: $CONTAINER_STATE"

INSPECT_JSON=$(docker inspect "$CONTAINER")
ORIG_IMAGE=$(echo "$INSPECT_JSON" | jq -r '.[0].Config.Image')
info "Image: $ORIG_IMAGE"

ORIG_ENTRYPOINT=$(echo "$INSPECT_JSON" | jq -r '.[0].Config.Entrypoint // empty | @json' 2>/dev/null || echo "")
[[ -n "$ORIG_ENTRYPOINT" && "$ORIG_ENTRYPOINT" != "null" ]] && info "Entrypoint: $ORIG_ENTRYPOINT"

ORIG_CMD=$(echo "$INSPECT_JSON" | jq -r '.[0].Config.Cmd // empty | @json' 2>/dev/null || echo "")
[[ -n "$ORIG_CMD" && "$ORIG_CMD" != "null" ]] && info "Cmd: $ORIG_CMD"

ORIG_USER=$(echo "$INSPECT_JSON" | jq -r '.[0].Config.User // ""')
[[ -n "$ORIG_USER" ]] && info "User: $ORIG_USER"

ORIG_WORKDIR=$(echo "$INSPECT_JSON" | jq -r '.[0].Config.WorkingDir // ""')
ORIG_NETWORK=$(echo "$INSPECT_JSON" | jq -r '.[0].HostConfig.NetworkMode // "default"')
info "Network: $ORIG_NETWORK"

header "Step 4: Building docker run command"
TEMP_CONTAINER="dockermonitoring-capture-$$"
DOCKER_RUN_ARGS=()

AGENT_CONTAINER_PATH="/dockermonitoring-agent/${STAGE_JAR_NAME}"
CONFIG_CONTAINER_PATH="/dockermonitoring-agent/native_methods.txt"
OUTPUT_CONTAINER_PATH="/dockermonitoring-output/runtime_native_methods.txt"

# True if something is listening on host TCP port (needs lsof).
_host_tcp_listening() {
    command -v lsof >/dev/null 2>&1 || return 1
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

# Sets METRICS_PORT. If DOCKERMONITORING_METRICS_PORT / ECHOTRACE_METRICS_PORT is set, honors it.
# Otherwise picks first free port in 9464–9490 so docker run does not fail when 9464 is taken.
_assign_metrics_port() {
    if [[ "${DOCKERMONITORING_METRICS_PORT+set}" == "set" ]] || [[ "${ECHOTRACE_METRICS_PORT+set}" == "set" ]]; then
        METRICS_PORT="${DOCKERMONITORING_METRICS_PORT:-${ECHOTRACE_METRICS_PORT:-9464}}"
        return
    fi
    local p=9464
    local end=9490
    while _host_tcp_listening "$p"; do
        warn "Host TCP $p is in use — will try the next port for /metrics."
        p=$((p + 1))
        if (( p > end )); then
            err "No free port 9464-${end}. Free a port or set DOCKERMONITORING_METRICS_PORT."
            exit 1
        fi
    done
    METRICS_PORT="$p"
    if (( METRICS_PORT != 9464 )); then
        info "Using METRICS_PORT=${METRICS_PORT} (9464 was busy)."
    fi
}

# Updates Prometheus file_sd so the observability stack scrapes the same host port (no YAML edit).
_write_prometheus_capture_target() {
    local dir="${SCRIPT_DIR}/observability/prometheus/file_sd"
    if [[ ! -d "${SCRIPT_DIR}/observability" ]]; then
        warn "No ${SCRIPT_DIR}/observability — skipping file_sd update (start_dashboard from repo root)."
        return
    fi
    mkdir -p "$dir"
    printf '[{"targets":["host.docker.internal:%s"]}]\n' "$METRICS_PORT" > "${dir}/capture.json"
    info "Prometheus scrape target file: ${dir}/capture.json → :${METRICS_PORT} (picked up in ~5s)"
}

AGENT_JVM_FLAGS="-javaagent:${AGENT_CONTAINER_PATH}"
AGENT_JVM_FLAGS+=" -Ddockermonitoring.native.methods.file=${CONFIG_CONTAINER_PATH}"
AGENT_JVM_FLAGS+=" -Ddockermonitoring.output.file=${OUTPUT_CONTAINER_PATH}"
AGENT_JVM_FLAGS+=" -Xshare:off"
AGENT_JVM_FLAGS+=" -Ddockermonitoring.metrics.host=0.0.0.0"

_assign_metrics_port
_write_prometheus_capture_target

AGENT_JVM_FLAGS+=" -Ddockermonitoring.metrics.port=${METRICS_PORT}"

if [[ -n "${DOCKERMONITORING_OTLP_ENDPOINT:-}" ]]; then
    AGENT_JVM_FLAGS+=" -Ddockermonitoring.otlp.endpoint=${DOCKERMONITORING_OTLP_ENDPOINT}"
fi

detect_jvm_env_var() {
    local image_lower
    image_lower=$(echo "$ORIG_IMAGE" | tr '[:upper:]' '[:lower:]')
    case "$image_lower" in
        *cassandra*)       echo "JVM_EXTRA_OPTS" ;;
        *tomcat*)          echo "CATALINA_OPTS" ;;
        *elasticsearch*)   echo "ES_JAVA_OPTS" ;;
        *solr*)            echo "SOLR_OPTS" ;;
        *zookeeper*)       echo "JVMFLAGS" ;;
        *jetty*)           echo "JAVA_OPTIONS" ;;
        *spark*)           echo "SPARK_SUBMIT_OPTS" ;;
        *storm*)           echo "STORM_JAR_JVM_OPTS" ;;
        *jboss*|*wildfly*) echo "JAVA_OPTS" ;;
        *sonarqube*)       echo "JAVA_TOOL_OPTIONS" ;;
        *groovy*)          echo "JAVA_OPTS" ;;
        *orientdb*)        echo "JAVA_OPTS" ;;
        *ignite*)          echo "JVM_OPTS" ;;
        *jruby*)           echo "JAVA_OPTS" ;;
        *)                 echo "JAVA_TOOL_OPTIONS" ;;
    esac
}

JVM_ENV_VAR=$(detect_jvm_env_var)
info "Detected JVM env var for agent: $JVM_ENV_VAR (from image)"

if [[ "$JVM_ENV_VAR" == "JAVA_TOOL_OPTIONS" ]]; then
    warn "Falling back to JAVA_TOOL_OPTIONS — may affect scripts that run java -version."
fi

ORIG_JVM_VAR_VALUE=""
while IFS= read -r envvar; do
    [[ -z "$envvar" ]] && continue
    if [[ "$envvar" == JAVA_TOOL_OPTIONS=* ]]; then
        info "Stripping original JAVA_TOOL_OPTIONS"
        continue
    fi
    if [[ "$envvar" == "${JVM_ENV_VAR}="* ]]; then
        ORIG_JVM_VAR_VALUE="${envvar#"${JVM_ENV_VAR}="}"
        info "Preserving existing ${JVM_ENV_VAR} suffix"
        continue
    fi
    DOCKER_RUN_ARGS+=("-e" "$envvar")
done < <(echo "$INSPECT_JSON" | jq -r '.[0].Config.Env[]? // empty')

if [[ -n "$ORIG_JVM_VAR_VALUE" ]]; then
    FINAL_JVM_VALUE="${AGENT_JVM_FLAGS} ${ORIG_JVM_VAR_VALUE}"
else
    FINAL_JVM_VALUE="${AGENT_JVM_FLAGS}"
fi
DOCKER_RUN_ARGS+=("-e" "${JVM_ENV_VAR}=${FINAL_JVM_VALUE}")

DOCKER_RUN_ARGS+=("-v" "${STAGING_DIR}:/dockermonitoring-agent:ro")
DOCKER_RUN_ARGS+=("-v" "${OUTPUT_HOST_DIR}:/dockermonitoring-output:rw")
DOCKER_RUN_ARGS+=("-p" "${METRICS_PORT}:${METRICS_PORT}")

while IFS= read -r port_mapping; do
    if [[ -n "$port_mapping" && "$port_mapping" != "null" ]]; then
        DOCKER_RUN_ARGS+=("-p" "$port_mapping")
    fi
done < <(echo "$INSPECT_JSON" | jq -r '
    .[0].HostConfig.PortBindings // {} |
    to_entries[] |
    .key as $cport |
    .value[]? |
    (if .HostIp != "" and .HostIp != "0.0.0.0" then .HostIp + ":" else "" end) +
    .HostPort + ":" + ($cport | split("/")[0])
' 2>/dev/null || true)

while IFS= read -r mount_spec; do
    if [[ -n "$mount_spec" && "$mount_spec" != "null" ]]; then
        DOCKER_RUN_ARGS+=("-v" "$mount_spec")
    fi
done < <(echo "$INSPECT_JSON" | jq -r '
    .[0].Mounts[]? |
    if .Type == "bind" then
        .Source + ":" + .Destination + (if .RW then "" else ":ro" end)
    elif .Type == "volume" then
        .Name + ":" + .Destination + (if .RW then "" else ":ro" end)
    else empty end
' 2>/dev/null || true)

[[ -n "$ORIG_USER" ]] && DOCKER_RUN_ARGS+=("--user" "$ORIG_USER")

if [[ "$ORIG_NETWORK" != "default" && "$ORIG_NETWORK" != "bridge" ]]; then
    DOCKER_RUN_ARGS+=("--network" "$ORIG_NETWORK")
fi

[[ -n "$ORIG_WORKDIR" ]] && DOCKER_RUN_ARGS+=("-w" "$ORIG_WORKDIR")

if [[ -n "$ORIG_ENTRYPOINT" && "$ORIG_ENTRYPOINT" != "null" ]]; then
    EP_FIRST=$(echo "$ORIG_ENTRYPOINT" | jq -r '.[0]')
    DOCKER_RUN_ARGS+=("--entrypoint" "$EP_FIRST")
fi

info "Twin name: $TEMP_CONTAINER"
info "${JVM_ENV_VAR}=${FINAL_JVM_VALUE}"

header "Step 5: Starting instrumented twin"
if [[ "$CONTAINER_STATE" == "running" ]]; then
    info "Stopping original container..."
    docker stop "$CONTAINER" >/dev/null 2>&1 || true
fi

FULL_CMD=(docker run -d --name "$TEMP_CONTAINER")
FULL_CMD+=("${DOCKER_RUN_ARGS[@]}")
FULL_CMD+=("$ORIG_IMAGE")

if [[ -n "$ORIG_ENTRYPOINT" && "$ORIG_ENTRYPOINT" != "null" ]]; then
    EP_LEN=$(echo "$ORIG_ENTRYPOINT" | jq 'length')
    if [[ "$EP_LEN" -gt 1 ]]; then
        while IFS= read -r ep_arg; do
            FULL_CMD+=("$ep_arg")
        done < <(echo "$ORIG_ENTRYPOINT" | jq -r '.[1:][]')
    fi
fi
if [[ -n "$ORIG_CMD" && "$ORIG_CMD" != "null" ]]; then
    while IFS= read -r cmd_arg; do
        FULL_CMD+=("$cmd_arg")
    done < <(echo "$ORIG_CMD" | jq -r '.[]')
fi

info "Launching instrumented container..."
if ! "${FULL_CMD[@]}"; then
    err "docker run failed (see message above)."
    docker start "$CONTAINER" >/dev/null 2>&1 || true
    exit 1
fi

sleep 3
TWIN_STATE=$(docker inspect -f '{{.State.Status}}' "$TEMP_CONTAINER" 2>/dev/null || echo "gone")
if [[ "$TWIN_STATE" != "running" ]]; then
    err "Instrumented container not running (state: $TWIN_STATE)"
    (docker logs "$TEMP_CONTAINER" 2>&1 | head -40 | while IFS= read -r line; do info "  $line"; done) || true
    docker rm -f "$TEMP_CONTAINER" >/dev/null 2>&1 || true
    docker start "$CONTAINER" >/dev/null 2>&1 || true
    exit 1
fi
info "Instrumented container running"

ACTUAL_OPTS=$(docker exec "$TEMP_CONTAINER" sh -c "printf '%s' \"\$${JVM_ENV_VAR}\"" 2>/dev/null || true)
if echo "$ACTUAL_OPTS" | grep -q "docker-monitoring-agent"; then
    info "${JVM_ENV_VAR} contains -javaagent"
else
    warn "${JVM_ENV_VAR} might not include the agent — actual: ${ACTUAL_OPTS:0:200}"
fi

info "Waiting for DockerMonitoring agent in logs (up to 40s)..."
AGENT_DETECTED=0
for _ in $(seq 1 40); do
    if docker logs "$TEMP_CONTAINER" 2>&1 | grep -qiE 'dockermonitoring|\[dockermonitoring\]'; then
        AGENT_DETECTED=1
        break
    fi
    sleep 1
done

if [[ "$AGENT_DETECTED" == "1" ]]; then
    info "Agent log lines:"
    (docker logs "$TEMP_CONTAINER" 2>&1 | grep -iE 'dockermonitoring|\[dockermonitoring\]' | head -12 | while IFS= read -r line; do info "  $line"; done) || true
else
    warn "No [DockerMonitoring] lines in logs after 40s."
    (docker logs "$TEMP_CONTAINER" 2>&1 | head -25 | while IFS= read -r line; do info "  $line"; done) || true
fi

header "Step 6: Capturing (${DURATION}s) — Grafana: http://localhost:${GRAFANA_PORT}/d/echotrace-native"
ELAPSED=0
while [[ $ELAPSED -lt $DURATION ]]; do
    sleep 10
    ELAPSED=$((ELAPSED + 10))
    CSTATE=$(docker inspect -f '{{.State.Status}}' "$TEMP_CONTAINER" 2>/dev/null || echo "gone")
    if [[ "$CSTATE" != "running" ]]; then
        warn "Twin stopped early (state: $CSTATE)"
        break
    fi
    if [[ -r "${OUTPUT_HOST_DIR}/runtime_native_methods.txt" ]]; then
        FLUSH_LINES=$(wc -l < "${OUTPUT_HOST_DIR}/runtime_native_methods.txt" 2>/dev/null || echo "?")
        info "  ${ELAPSED}/${DURATION}s — $FLUSH_LINES lines in output file"
    else
        info "  ${ELAPSED}/${DURATION}s"
    fi
done

header "Step 7: Extracting results"
if [[ -f "${OUTPUT_HOST_DIR}/runtime_native_methods.txt" ]] && [[ ! -r "${OUTPUT_HOST_DIR}/runtime_native_methods.txt" ]]; then
    docker exec "$TEMP_CONTAINER" chmod 666 /dockermonitoring-output/runtime_native_methods.txt >/dev/null 2>&1 || true
fi

(docker logs "$TEMP_CONTAINER" 2>&1 | grep -iE 'dockermonitoring|native method' | head -20 | while IFS= read -r line; do info "  $line"; done) || true

info "Stopping twin (shutdown hook flushes)..."
docker stop -t 30 "$TEMP_CONTAINER" >/dev/null 2>&1 || true

OUTPUT_FILE="runtime_native_methods.txt"
if [[ -f "${OUTPUT_HOST_DIR}/runtime_native_methods.txt" ]] && [[ -s "${OUTPUT_HOST_DIR}/runtime_native_methods.txt" ]]; then
    cp "${OUTPUT_HOST_DIR}/runtime_native_methods.txt" "$OUTPUT_FILE"
    RUNTIME_COUNT=$(wc -l < "$OUTPUT_FILE")
    info "Wrote $RUNTIME_COUNT lines → $OUTPUT_FILE"
else
    warn "No output file from bind mount."
    docker logs --tail 35 "$TEMP_CONTAINER" 2>&1 | while IFS= read -r line; do info "  $line"; done
fi

header "Step 8: Cleanup"
docker rm -f "$TEMP_CONTAINER" >/dev/null 2>&1 || true
TEMP_CONTAINER=""
docker start "$CONTAINER" >/dev/null 2>&1 || true
info "Original container restarted: $CONTAINER"

header "Summary"
if [[ -f "$OUTPUT_FILE" ]] && [[ -s "$OUTPUT_FILE" ]]; then
    info "Static config lines: $METHOD_COUNT"
    info "Runtime lines:        $(wc -l < "$OUTPUT_FILE")"
    if curl -fsS "http://localhost:${GRAFANA_PORT}/api/health" >/dev/null 2>&1; then
        info "Grafana:  http://localhost:${GRAFANA_PORT}/d/echotrace-native"
    else
        info "Start Grafana: ./start_dashboard.sh  (defaults: Grafana 3030, Prometheus 9095)"
    fi
    info "Prometheus targets: http://localhost:${PROMETHEUS_PORT}/targets"
else
    warn "No runtime_native_methods.txt — see logs above."
    warn "Remember: java.* / javax.* natives are not wrapped by the agent."
fi
