#!/usr/bin/env bash
set -euo pipefail

# Safely decodes the release keystore, verifies store password and alias, performs an actual
# ephemeral JAR signing + verification operation to prove ANDROID_KEY_PASSWORD works, and
# extracts the authoritative expected certificate SHA-256 fingerprint without logging secrets.

KEYSTORE_OUT="${1:-release.keystore}"

missing_secrets=()
[ -z "${ANDROID_KEYSTORE_BASE64:-}" ] && missing_secrets+=("ANDROID_KEYSTORE_BASE64")
[ -z "${ANDROID_KEYSTORE_PASSWORD:-}" ] && missing_secrets+=("ANDROID_KEYSTORE_PASSWORD")
[ -z "${ANDROID_KEY_ALIAS:-}" ] && missing_secrets+=("ANDROID_KEY_ALIAS")
[ -z "${ANDROID_KEY_PASSWORD:-}" ] && missing_secrets+=("ANDROID_KEY_PASSWORD")

if [ "${#missing_secrets[@]}" -gt 0 ]; then
  echo "ERROR: [RELEASE_SIGNING_FAILURE] Missing required GitHub Secrets for release signing: ${missing_secrets[*]}" >&2
  exit 1
fi

for tool in keytool jarsigner jar base64; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: [RELEASE_SIGNING_FAILURE] Required JDK tool '$tool' is missing from PATH." >&2
    exit 1
  fi
done

export ANDROID_KEYSTORE_PASSWORD
export ANDROID_KEY_PASSWORD

CLEAN_B64="$(printf '%s' "$ANDROID_KEYSTORE_BASE64" | tr -d '[:space:]')"
if [ -z "$CLEAN_B64" ]; then
  echo "ERROR: [RELEASE_SIGNING_FAILURE] ANDROID_KEYSTORE_BASE64 is empty after trimming whitespace." >&2
  exit 1
fi

if ! printf '%s' "$CLEAN_B64" | base64 --decode > "$KEYSTORE_OUT" 2>/dev/null; then
  rm -f "$KEYSTORE_OUT"
  echo "ERROR: [RELEASE_SIGNING_FAILURE] Failed to Base64-decode ANDROID_KEYSTORE_BASE64 (invalid Base64 encoding)." >&2
  exit 1
fi

chmod 600 "$KEYSTORE_OUT"
if [ ! -s "$KEYSTORE_OUT" ]; then
  rm -f "$KEYSTORE_OUT"
  echo "ERROR: [RELEASE_SIGNING_FAILURE] Decoded keystore is empty." >&2
  exit 1
fi

if ! keytool -list -keystore "$KEYSTORE_OUT" -storepass:env ANDROID_KEYSTORE_PASSWORD >/dev/null 2>&1; then
  rm -f "$KEYSTORE_OUT"
  echo "ERROR: [RELEASE_SIGNING_FAILURE] Cannot read release keystore (corrupt keystore file or incorrect ANDROID_KEYSTORE_PASSWORD)." >&2
  exit 1
fi

KEYTOOL_OUT=""
if ! KEYTOOL_OUT="$(keytool -list -v -keystore "$KEYSTORE_OUT" -storepass:env ANDROID_KEYSTORE_PASSWORD -alias "$ANDROID_KEY_ALIAS" 2>&1)"; then
  rm -f "$KEYSTORE_OUT"
  echo "ERROR: [RELEASE_SIGNING_FAILURE] Release keystore alias '$ANDROID_KEY_ALIAS' not found or unreadable (check ANDROID_KEY_ALIAS)." >&2
  exit 1
fi

EXPECTED_CERT_SHA256="$(printf '%s\n' "$KEYTOOL_OUT" | grep -E "^\s*SHA256:" | head -n 1 | awk -F'SHA256:' '{print tolower($2)}' | tr -d '[:space:]:')"
if [ -z "$EXPECTED_CERT_SHA256" ]; then
  rm -f "$KEYSTORE_OUT"
  echo "ERROR: [RELEASE_SIGNING_FAILURE] Could not extract SHA-256 certificate fingerprint for alias '$ANDROID_KEY_ALIAS'." >&2
  exit 1
fi

# Perform an actual ephemeral signing + verification operation to prove ANDROID_KEY_PASSWORD unlocks the private key
TMP_SIGN_DIR="$(mktemp -d)"
cleanup_tmp() {
  rm -rf "$TMP_SIGN_DIR"
}
trap cleanup_tmp EXIT

printf 'preflight=true\n' > "$TMP_SIGN_DIR/probe.txt"
jar cf "$TMP_SIGN_DIR/probe.jar" -C "$TMP_SIGN_DIR" probe.txt

if ! jarsigner -keystore "$KEYSTORE_OUT" -storepass:env ANDROID_KEYSTORE_PASSWORD -keypass:env ANDROID_KEY_PASSWORD "$TMP_SIGN_DIR/probe.jar" "$ANDROID_KEY_ALIAS" >/dev/null 2>&1; then
  rm -f "$KEYSTORE_OUT"
  echo "ERROR: [RELEASE_SIGNING_FAILURE] Private-key signing test failed for alias '$ANDROID_KEY_ALIAS' (check ANDROID_KEY_PASSWORD)." >&2
  exit 1
fi

VERIFY_OUT="$(jarsigner -verify -verbose -certs "$TMP_SIGN_DIR/probe.jar" 2>&1 || true)"
if ! printf '%s\n' "$VERIFY_OUT" | grep -q "jar verified."; then
  rm -f "$KEYSTORE_OUT"
  echo "ERROR: [RELEASE_SIGNING_FAILURE] Ephemeral preflight JAR signature failed verification." >&2
  exit 1
fi

SIGNED_CERT_SHA256="$(keytool -printcert -jarfile "$TMP_SIGN_DIR/probe.jar" 2>/dev/null | grep -E "^\s*SHA256:" | head -n 1 | awk -F'SHA256:' '{print tolower($2)}' | tr -d '[:space:]:')"
if [ "$SIGNED_CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]; then
  rm -f "$KEYSTORE_OUT"
  echo "ERROR: [RELEASE_SIGNING_FAILURE] Preflight signed JAR cert SHA-256 does not match keystore alias cert." >&2
  exit 1
fi

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "expected_cert_sha256=$EXPECTED_CERT_SHA256" >> "$GITHUB_OUTPUT"
fi
if [ -n "${GITHUB_ENV:-}" ]; then
  echo "EXPECTED_CERT_SHA256=$EXPECTED_CERT_SHA256" >> "$GITHUB_ENV"
fi

echo "EXPECTED_CERT_SHA256=$EXPECTED_CERT_SHA256"
echo "SUCCESS: Release keystore, alias '$ANDROID_KEY_ALIAS', store password, private-key password, and signing operation verified (cert SHA-256: $EXPECTED_CERT_SHA256)."
