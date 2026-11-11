package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func set(names ...string) map[string]struct{} {
	m := make(map[string]struct{}, len(names))
	for _, n := range names {
		m[n] = struct{}{}
	}
	return m
}

func event(syscall, container string) string {
	return `{"evt":{"type":"` + syscall + `"},"container":{"name":"` + container + `"}}`
}

func TestAllowlistedAndUnexpectedSyscalls(t *testing.T) {
	st := newState(prometheus.NewRegistry(), config{allow: set("read", "write", "futex", "mmap")})

	input := strings.Join([]string{
		event("read", "app"),
		event("read", "app"),
		event("futex", "app"),
		event("fchdir", "app"), // not on the allowlist
		event("fchdir", "app"),
	}, "\n")
	if err := st.consume(strings.NewReader(input)); err != nil {
		t.Fatal(err)
	}

	check := func(name string, got, want float64) {
		t.Helper()
		if got != want {
			t.Errorf("%s = %v, want %v", name, got, want)
		}
	}
	check("lines", testutil.ToFloat64(st.lines), 5)
	check("parse errors", testutil.ToFloat64(st.parseErr), 0)
	check("observed read", testutil.ToFloat64(st.seccompObserved.WithLabelValues("read", "app")), 2)
	check("unexpected fchdir", testutil.ToFloat64(st.seccompUnexpected.WithLabelValues("fchdir", "app")), 2)
	check("unexpected series", float64(testutil.CollectAndCount(st.seccompUnexpected)), 1)
	check("allowlist size", testutil.ToFloat64(st.allowlistSize), 4)
	check("distinct observed", testutil.ToFloat64(st.observedDistinct), 3)
	check("allowlisted observed", testutil.ToFloat64(st.allowlistedHit), 2)
	check("coverage", testutil.ToFloat64(st.coverage), 0.5)
}

func TestNoAllowlistMeansNothingIsUnexpected(t *testing.T) {
	st := newState(prometheus.NewRegistry(), config{})
	st.handleLine(event("ptrace", "app"))

	if n := testutil.CollectAndCount(st.seccompUnexpected); n != 0 {
		t.Errorf("unexpected series = %d, want 0 without an allowlist", n)
	}
	if got := testutil.ToFloat64(st.coverage); got != 0 {
		t.Errorf("coverage = %v, want 0", got)
	}
}

func TestBadLinesAreCountedAndDoNotStopTheStream(t *testing.T) {
	st := newState(prometheus.NewRegistry(), config{allow: set("read")})
	st.now = func() time.Time { return time.Unix(1700000000, 0) }

	st.handleLine("not json")
	if got := testutil.ToFloat64(st.lastEvent); got != 0 {
		t.Errorf("last event timestamp = %v after a bad line, want 0", got)
	}

	if err := st.consume(strings.NewReader("{broken\n" + event("read", "app") + "\n")); err != nil {
		t.Fatal(err)
	}
	if got := testutil.ToFloat64(st.parseErr); got != 2 {
		t.Errorf("parse errors = %v, want 2", got)
	}
	if got := testutil.ToFloat64(st.seccompObserved.WithLabelValues("read", "app")); got != 1 {
		t.Errorf("observed read = %v, want 1", got)
	}
	if got := testutil.ToFloat64(st.lastEvent); got != 1700000000 {
		t.Errorf("last event timestamp = %v, want 1700000000", got)
	}
}

func TestLabelsFallBackToUnknownAndFixedContainerWins(t *testing.T) {
	st := newState(prometheus.NewRegistry(), config{})
	st.handleLine(`{"something":"else"}`)
	if got := testutil.ToFloat64(st.seccompObserved.WithLabelValues("unknown", "unknown")); got != 1 {
		t.Errorf("unknown/unknown = %v, want 1", got)
	}

	fixed := newState(prometheus.NewRegistry(), config{containerFixed: "pinned"})
	fixed.handleLine(event("read", "from-json"))
	if got := testutil.ToFloat64(fixed.seccompObserved.WithLabelValues("read", "pinned")); got != 1 {
		t.Errorf("read/pinned = %v, want 1", got)
	}
}

func TestBlockedPrefix(t *testing.T) {
	st := newState(prometheus.NewRegistry(), config{blockedEvtPrefix: "seccomp_"})
	st.handleLine(event("seccomp_kill", "app"))
	st.handleLine(event("read", "app"))
	if got := testutil.ToFloat64(st.seccompBlocked.WithLabelValues("seccomp_kill", "app")); got != 1 {
		t.Errorf("blocked = %v, want 1", got)
	}
	if n := testutil.CollectAndCount(st.seccompBlocked); n != 1 {
		t.Errorf("blocked series = %d, want 1", n)
	}
}

func TestLoadAllowlist(t *testing.T) {
	path := filepath.Join(t.TempDir(), "allow.txt")
	if err := os.WriteFile(path, []byte("# comment\nread\n\n  write  \nread\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	got := loadAllowlist(path)
	if len(got) != 2 {
		t.Fatalf("len = %d, want 2 (%v)", len(got), got)
	}
	for _, name := range []string{"read", "write"} {
		if _, ok := got[name]; !ok {
			t.Errorf("%q missing", name)
		}
	}
	if n := len(loadAllowlist(filepath.Join(t.TempDir(), "missing.txt"))); n != 0 {
		t.Errorf("missing file gave %d entries, want 0", n)
	}
	if n := len(loadAllowlist("")); n != 0 {
		t.Errorf("empty path gave %d entries, want 0", n)
	}
}

func TestGetDotPath(t *testing.T) {
	obj := map[string]any{
		"evt":  map[string]any{"type": "openat", "num": float64(42)},
		"flat": "x",
	}
	cases := map[string]string{
		"evt.type":      "openat",
		"evt.num":       "42",
		"flat":          "x",
		"evt.missing":   "",
		"flat.too.deep": "",
	}
	for path, want := range cases {
		if got := getDotPath(obj, path); got != want {
			t.Errorf("getDotPath(%q) = %q, want %q", path, got, want)
		}
	}
}
