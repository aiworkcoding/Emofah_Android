#!/usr/bin/env bash
set -euo pipefail

# Verifies Android App Bundle (.aab) existence, freshness, ZIP integrity, bundletool structural validity,
# bundle manifest package/versionCode/versionName against authoritative Gradle metadata, JAR signature
# validity without false strict CA-chain rejection of self-signed certs, and exact signer SHA-256 parity
# with both the release keystore and the signed release APK.

AAB_PATH="${1:?AAB_PATH required}"
EXPECTED_APP_ID="${2:?EXPECTED_APP_ID required}"
EXPECTED_VERSION_CODE="${3:?EXPECTED_VERSION_CODE required}"
EXPECTED_VERSION_NAME="${4:?EXPECTED_VERSION_NAME required}"
EXPECTED_CERT_SHA256="${5:?EXPECTED_CERT_SHA256 required}"
APK_CERT_SHA256="${6:-}"
BUILD_START_EPOCH="${7:-0}"
BUNDLETOOL_JAR="${8:-${BUNDLETOOL_JAR_PATH:-/tmp/bundletool-all-1.18.3.jar}}"

BUNDLETOOL_VERSION="1.18.3"
BUNDLETOOL_URL="https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar"
BUNDLETOOL_EXPECTED_SHA256="${BUNDLETOOL_SHA256:-a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29}"

if [ ! -f "$AAB_PATH" ]; then
  echo "ERROR: [APK_GENERATION_FAILURE] Release AAB not found at: $AAB_PATH" >&2
  exit 1
fi

AAB_BYTES="$(stat -c%s "$AAB_PATH")"
if [ "$AAB_BYTES" -le 0 ]; then
  echo "ERROR: [APK_VALIDATION_FAILURE] Release AAB is empty (0 bytes): $AAB_PATH" >&2
  exit 1
fi

AAB_MTIME="$(stat -c%Y "$AAB_PATH")"
if [ "$BUILD_START_EPOCH" -gt 0 ] && [ "$AAB_MTIME" -lt "$BUILD_START_EPOCH" ]; then
  echo "ERROR: [APK_VALIDATION_FAILURE] Stale AAB detected (mtime=$AAB_MTIME < build_start=$BUILD_START_EPOCH): $AAB_PATH" >&2
  exit 1
fi

if ! unzip -tqq "$AAB_PATH"; then
  echo "ERROR: [APK_VALIDATION_FAILURE] ZIP archive integrity check failed for $AAB_PATH" >&2
  exit 1
fi

if [ ! -f "$BUNDLETOOL_JAR" ]; then
  echo "Downloading bundletool ${BUNDLETOOL_VERSION} for AAB structural & manifest verification..."
  mkdir -p "$(dirname "$BUNDLETOOL_JAR")"
  curl -fsSL --retry 3 --retry-delay 5 "$BUNDLETOOL_URL" -o "$BUNDLETOOL_JAR"
fi

if [ -n "$BUNDLETOOL_EXPECTED_SHA256" ]; then
  ACTUAL_BT_SHA256="$(sha256sum "$BUNDLETOOL_JAR" | awk '{print $1}')"
  if [ "$ACTUAL_BT_SHA256" != "$BUNDLETOOL_EXPECTED_SHA256" ]; then
    echo "ERROR: [APK_VALIDATION_FAILURE] bundletool SHA-256 mismatch: $ACTUAL_BT_SHA256 != $BUNDLETOOL_EXPECTED_SHA256" >&2
    exit 1
  fi
fi

echo "Running bundletool validate..."
java -jar "$BUNDLETOOL_JAR" validate --bundle="$AAB_PATH"

echo "Inspecting AAB manifest via bundletool..."
AAB_MANIFEST="$(java -jar "$BUNDLETOOL_JAR" dump manifest --bundle="$AAB_PATH")"
echo "$AAB_MANIFEST" | head -n 25

python3 - "$AAB_MANIFEST" "$EXPECTED_APP_ID" "$EXPECTED_VERSION_CODE" "$EXPECTED_VERSION_NAME" <<'PYEOF'
import sys, xml.etree.ElementTree as ET
manifest_xml, exp_app, exp_code, exp_name = sys.argv[1:5]
root = ET.fromstring(manifest_xml)
android_ns = "{http://schemas.android.com/apk/res/android}"
pkg = root.attrib.get("package", "")
ver_code = root.attrib.get(f"{android_ns}versionCode", "")
ver_name = root.attrib.get(f"{android_ns}versionName", "")
if pkg != exp_app:
    raise SystemExit(f"AAB manifest package mismatch: {pkg} != {exp_app}")
if str(ver_code) != str(exp_code):
    raise SystemExit(f"AAB manifest versionCode mismatch: {ver_code} != {exp_code}")
if str(ver_name) != str(exp_name):
    raise SystemExit(f"AAB manifest versionName mismatch: {ver_name} != {exp_name}")
print(f"AAB manifest verified: package={pkg}, versionCode={ver_code}, versionName={ver_name}")
PYEOF

echo "Verifying AAB JAR signature and entry digests..."
set +e
JARSIGNER_OUT="$(jarsigner -verify -verbose -certs "$AAB_PATH" 2>&1)"
JARSIGNER_RC=$?
set -e
echo "$JARSIGNER_OUT" | tail -n 25

if [ "$JARSIGNER_RC" -ne 0 ]; then
  echo "ERROR: [RELEASE_SIGNING_FAILURE] jarsigner exited with code $JARSIGNER_RC on $AAB_PATH" >&2
  exit 1
fi
if ! printf '%s\n' "$JARSIGNER_OUT" | grep -q "jar verified."; then
  echo "ERROR: [RELEASE_SIGNING_FAILURE] AAB is not verified by jarsigner." >&2
  exit 1
fi
if printf '%s\n' "$JARSIGNER_OUT" | grep -qiE "jar is unsigned|entries that are not signed|tampered|SecurityException|Digest mismatch"; then
  echo "ERROR: [RELEASE_SIGNING_FAILURE] AAB signature integrity failure detected." >&2
  exit 1
fi

AAB_PRINTCERT="$(keytool -printcert -jarfile "$AAB_PATH" 2>&1 || true)"
echo "$AAB_PRINTCERT" | grep -E "Owner:|Issuer:|SHA256:" || true
AAB_CERT_SHA256="$(printf '%s\n' "$AAB_PRINTCERT" | grep -E "^\s*SHA256:" | head -n 1 | awk -F'SHA256:' '{print tolower($2)}' | tr -d '[:space:]:')"
if [ -z "$AAB_CERT_SHA256" ]; then
  echo "ERROR: [RELEASE_SIGNING_FAILURE] Could not extract signer SHA-256 fingerprint from $AAB_PATH" >&2
  exit 1
fi

NORM_EXPECTED="$(printf '%s' "$EXPECTED_CERT_SHA256" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]:')"
if [ "$AAB_CERT_SHA256" != "$NORM_EXPECTED" ]; then
  echo "ERROR: [RELEASE_SIGNING_FAILURE] AAB signer SHA-256 ($AAB_CERT_SHA256) does not match expected keystore ($NORM_EXPECTED)." >&2
  exit 1
fi

if [ -n "$APK_CERT_SHA256" ]; then
  NORM_APK="$(printf '%s' "$APK_CERT_SHA256" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]:')"
  if [ "$AAB_CERT_SHA256" != "$NORM_APK" ]; then
    echo "ERROR: [RELEASE_SIGNING_FAILURE] AAB signer SHA-256 ($AAB_CERT_SHA256) does not match APK signer ($NORM_APK)." >&2
    exit 1
  fi
fi

AAB_HUMAN_SIZE="$(ls -lh "$AAB_PATH" | awk '{print $5}')"
AAB_SHA256="$(sha256sum "$AAB_PATH" | awk '{print $1}')"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "aab_size_human=$AAB_HUMAN_SIZE"
    echo "aab_size_bytes=$AAB_BYTES"
    echo "aab_sha256=$AAB_SHA256"
    echo "aab_cert_sha256=$AAB_CERT_SHA256"
  } >> "$GITHUB_OUTPUT"
fi

echo "AAB_BYTES=$AAB_BYTES"
echo "AAB_HUMAN_SIZE=$AAB_HUMAN_SIZE"
echo "AAB_SHA256=$AAB_SHA256"
echo "AAB_CERT_SHA256=$AAB_CERT_SHA256"
echo "SUCCESS: Verified $AAB_PATH ($AAB_HUMAN_SIZE / $AAB_BYTES bytes, cert SHA-256: $AAB_CERT_SHA256)."
