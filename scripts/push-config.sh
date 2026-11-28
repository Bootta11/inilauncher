#!/usr/bin/env bash
# Loads a launcher configuration into the DEBUG build on an emulator or phone (docs/configuration.md):
# checks the file is JSON, writes it into the app's private folder with `adb run-as` (which only
# works on debuggable builds), then restarts the app. The app imports it with the same checks as
# Extras → Import configuration, logs what it skipped, and deletes the file.
#
#   scripts/push-config.sh configs/emulator-demo.json
#   scripts/push-config.sh my-config.json --serial emulator-5554
#
# See what was imported or skipped: adb logcat -s LauncherConfig
set -euo pipefail

PACKAGE="app.inilauncher.debug"
ACTIVITY="app.olauncher.MainActivity"

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[[ $# -ge 1 ]] || usage
file="$1"; shift
if [[ "${1:-}" == "--serial" ]]; then export ANDROID_SERIAL="${2:?--serial needs a device}"; shift 2; fi
[[ -f "$file" ]] || { echo "No such file: $file" >&2; exit 1; }
command -v adb >/dev/null || { echo "Needs adb." >&2; exit 1; }
[[ $(wc -c < "$file") -le 262144 ]] || { echo "Larger than 256 KB: the app would refuse it." >&2; exit 1; }
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d.get("format")=="inilauncher-config", "not an Inilauncher configuration"' "$file" \
  || { echo "$file isn't a valid configuration file." >&2; exit 1; }

adb shell pm path "$PACKAGE" >/dev/null 2>&1 || { echo "$PACKAGE (the debug build) isn't installed." >&2; exit 1; }
# run-as refuses non-debuggable apps, so this can never reach a release build
adb shell run-as "$PACKAGE" sh -c "'mkdir -p files && cat > files/import-config.json'" < "$file"
adb shell am force-stop "$PACKAGE"
adb shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null
echo "Pushed $file to $PACKAGE. Check the result with: adb logcat -s LauncherConfig"
