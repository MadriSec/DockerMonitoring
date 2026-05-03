#!/usr/bin/env bash
# Run from here → delegates to repository root (observability lives next to this dir).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/start_dashboard.sh" "$@"
