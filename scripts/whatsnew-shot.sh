#!/usr/bin/env bash
# Takes a screenshot from the connected phone for the What's new dialog (docs/app-updates.md → What's new):
# screencap over adb → optional crop → at most 720 px wide → WebP in app/src/main/assets/whatsnew/<version>/.
#
#   scripts/whatsnew-shot.sh 0.1.6 settings-search                 # whole screen
#   scripts/whatsnew-shot.sh 0.1.6 settings-search 0 300 1080 1400 # crop: x y width height, in screen pixels
#   scripts/whatsnew-shot.sh 0.1.6 settings-search-light            # the light-theme picture
#
# Take them on the screenshot emulator, not a personal phone (docs/app-updates.md → The screenshot emulator).
#
# Needs adb (one device connected, or ANDROID_SERIAL set) and cwebp (brew install webp).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets/whatsnew"
MAX_WIDTH=720
BUDGET_KB=800

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[[ $# -eq 2 || $# -eq 6 ]] || usage
version="$1"; name="${2%.webp}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must be X.Y.Z, got $version" >&2; exit 1; }
[[ "$name" =~ ^[a-z0-9-]+$ ]] || { echo "Name: lowercase letters, digits and dashes only" >&2; exit 1; }
command -v adb >/dev/null || { echo "Needs adb." >&2; exit 1; }
command -v cwebp >/dev/null || { echo "Needs cwebp (brew install webp)." >&2; exit 1; }

mkdir -p "$ROOT/.run" "$ASSETS/$version"
png="$ROOT/.run/whatsnew-shot.png"
out="$ASSETS/$version/$name.webp"
trap 'rm -f "$png"' EXIT

adb exec-out screencap -p > "$png"
read -r w h < <(python3 -c 'import struct,sys; d=open(sys.argv[1],"rb").read(24); print(*struct.unpack(">II", d[16:24]))' "$png")

args=()
if [[ $# -eq 6 ]]; then
  args+=(-crop "$3" "$4" "$5" "$6")
  w="$5"
fi
(( w > MAX_WIDTH )) && args+=(-resize "$MAX_WIDTH" 0)
cwebp -quiet -q 80 -m 6 "${args[@]}" "$png" -o "$out"

echo "Saved $out ($(( $(wc -c < "$out") / 1024 )) KB)"
total=$(du -sk "$ASSETS" | cut -f1)
echo "whatsnew assets: ${total} KB of ${BUDGET_KB} KB"
(( total <= BUDGET_KB )) || echo "Over budget: drop the oldest version's screenshots (only the 3 newest keep them)." >&2
echo "Add it to whatsnew.json as \"image\": \"$name.webp\" under version $version."
