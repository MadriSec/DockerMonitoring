// Package main implements a small stdin → Prometheus bridge for sysdig-style
// NDJSON or custom single-line JSON objects. Event taxonomy is intentionally
// configurable: pin your sysdig version and set --syscall-field / --container-field.
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

func main() {
	listen := flag.String("listen", ":9101", "HTTP listen address for /metrics")
	container := flag.String("container", "", "Fixed Prometheus label `container` (overrides JSON extraction when non-empty)")
	syscallField := flag.String("syscall-field", "evt.type", "Dot path into each JSON object for syscall / event type name")
	containerField := flag.String("container-field", "container.name", "Dot path for container label (ignored if -container is set)")
	allowlistPath := flag.String("allowlist-file", "", "Optional file of allowed syscall names (one per line, # comments); drives unexpected + coverage gauges")
	blockedRaw := flag.String("blocked-if-evt-type-prefix", "", "If non-empty, increment blocked counter when evt.type has this prefix (sysdig-specific; pin your filter output)")
	flag.Parse()

	allow := loadAllowlist(*allowlistPath)

	reg := prometheus.NewRegistry()
	reg.MustRegister(prometheus.NewProcessCollector(prometheus.ProcessCollectorOpts{}))
	reg.MustRegister(prometheus.NewGoCollector())

	lines := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "seccomp_exporter_lines_read_total",
		Help: "NDJSON lines read from stdin",
	})
	parseErr := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "seccomp_exporter_json_parse_errors_total",
		Help: "Lines that failed JSON decode",
	})
	seccompObserved := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "seccomp_syscall_observed_total",
		Help: "Sysdig / audit events counted by extracted syscall or evt.type",
	}, []string{"syscall", "container"})
	seccompBlocked := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "seccomp_syscall_blocked_total",
		Help: "Events classified as blocked/denied (heuristic; tune -blocked-if-evt-type-prefix for your sysdig version)",
	}, []string{"syscall", "container"})
	seccompUnexpected := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "seccomp_unexpected_syscall_total",
		Help: "Observed syscalls not present in allowlist (-allowlist-file)",
	}, []string{"syscall", "container"})
	allowlistSize := prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "seccomp_profile_allowlist_size",
		Help: "Number of syscall names in allowlist file",
	})
	observedDistinct := prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "seccomp_observed_distinct_syscalls",
		Help: "Distinct syscall names observed on stdin since process start",
	})
	allowlistedHit := prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "seccomp_allowlisted_syscalls_observed",
		Help: "Count of allowlist entries that have been observed at least once",
	})
	coverage := prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "seccomp_profile_coverage_ratio",
		Help: "allowlisted_syscalls_observed / allowlist_size (0 if allowlist empty)",
	})

	reg.MustRegister(lines, parseErr, seccompObserved, seccompBlocked, seccompUnexpected,
		allowlistSize, observedDistinct, allowlistedHit, coverage)

	allowlistSize.Set(float64(len(allow)))

	st := &state{
		allow:             allow,
		containerFixed:    *container,
		syscallField:      *syscallField,
		containerField:    *containerField,
		blockedEvtPrefix:  *blockedRaw,
		lines:             lines,
		parseErr:          parseErr,
		seccompObserved:   seccompObserved,
		seccompBlocked:    seccompBlocked,
		seccompUnexpected: seccompUnexpected,
		observedDistinct:  observedDistinct,
		allowlistedHit:    allowlistedHit,
		coverage:          coverage,
		seen:              make(map[string]struct{}),
		hitAllow:          make(map[string]struct{}),
	}

	http.Handle("/metrics", promhttp.HandlerFor(reg, promhttp.HandlerOpts{Registry: reg}))
	go func() {
		log.Printf("seccomp-exporter metrics on http://%s/metrics", *listen)
		if err := http.ListenAndServe(*listen, nil); err != nil {
			log.Fatal(err)
		}
	}()

	sc := bufio.NewScanner(os.Stdin)
	// Very long sysdig JSON lines
	buf := make([]byte, 0, 64*1024)
	sc.Buffer(buf, 1024*1024)

	for sc.Scan() {
		st.handleLine(sc.Text())
	}
	if err := sc.Err(); err != nil {
		log.Printf("stdin: %v", err)
	}
}

type state struct {
	mu sync.Mutex

	allow            map[string]struct{}
	containerFixed   string
	syscallField     string
	containerField   string
	blockedEvtPrefix string

	lines             prometheus.Counter
	parseErr          prometheus.Counter
	seccompObserved   *prometheus.CounterVec
	seccompBlocked    *prometheus.CounterVec
	seccompUnexpected *prometheus.CounterVec
	observedDistinct  prometheus.Gauge
	allowlistedHit    prometheus.Gauge
	coverage          prometheus.Gauge

	seen     map[string]struct{}
	hitAllow map[string]struct{}
}

func (st *state) handleLine(raw string) {
	st.lines.Inc()
	var obj map[string]any
	if err := json.Unmarshal([]byte(raw), &obj); err != nil {
		st.parseErr.Inc()
		return
	}

	syscallName := strings.TrimSpace(getDotPath(obj, st.syscallField))
	if syscallName == "" {
		syscallName = "unknown"
	}

	cLabel := st.containerFixed
	if cLabel == "" {
		cLabel = strings.TrimSpace(getDotPath(obj, st.containerField))
		if cLabel == "" {
			cLabel = "unknown"
		}
	}

	st.mu.Lock()
	defer st.mu.Unlock()

	st.seccompObserved.WithLabelValues(syscallName, cLabel).Inc()
	if _, ok := st.seen[syscallName]; !ok {
		st.seen[syscallName] = struct{}{}
		st.observedDistinct.Set(float64(len(st.seen)))
	}

	if len(st.allow) > 0 {
		if _, ok := st.allow[syscallName]; !ok {
			st.seccompUnexpected.WithLabelValues(syscallName, cLabel).Inc()
		} else if _, seen := st.hitAllow[syscallName]; !seen {
			st.hitAllow[syscallName] = struct{}{}
			st.refreshCoverageLocked()
		}
	}

	if st.blockedEvtPrefix != "" {
		evtType := strings.TrimSpace(getDotPath(obj, "evt.type"))
		if evtType != "" && strings.HasPrefix(evtType, st.blockedEvtPrefix) {
			st.seccompBlocked.WithLabelValues(syscallName, cLabel).Inc()
		}
	}
}

func (st *state) refreshCoverageLocked() {
	nAllow := len(st.allow)
	if nAllow == 0 {
		st.coverage.Set(0)
		st.allowlistedHit.Set(0)
		return
	}
	hit := float64(len(st.hitAllow))
	st.allowlistedHit.Set(hit)
	st.coverage.Set(hit / float64(nAllow))
}

func loadAllowlist(path string) map[string]struct{} {
	out := make(map[string]struct{})
	if path == "" {
		return out
	}
	data, err := os.ReadFile(path)
	if err != nil {
		log.Printf("allowlist: read %s: %v (continuing without allowlist)", path, err)
		return out
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		out[line] = struct{}{}
	}
	log.Printf("loaded %d allowlist entries from %s", len(out), path)
	return out
}

// getDotPath walks a nested map[string]any (from encoding/json) using a dotted path.
func getDotPath(root map[string]any, dot string) string {
	parts := strings.Split(dot, ".")
	var cur any = root
	for _, p := range parts {
		if p == "" {
			continue
		}
		m, ok := cur.(map[string]any)
		if !ok {
			return ""
		}
		cur, ok = m[p]
		if !ok {
			return ""
		}
	}
	switch v := cur.(type) {
	case string:
		return v
	case float64:
		return fmt.Sprintf("%.0f", v)
	case bool:
		return fmt.Sprintf("%v", v)
	default:
		return fmt.Sprintf("%v", v)
	}
}
