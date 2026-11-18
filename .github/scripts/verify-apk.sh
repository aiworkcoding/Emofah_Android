#!/usr/bin/env bash
set -euo pipefail

# Independent APK integrity, freshness, manifest metadata, and signature verification.
# Does not rely on arbitrary byte-size thresholds or hardcoded app metadata.

APK_PATH="${1:?APK_PATH required}"
EXPECTED_APP_ID="${2:?EXPECTED_APP_ID required}"
EXPECTED_VERSION_CODE="${3:?EXPECTED_VERSION_CODE required}"
EXPECTED_VERSION_NAME="${4:?EXPECTED_VERSION_NAME required}"
BUILD_START_EPOCH="${5:-0}"
EXPECTED_CERT_SHA256="${6:-}"

if [ ! -f "$APK_PATH" ]; then
  echo "ERROR: [APK_GENERATION_FAILURE] APK not found at: $APK_PATH" >&2
  exit 1
fi

APK_BYTES="$(stat -c%s "$APK_PATH")"
if [ "$APK_BYTES" -le 0 ]; then
  echo "ERROR: [APK_VALIDATION_FAILURE] APK file is empty (0 bytes): $APK_PATH" >&2
  exit 1
fi

APK_MTIME="$(stat -c%Y "$APK_PATH")"
if [ "$BUILD_START_EPOCH" -gt 0 ] && [ "$APK_MTIME" -lt "$BUILD_START_EPOCH" ]; then
  echo "ERROR: [APK_VALIDATION_FAILURE] Stale APK detected (mtime=$APK_MTIME < build_start=$BUILD_START_EPOCH): $APK_PATH" >&2
  exit 1
fi

APK_DIR="$(dirname "$APK_PATH")"
APK_BASENAME="$(basename "$APK_PATH")"
OTHER_APKS="$(find "$APK_DIR" -maxdepth 1 -type f -name '*.apk' ! -name "$APK_BASENAME" || true)"
if [ -n "$OTHER_APKS" ]; then
  echo "ERROR: [APK_VALIDATION_FAILURE] Unexpected stale/extra APK files found in $APK_DIR: $OTHER_APKS" >&2
  exit 1
fi

METADATA_JSON="$APK_DIR/output-metadata.json"
if [ -f "$METADATA_JSON" ]; then
  python3 - "$METADATA_JSON" "$EXPECTED_APP_ID" "$EXPECTED_VERSION_CODE" "$EXPECTED_VERSION_NAME" "$APK_BASENAME" <<'PYEOF'
import json, sys
meta_path, exp_app, exp_code, exp_name, exp_file = sys.argv[1:6]
with open(meta_path, "r", encoding="utf-8") as f:
    data = json.load(f)
if data.get("applicationId") != exp_app:
    raise SystemExit(f"output-metadata.json applicationId mismatch: {data.get('applicationId')} != {exp_app}")
elements = data.get("elements") or []
if len(elements) != 1:
    raise SystemExit(f"output-metadata.json expected 1 element, got {len(elements)}")
el = elements[0]
if str(el.get("versionCode")) != str(exp_code):
    raise SystemExit(f"output-metadata.json versionCode mismatch: {el.get('versionCode')} != {exp_code}")
if str(el.get("versionName")) != str(exp_name):
    raise SystemExit(f"output-metadata.json versionName mismatch: {el.get('versionName')} != {exp_name}")
if el.get("outputFile") != exp_file:
    raise SystemExit(f"output-metadata.json outputFile mismatch: {el.get('outputFile')} != {exp_file}")
PYEOF
fi

if ! unzip -tqq "$APK_PATH"; then
  echo "ERROR: [APK_VALIDATION_FAILURE] ZIP archive integrity check failed for $APK_PATH" >&2
  exit 1
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
BUILD_TOOLS_DIR="${ANDROID_BUILD_TOOLS_DIR:-}"
if [ -z "$BUILD_TOOLS_DIR" ] || [ ! -d "$BUILD_TOOLS_DIR" ]; then
  if [ -n "$SDK_ROOT" ] && [ -d "$SDK_ROOT/build-tools" ]; then
    BUILD_TOOLS_DIR="$(find "$SDK_ROOT/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1 || true)"
  fi
fi

if [ -z "$BUILD_TOOLS_DIR" ] || [ ! -x "$BUILD_TOOLS_DIR/aapt2" ] || [ ! -x "$BUILD_TOOLS_DIR/apksigner" ]; then
  echo "ERROR: [SDK_SETUP_FAILURE] Could not locate installed build-tools aapt2/apksigner under ${SDK_ROOT:-<unset>}" >&2
  exit 1
fi

BADGING="$("$BUILD_TOOLS_DIR/aapt2" dump badging "$APK_PATH")"
echo "$BADGING" | head -n 12

PARSED_META="$(python3 - "$BADGING" <<'PYEOF'
import sys, re
badging = sys.argv[1]
pkg_match = re.search(r"^package:\s+name='([^']+)'", badging, re.MULTILINE)
code_match = re.search(r"versionCode='([^']+)'", badging)
name_match = re.search(r"versionName='([^']+)'", badging)

app_id = pkg_match.group(1) if pkg_match else ""
ver_code = code_match.group(1) if code_match else ""
ver_name = name_match.group(1) if name_match else ""

print(app_id)
print(ver_code)
print(ver_name)
PYEOF
)"

ACTUAL_APP_ID="$(printf '%s\n' "$PARSED_META" | sed -n '1p')"
ACTUAL_VERSION_CODE="$(printf '%s\n' "$PARSED_META" | sed -n '2p')"
ACTUAL_VERSION_NAME="$(printf '%s\n' "$PARSED_META" | sed -n '3p')"


if [ "$ACTUAL_APP_ID" != "$EXPECTED_APP_ID" ]; then
  echo "ERROR: [APK_VALIDATION_FAILURE] applicationId mismatch: got '$ACTUAL_APP_ID', expected '$EXPECTED_APP_ID'." >&2
  exit 1
fi
if [ "$ACTUAL_VERSION_CODE" != "$EXPECTED_VERSION_CODE" ]; then
  echo "ERROR: [APK_VALIDATION_FAILURE] versionCode mismatch: got '$ACTUAL_VERSION_CODE', expected '$EXPECTED_VERSION_CODE'." >&2
  exit 1
fi
if [ "$ACTUAL_VERSION_NAME" != "$EXPECTED_VERSION_NAME" ]; then
  echo "ERROR: [APK_VALIDATION_FAILURE] versionName mismatch: got '$ACTUAL_VERSION_NAME', expected '$EXPECTED_VERSION_NAME'." >&2
  exit 1
fi

SIGN_OUT="$("$BUILD_TOOLS_DIR/apksigner" verify --verbose --print-certs "$APK_PATH")"
echo "$SIGN_OUT"

APK_CERT_SHA256="$(printf '%s\n' "$SIGN_OUT" | grep -i "Signer #1 certificate SHA-256 digest:" | head -n 1 | awk -F': ' '{print tolower($2)}' | tr -d '[:space:]:')"
if [ -z "$APK_CERT_SHA256" ]; then
  echo "ERROR: [APK_VALIDATION_FAILURE] Could not extract Signer #1 SHA-256 digest from apksigner output." >&2
  exit 1
fi

if [ -n "$EXPECTED_CERT_SHA256" ]; then
  NORM_EXPECTED="$(printf '%s' "$EXPECTED_CERT_SHA256" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]:')"
  if [ "$APK_CERT_SHA256" != "$NORM_EXPECTED" ]; then
    echo "ERROR: [RELEASE_SIGNING_FAILURE] APK signing certificate SHA-256 ($APK_CERT_SHA256) does not match expected keystore ($NORM_EXPECTED)." >&2
    exit 1
  fi
fi

APK_HUMAN_SIZE="$(numfmt --to=iec-i --suffix=B "$APK_BYTES" 2>/dev/null || echo "${APK_BYTES}B")"
APK_SHA256="$(sha256sum "$APK_PATH" | awk '{print $1}')"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "apk_size_human=$APK_HUMAN_SIZE"
    echo "apk_size_bytes=$APK_BYTES"
    echo "apk_sha256=$APK_SHA256"
    echo "apk_cert_sha256=$APK_CERT_SHA256"
    echo "apk_application_id=$ACTUAL_APP_ID"
    echo "apk_version_code=$ACTUAL_VERSION_CODE"
    echo "apk_version_name=$ACTUAL_VERSION_NAME"
  } >> "$GITHUB_OUTPUT"
fi

echo "APK_BYTES=$APK_BYTES"
echo "APK_HUMAN_SIZE=$APK_HUMAN_SIZE"
echo "APK_SHA256=$APK_SHA256"
echo "APK_CERT_SHA256=$APK_CERT_SHA256"
echo "SUCCESS: Verified $APK_PATH ($APK_HUMAN_SIZE / $APK_BYTES bytes, appId=$ACTUAL_APP_ID, v=$ACTUAL_VERSION_NAME+$ACTUAL_VERSION_CODE)."
