#!/usr/bin/env bash
set -euo pipefail

echo "Running independent tests for verify-apk.sh parsing logic..."

# Test Python parsing logic with the exact incident badging output
INCIDENT_BADGING="package: name='com.emofah.agencies' versionCode='1' versionName='0.1.0' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='34' compileSdkVersionCodename='14'
sdkVersion:'26'
targetSdkVersion:'34'"

PARSED_META="$(python3 - "$INCIDENT_BADGING" <<'PYEOF'
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

EXTRACTED_APP_ID="$(printf '%s\n' "$PARSED_META" | sed -n '1p')"
EXTRACTED_VERSION_CODE="$(printf '%s\n' "$PARSED_META" | sed -n '2p')"
EXTRACTED_VERSION_NAME="$(printf '%s\n' "$PARSED_META" | sed -n '3p')"

echo "Extracted App ID: '$EXTRACTED_APP_ID'"
echo "Extracted Version Code: '$EXTRACTED_VERSION_CODE'"
echo "Extracted Version Name: '$EXTRACTED_VERSION_NAME'"

if [ "$EXTRACTED_APP_ID" != "com.emofah.agencies" ]; then
  echo "FAIL: Expected 'com.emofah.agencies', got '$EXTRACTED_APP_ID'" >&2
  exit 1
fi

if [ "$EXTRACTED_VERSION_CODE" != "1" ]; then
  echo "FAIL: Expected '1', got '$EXTRACTED_VERSION_CODE'" >&2
  exit 1
fi

if [ "$EXTRACTED_VERSION_NAME" != "0.1.0" ]; then
  echo "FAIL: Expected '0.1.0', got '$EXTRACTED_VERSION_NAME'" >&2
  exit 1
fi

if [ "$EXTRACTED_APP_ID" = "14" ]; then
  echo "FAIL: Regression detected! Extracted '14' as application ID." >&2
  exit 1
fi

echo "SUCCESS: verify-apk parsing regression test passed successfully."
