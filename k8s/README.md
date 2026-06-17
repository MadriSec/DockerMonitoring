# Kubernetes

This directory mirrors the all-in-one `docker compose` stack on a Kubernetes
cluster:

- `instrumented-twin`: Tomcat plus the DockerMonitoring Java agent.
- `seccomp-exporter`: demo NDJSON syscall stream by default.
- `prometheus`: scrapes the two in-cluster services.
- `grafana`: provisions the existing Echotrace dashboards.

The default path is for local clusters such as Minikube, kind, Docker Desktop,
or k3d. Real syscall capture is host and kernel dependent, so the Kubernetes
manifests keep `seccomp-exporter` in demo mode.

## Start

From the repository root:

```bash
./start_k8s.sh
```

The script builds local images:

- `dm-instrumented-twin:local`
- `dm-seccomp-exporter:local`

It also loads those images into kind or Minikube when those clusters are
detected, applies `k8s/*.yaml`, and creates the Grafana dashboard ConfigMap from
the JSON files under `observability/grafana/dashboards/`.

## Open locally

Use port-forwarding:

```bash
kubectl -n dockermonitoring port-forward svc/instrumented-twin 8080:8080
kubectl -n dockermonitoring port-forward svc/prometheus 9090:9090
kubectl -n dockermonitoring port-forward svc/grafana 3001:3000
```

Then open:

- Tomcat: <http://localhost:8080>
- JVM metrics: <http://localhost:9464/metrics> with an extra port-forward for `9464:9464`
- Seccomp metrics: <http://localhost:9101/metrics> with an extra port-forward for `9101:9101`
- Prometheus: <http://localhost:9090>
- Grafana: <http://localhost:3001> (`admin` / `admin`)

## Stop

```bash
./start_k8s.sh --delete
```

## Live syscall capture

The default `seccomp-exporter` deployment is intentionally portable. For real
syscall events on Linux Kubernetes nodes, replace the demo stream with a
privileged sysdig/Falco-style event source and pipe sysdig-shaped NDJSON into
`seccomp-exporter`. Keep the allowlist mounted at `/etc/seccomp/allowlist.txt`
or update `SECCOMP_ALLOWLIST_FILE`.
