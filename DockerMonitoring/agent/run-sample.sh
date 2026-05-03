#!/usr/bin/env bash
# End-to-end test of the native-wrapper agent.
#
# Builds the agent with Maven, compiles the sample harness, runs it with
# the agent attached, and prints the recorded-natives output file.
#
# Expected output:
#   - FakeNative shows a renamed prefixed native method + a non-native wrapper
#   - Calling each wrapper triggers the tracker (visible in logs)
#   - Each native also throws UnsatisfiedLinkError (no .so is linked)
#   - runtime_native_methods.txt contains the recorded method keys

set -euo pipefail

cd "$(dirname "$0")"

AGENT_JAR="target/docker-monitoring-agent.jar"
SAMPLE_DIR="src/test/resources/sample"
BUILD_DIR="target/sample"
OUTPUT_FILE="$(pwd)/runtime_native_methods.txt"

echo "==> building agent with Maven"
mvn -q -DskipTests package

if [[ ! -f "$AGENT_JAR" ]]; then
    echo "ERROR: $AGENT_JAR not produced" >&2
    exit 1
fi

echo "==> compiling sample harness"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" \
    "$SAMPLE_DIR/FakeNative.java" \
    "$SAMPLE_DIR/HarnessMain.java"

# Remove any stale output so we know it was written fresh by this run
rm -f "$OUTPUT_FILE"

echo "==> running harness with -javaagent"
echo
java \
    -javaagent:"$AGENT_JAR" \
    -Ddockermonitoring.native.methods.file="$SAMPLE_DIR/natives.cfg" \
    -Ddockermonitoring.output.file="$OUTPUT_FILE" \
    -cp "$BUILD_DIR" \
    sample.HarnessMain

echo
echo "==> $OUTPUT_FILE"
if [[ -f "$OUTPUT_FILE" ]]; then
    cat "$OUTPUT_FILE"
else
    echo "(output file not written — tracker shutdown hook may have been skipped)"
    exit 1
fi
