#!/usr/bin/env bash
set -euo pipefail

# Runs ./gradlew with bounded retries strictly limited to transient network/repository errors.
# Deterministic build, configuration, compilation, and test failures abort immediately.

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <gradle-args...>" >&2
  exit 2
fi

MAX_ATTEMPTS="${GRADLE_RETRY_MAX_ATTEMPTS:-3}"
RETRY_DELAY_SEC="${GRADLE_RETRY_DELAY_SEC:-15}"
GRADLE_CMD="${GRADLE_WRAPPER_CMD:-./gradlew}"

is_deterministic_failure() {
  local log_file="$1"
  grep -qiE \
    "Multiple arguments were provided for command-line option|not found in configuration container|Problem configuring task|Task '.*' not found|Could not find method|Could not compile build file|Compilation error|Unresolved reference|cannot find symbol|No parameter with name|Type mismatch|e: /.*\.kt:|Project '.*' not found|Unsupported class file major version|There were failing tests|AssertionError|ComparisonFailure|Received status code 40[0134]|status code: 40[0134]" \
    "$log_file"
}

is_memory_exhaustion() {
  local log_file="$1"
  grep -qiE \
    "OutOfMemoryError|Java heap space|GC overhead limit exceeded|Metaspace|Expiring Daemon because JVM heap space is exhausted|Daemon disappeared unexpectedly" \
    "$log_file"
}

is_transient_network_failure() {
  local log_file="$1"
  grep -qiE \
    "Read timed out|Connection timed out|ConnectException|SocketTimeoutException|Connection reset|UnknownHostException|Temporary failure in name resolution|NoRouteToHostException|Remote host terminated the handshake|status code: (429|500|502|503|504)|Received status code (429|500|502|503|504)|502 Bad Gateway|503 Service|504 Gateway|Could not GET .* (50[0-4]|timed out|Connection reset)|Could not HEAD .* (50[0-4]|timed out|Connection reset)" \
    "$log_file"
}

classify_failure() {
  local log_file="$1"
  if is_memory_exhaustion "$log_file"; then
    echo "MEMORY_EXHAUSTION_FAILURE"
  elif grep -qiE "Multiple arguments were provided|not found in configuration container|Problem configuring task|Task '.*' not found|Could not find method|Could not compile build file|Project '.*' not found|Unsupported class file" "$log_file"; then
    echo "GRADLE_CONFIGURATION_FAILURE"
  elif grep -qiE "Compilation error|Unresolved reference|cannot find symbol|No parameter with name|Type mismatch|e: /.*\.kt:|> Task .*:(compile|ksp)[^ ]* FAILED|Execution failed for task '.*:(compile|ksp)" "$log_file"; then
    echo "PROJECT_COMPILATION_FAILURE"
  elif grep -qiE "> Task .*:test[^ ]* FAILED|Execution failed for task '.*:test|There were failing tests|AssertionError|ComparisonFailure" "$log_file"; then
    echo "UNIT_TEST_FAILURE"
  elif grep -qiE "RELEASE_SIGNING_FAILURE|keystore|signingConfig" "$log_file"; then
    echo "RELEASE_SIGNING_FAILURE"
  elif grep -qiE "> Task .*:(package|assemble|bundle|merge|process|dex|strip|shrink|minify)[^ ]* FAILED|Execution failed for task '.*:(package|assemble|bundle|merge|process|dex|strip|shrink|minify)" "$log_file"; then
    echo "APK_GENERATION_FAILURE"
  elif grep -qiE "Could not resolve|Could not GET|Could not HEAD|Could not find [^ ]+:[^ ]+:" "$log_file"; then
    echo "GRADLE_DEPENDENCY_FAILURE"
  elif is_transient_network_failure "$log_file"; then
    echo "TRANSIENT_NETWORK_FAILURE"
  else
    echo "GRADLE_EXECUTION_FAILURE"
  fi
}

record_failure_env() {
  local category="$1"
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "FAILURE_CATEGORY=$category" >> "$GITHUB_ENV"
    if [ "$category" = "PROJECT_COMPILATION_FAILURE" ]; then
      echo "FAILED_STAGE=${COMPILATION_STAGE_LABEL:-Kotlin/Java Compilation}" >> "$GITHUB_ENV"
    elif [ "$category" = "UNIT_TEST_FAILURE" ]; then
      echo "FAILED_STAGE=${UNIT_TEST_STAGE_LABEL:-Project Verification (Unit Tests)}" >> "$GITHUB_ENV"
    elif [ "$category" = "APK_GENERATION_FAILURE" ]; then
      echo "FAILED_STAGE=${APK_STAGE_LABEL:-APK Packaging}" >> "$GITHUB_ENV"
    elif [ "$category" = "MEMORY_EXHAUSTION_FAILURE" ]; then
      echo "FAILED_STAGE=${CURRENT_STAGE_LABEL:-Gradle Execution (Memory Exhaustion)}" >> "$GITHUB_ENV"
    fi
  fi
}

attempt=1
log_file="$(mktemp)"
trap 'rm -f "$log_file"' EXIT

while [ "$attempt" -le "$MAX_ATTEMPTS" ]; do
  echo "Running (attempt $attempt/$MAX_ATTEMPTS): $GRADLE_CMD $*"
  set +e
  "$GRADLE_CMD" "$@" 2>&1 | tee "$log_file"
  rc="${PIPESTATUS[0]}"
  set -e

  if [ "$rc" -eq 0 ]; then
    exit 0
  fi

  if is_deterministic_failure "$log_file"; then
    category="$(classify_failure "$log_file")"
    record_failure_env "$category"
    echo "ERROR: [$category] Deterministic Gradle failure detected (exit code $rc); aborting without retry." >&2
    exit "$rc"
  fi

  if is_memory_exhaustion "$log_file"; then
    category="MEMORY_EXHAUSTION_FAILURE"
    record_failure_env "$category"
    echo "ERROR: [$category] JVM memory exhaustion detected (exit code $rc); aborting without retry." >&2
    exit "$rc"
  fi

  if is_transient_network_failure "$log_file"; then
    if [ "$attempt" -lt "$MAX_ATTEMPTS" ]; then
      echo "Transient repository/network error detected on attempt $attempt/$MAX_ATTEMPTS (exit code $rc); retrying in ${RETRY_DELAY_SEC}s..." >&2
      sleep "$RETRY_DELAY_SEC"
      attempt=$((attempt + 1))
      continue
    fi
    if [ -n "${GITHUB_ENV:-}" ]; then
      echo "FAILURE_CATEGORY=TRANSIENT_NETWORK_EXHAUSTED" >> "$GITHUB_ENV"
    fi
    echo "ERROR: [TRANSIENT_NETWORK_EXHAUSTED] Transient error persisted across $MAX_ATTEMPTS attempts (exit code $rc)." >&2
    exit "$rc"
  fi

  category="$(classify_failure "$log_file")"
  record_failure_env "$category"
  echo "ERROR: [$category] Unretryable Gradle failure (exit code $rc); aborting without retry." >&2
  exit "$rc"
done
