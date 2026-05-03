#!/usr/bin/env bash
# Thin wrapper — full capture lives at repository root.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/otel_capture.sh" "$@"
