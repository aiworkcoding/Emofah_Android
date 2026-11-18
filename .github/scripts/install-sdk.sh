#!/usr/bin/env bash
set -euo pipefail

# Provisions required Android SDK packages dynamically from the project's configured compileSdk
# and resolves the matching installed build-tools directory without hardcoding 34.0.0.

COMPILE_SDK="${1:-${COMPILE_SDK:-}}"
if [ -z "$COMPILE_SDK" ] || ! [[ "$COMPILE_SDK" =~ ^[0-9]+$ ]]; then
  echo "ERROR: [PROJECT_CONFIGURATION_FAILURE] Valid numeric COMPILE_SDK is required (got: '${COMPILE_SDK:-}')." >&2
  exit 2
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT" ]; then
  echo "ERROR: [SDK_SETUP_FAILURE] ANDROID_SDK_ROOT/ANDROID_HOME is missing or invalid." >&2
  exit 1
fi
if ! command -v sdkmanager >/dev/null 2>&1; then
  echo "ERROR: [SDK_SETUP_FAILURE] sdkmanager not found on PATH." >&2
  exit 1
fi

MAX_ATTEMPTS="${SDK_RETRY_MAX_ATTEMPTS:-3}"
RETRY_DELAY_SEC="${SDK_RETRY_DELAY_SEC:-10}"

discover_build_tools_pkg() {
  local list_out
  local candidates
  list_out="$(sdkmanager --list 2>/dev/null || true)"
  candidates="$(printf '%s\n' "$list_out" | awk -F'|' '{gsub(/^[[:space:]]+|[[:space:]]+$/, "", $1); print $1}' | grep -E "^build-tools;${COMPILE_SDK}\.[0-9]+\.[0-9]+$" | sort -uV || true)"
  if [ -n "$candidates" ]; then
    printf '%s\n' "$candidates" | tail -n 1
    return 0
  fi
  # Fallback to release candidate if no stable exists for that API level
  candidates="$(printf '%s\n' "$list_out" | awk -F'|' '{gsub(/^[[:space:]]+|[[:space:]]+$/, "", $1); print $1}' | grep -E "^build-tools;${COMPILE_SDK}\.[0-9]+\.[0-9]+.*" | sort -uV || true)"
  if [ -n "$candidates" ]; then
    printf '%s\n' "$candidates" | tail -n 1
    return 0
  fi
  return 1
}

# Check locally installed build-tools matching COMPILE_SDK first, otherwise query sdkmanager
BUILD_TOOLS_PKG=""
if [ -d "$SDK_ROOT/build-tools" ]; then
  INSTALLED_BT_DIR="$(find "$SDK_ROOT/build-tools" -maxdepth 1 -mindepth 1 -type d -name "${COMPILE_SDK}.*" | sort -V | tail -n 1 || true)"
  if [ -n "$INSTALLED_BT_DIR" ]; then
    BUILD_TOOLS_PKG="build-tools;$(basename "$INSTALLED_BT_DIR")"
  fi
fi

if [ -z "$BUILD_TOOLS_PKG" ]; then
  attempt=1
  while [ "$attempt" -le "$MAX_ATTEMPTS" ]; do
    if BUILD_TOOLS_PKG="$(discover_build_tools_pkg)"; then
      break
    fi
    if [ "$attempt" -lt "$MAX_ATTEMPTS" ]; then
      echo "Retrying SDK package catalogue query (attempt $attempt/$MAX_ATTEMPTS)..." >&2
      sleep "$RETRY_DELAY_SEC"
    fi
    attempt=$((attempt + 1))
  done
fi

if [ -z "$BUILD_TOOLS_PKG" ]; then
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "FAILURE_CATEGORY=SDK_PACKAGE_NOT_FOUND" >> "$GITHUB_ENV"
  fi
  echo "ERROR: [SDK_PACKAGE_NOT_FOUND] Could not discover a valid build-tools package for API level ${COMPILE_SDK}." >&2
  exit 1
fi

BUILD_TOOLS_VER="${BUILD_TOOLS_PKG#build-tools;}"
REQUIRED_PACKAGES=("platform-tools" "platforms;android-${COMPILE_SDK}" "$BUILD_TOOLS_PKG")

attempt=1
log_file="$(mktemp)"
trap 'rm -f "$log_file"' EXIT
installed=false

while [ "$attempt" -le "$MAX_ATTEMPTS" ]; do
  echo "Installing Android SDK packages (attempt $attempt/$MAX_ATTEMPTS): ${REQUIRED_PACKAGES[*]}"
  set +e
  printf 'y\n%.0s' {1..30} | sdkmanager --install "${REQUIRED_PACKAGES[@]}" 2>&1 | tee "$log_file"
  rc="${PIPESTATUS[1]}"
  set -e

  if [ "$rc" -eq 0 ]; then
    installed=true
    break
  fi

  if grep -qiE "Failed to find package|Unknown package|Package path is not valid" "$log_file"; then
    if [ -n "${GITHUB_ENV:-}" ]; then
      echo "FAILURE_CATEGORY=SDK_PACKAGE_NOT_FOUND" >> "$GITHUB_ENV"
    fi
    echo "ERROR: [SDK_PACKAGE_NOT_FOUND] Deterministic missing SDK package detected; aborting without retry." >&2
    exit 1
  fi

  if [ "$attempt" -lt "$MAX_ATTEMPTS" ]; then
    echo "Transient SDK download error detected; retrying in ${RETRY_DELAY_SEC}s..." >&2
    sleep "$RETRY_DELAY_SEC"
  fi
  attempt=$((attempt + 1))
done

if [ "$installed" != "true" ]; then
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "FAILURE_CATEGORY=SDK_SETUP_FAILURE" >> "$GITHUB_ENV"
  fi
  echo "ERROR: [SDK_SETUP_FAILURE] SDK package installation failed after $MAX_ATTEMPTS attempts." >&2
  exit 1
fi

BUILD_TOOLS_DIR="$SDK_ROOT/build-tools/$BUILD_TOOLS_VER"
for required_file in \
  "$SDK_ROOT/platform-tools/adb" \
  "$SDK_ROOT/platforms/android-${COMPILE_SDK}/android.jar" \
  "$BUILD_TOOLS_DIR/aapt2" \
  "$BUILD_TOOLS_DIR/apksigner" \
  "$BUILD_TOOLS_DIR/d8"; do
  if [ ! -e "$required_file" ]; then
    if [ -n "${GITHUB_ENV:-}" ]; then
      echo "FAILURE_CATEGORY=SDK_SETUP_FAILURE" >> "$GITHUB_ENV"
    fi
    echo "ERROR: [SDK_SETUP_FAILURE] Missing required SDK component: $required_file" >&2
    exit 1
  fi
done

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "ANDROID_BUILD_TOOLS_VERSION=$BUILD_TOOLS_VER" >> "$GITHUB_ENV"
  echo "ANDROID_BUILD_TOOLS_DIR=$BUILD_TOOLS_DIR" >> "$GITHUB_ENV"
fi

echo "SUCCESS: Verified SDK components (platform-tools, android-${COMPILE_SDK}, build-tools ${BUILD_TOOLS_VER})."
