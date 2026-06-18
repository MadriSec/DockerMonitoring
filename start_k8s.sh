#!/usr/bin/env bash
# =============================================================================
# start_k8s.sh - local Kubernetes stack for DockerMonitoring
#
# Builds the local images used by k8s/workloads.yaml, loads them into kind or
# Minikube when detected, applies manifests, and provisions Grafana dashboards.
#
# Usage:
#   ./start_k8s.sh
#   ./start_k8s.sh --delete
#   ./start_k8s.sh --status
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="${K8S_NAMESPACE:-dockermonitoring}"
TWIN_IMAGE="${TWIN_IMAGE:-dm-instrumented-twin:local}"
SECCOMP_IMAGE="${SECCOMP_IMAGE:-dm-seccomp-exporter:local}"

header() { echo -e "\n\033[1;36m==> $1\033[0m" >&2; }
info()   { echo -e "\033[0;32m    $1\033[0m" >&2; }
warn()   { echo -e "\033[0;33m    WARN: $1\033[0m" >&2; }
err()    { echo -e "\033[0;31m    ERR:  $1\033[0m" >&2; }

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    err "$1 not found"
    exit 1
  fi
}

cluster_name() {
  kubectl config view --minify -o jsonpath='{.clusters[0].name}' 2>/dev/null || true
}

load_images_if_needed() {
  local cluster
  cluster="$(cluster_name)"

  if [[ "$cluster" == kind-* ]] && command -v kind >/dev/null 2>&1; then
    local name="${cluster#kind-}"
    header "Loading images into kind cluster ${name}"
    kind load docker-image "$TWIN_IMAGE" --name "$name"
    kind load docker-image "$SECCOMP_IMAGE" --name "$name"
  elif [[ "$cluster" == minikube ]] && command -v minikube >/dev/null 2>&1; then
    header "Loading images into Minikube"
    minikube image load "$TWIN_IMAGE"
    minikube image load "$SECCOMP_IMAGE"
  else
    warn "Cluster '${cluster:-unknown}' was not recognized as kind or Minikube."
    warn "Make sure it can pull or already has ${TWIN_IMAGE} and ${SECCOMP_IMAGE}."
  fi
}

wait_for_rollout() {
  header "Waiting for deployments"
  kubectl -n "$NAMESPACE" rollout status deploy/instrumented-twin --timeout=180s
  kubectl -n "$NAMESPACE" rollout status deploy/seccomp-exporter --timeout=180s
  kubectl -n "$NAMESPACE" rollout status deploy/prometheus --timeout=180s
  kubectl -n "$NAMESPACE" rollout status deploy/grafana --timeout=180s
}

mode="${1:-up}"
case "$mode" in
  --delete|delete|down)
    need kubectl
    header "Deleting Kubernetes stack"
    kubectl delete namespace "$NAMESPACE" --ignore-not-found
    exit 0
    ;;
  --status|status)
    need kubectl
    header "Kubernetes stack status"
    kubectl -n "$NAMESPACE" get pods,svc
    exit 0
    ;;
  up|--up|start|"")
    ;;
  -h|--help|help)
    sed -n '1,26p' "$0"
    exit 0
    ;;
  *)
    err "unknown argument: $mode"
    err "usage: $0 [up|--delete|--status]"
    exit 1
    ;;
esac

need docker
need kubectl

header "Building local images"
docker build -t "$TWIN_IMAGE" -f "${SCRIPT_DIR}/DockerMonitoring/tomcat-twin/Dockerfile" "$SCRIPT_DIR"
docker build -t "$SECCOMP_IMAGE" "${SCRIPT_DIR}/DockerMonitoring/seccomp-exporter"

load_images_if_needed

header "Applying Kubernetes manifests"
# kustomize (apply -k) applies all manifests and generates the Grafana
# dashboards ConfigMap from observability/grafana/dashboards/*.json.
kubectl apply -k "${SCRIPT_DIR}"

wait_for_rollout

header "Kubernetes stack ready"
info "Status:     ./start_k8s.sh --status"
info "Stop:       ./start_k8s.sh --delete"
info "Tomcat:     kubectl -n ${NAMESPACE} port-forward svc/instrumented-twin 8080:8080"
info "JVM metrics: kubectl -n ${NAMESPACE} port-forward svc/instrumented-twin 9464:9464"
info "Seccomp:    kubectl -n ${NAMESPACE} port-forward svc/seccomp-exporter 9101:9101"
info "Prometheus: kubectl -n ${NAMESPACE} port-forward svc/prometheus 9090:9090"
info "Grafana:    kubectl -n ${NAMESPACE} port-forward svc/grafana 3001:3000  (admin / admin)"
