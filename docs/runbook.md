# Alert runbook

One entry per alert in
[`dockermonitoring.rules.yml`](../observability/prometheus/rules/dockermonitoring.rules.yml).
Each alert links here through its `runbook_url` annotation. CI fails if an alert has no entry.

Useful places while working an alert:

| What | Where (standalone stack) |
|---|---|
| Firing alerts, silences | Alertmanager, http://localhost:9096 |
| Rule state and expressions | Prometheus, http://localhost:9095/alerts |
| Dashboards | Grafana, http://localhost:3030 |
| Exporter metrics | `curl http://<exporter>:9101/metrics` |
| Agent metrics | `curl http://<app>:9464/metrics` |

Severities: **critical** means checks are not running or a container is misbehaving now;
**warning** means a gap that needs a fix but is not getting worse; **info** is a tuning hint.

---

## MonitoringTargetDown

**Severity:** critical. **Fires when** Prometheus has failed to scrape a target for 1 minute.

**What it means.** The Java agent's metrics endpoint or the seccomp exporter is unreachable.
Nothing from that target is being checked, and every other alert for the same instance is
suppressed until it is back.

**Check.**
1. Prometheus → Status → Targets shows the scrape error (refused, timeout, DNS).
2. Is the container running? `docker ps -a --filter name=<name>`; read its last log lines.
3. The exporter exits when its input ends. If sysdig died, the exporter went with it.
4. For the agent: did the JVM start with `-javaagent`? Look for `dockermonitoring` in the app log.

**Fix.** Restart the failed piece. For the exporter, restart the whole pipe
(`sysdig … | seccomp-exporter`), not only the exporter.

## SeccompEventStreamStalled

**Severity:** warning. **Fires when** the exporter is up but has parsed no event for 2 minutes.

**What it means.** The event source went quiet. Syscalls made now are not checked.

**Check.**
1. Is sysdig still running? `pgrep -af sysdig`.
2. Is the watched container still running, and under the same name? The sysdig filter is by
   container name, so a renamed or recreated container stops matching.
3. An idle JVM still makes `futex` calls every few seconds, so true silence is unusual.

**Fix.** Restart the sysdig pipe with the right container name.

## SeccompNoEventsSinceStart

**Severity:** warning. **Fires when** the exporter has been up 5 minutes and has read no line.

**What it means.** The event source was never connected, or its filter matches nothing.

**Check.** Run the sysdig command by hand without the pipe and confirm it prints events.
Check the container name in the filter, and that sysdig has the privileges it needs
(`--modern-bpf` needs root and a kernel with BTF).

**Fix.** Correct the filter or privileges and restart the pipe.

## SeccompEventParseErrors

**Severity:** warning. **Fires when** more than 1% of input lines fail JSON decoding for 5 minutes.

**What it means.** Lines that fail to parse are dropped, so the syscalls in them are not checked.

**Check.**
1. Look at raw input: run the sysdig command by hand and read a few lines.
2. Common causes: sysdig warnings mixed into stdout, a `-p` format that is not valid JSON
   for some events (unescaped quotes in a field), or a changed sysdig version.

**Fix.** Send sysdig's stderr elsewhere, and keep the output format to fields that cannot
contain quotes (`syscall.type`, `container.name`, `proc.name`).

## UnexpectedSyscallObserved

**Severity:** warning. **Fires when** a container has made a syscall that is not on its
allowlist, at least once since the exporter started.

**What it means.** With the seccomp profile enforced, this call would be denied. It stays
firing until the profile is fixed and the exporter restarted, because a single call at
startup is as real a gap as a repeated one.

**Check.**
1. Which syscall, how many times, which process? The dashboard table
   "Observed syscalls NOT on the allowlist" has the count.
2. Is it startup-only (count stays flat) or on a request path (count grows)?
3. Decide whether the application legitimately needs it. Example from this project:
   `fchdir`, 4 calls, made by the JVM when it creates `/tmp/hsperfdata_<user>` at startup.
   Legitimate, and denying it only disables `jps`/`jstat`.
4. A syscall with no legitimate reason (`ptrace`, `mount`, `init_module` …) is a security
   event, not a profile gap. Treat it as one.

**Fix.** Legitimate: add it to the profile and record why. Not legitimate: stop the
container and investigate how it got there.

## UnexpectedSyscallOngoing

**Severity:** critical. **Fires when** a non-allowlisted syscall is being made continuously
for 2 minutes.

**What it means.** Either the profile misses a syscall on a hot path, in which case the
application would be failing under enforcement, or the container is doing something it
should not.

**Check.** Same as [UnexpectedSyscallObserved](#unexpectedsyscallobserved), now. Find the
process: `sysdig -p '%proc.name %proc.cmdline' container.name=<c> and evt.type=<syscall>`.

**Fix.** Same as above. Do not silence it without a decision recorded.

## SeccompAllowlistNotLoaded

**Severity:** critical. **Fires when** the exporter reports an allowlist of size 0.

**What it means.** Without an allowlist every syscall counts as expected. The unexpected
syscall alerts cannot fire, so the stack looks healthy while checking nothing.

**Check.** The exporter log says `allowlist: read <path>: <error>` when the file is missing.
Check the `--allowlist-file` flag (or `SECCOMP_ALLOWLIST_FILE`) and the volume mount.

**Fix.** Mount the right file and restart the exporter.

## SeccompProfileMostlyUnused

**Severity:** info. **Fires when** under 25% of the allowlist has been used after an hour
of traffic.

**What it means.** The profile may be much wider than this workload needs. Some unused
entries are expected: error paths, shutdown, features the traffic did not reach. For
reference, a Tomcat 7 workload in this project used 45%.

**Check.** Was the hour representative? Compare with a full workload run before narrowing
anything.

**Fix.** None required. Narrow the profile only with evidence from a complete workload.

## NativeMethodListNotLoaded

**Severity:** warning. **Fires when** the Java agent reports zero listed native methods.

**What it means.** The agent is attached but has nothing to wrap, so no native calls are counted.

**Check.** The JVM property `-Ddockermonitoring.native.methods.file=<file>`: is it set, does
the file exist inside the container, and is it non-empty? The agent prints a
`[DockerMonitoring]` line on stderr when the property is missing or the file cannot be read.

**Fix.** Mount the list at the path the agent is given and restart the JVM.

## NativeMethodsNoneWrapped

**Severity:** warning. **Fires when** the list is loaded but no method is wrapped after 10 minutes.

**What it means.** Either the list belongs to a different application or JDK, or every listed
class was loaded before the agent started. The agent cannot wrap those.

**Check.** The coverage file (`-Ddockermonitoring.coverage.file`) gives the status of every
listed method. All `not_loaded` points to a wrong list; all `preloaded` points to
`-Ddockermonitoring.wrap.jdk=false` or a list of early JDK classes only.

**Fix.** Use the list generated for this image.
