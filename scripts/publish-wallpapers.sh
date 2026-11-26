#!/usr/bin/env bash
# Publishes wallpapers/daily_wallpapers.json to the gist the app reads (docs/wallpapers.md).
# Shows what changes and asks before writing. Needs the GitHub CLI, signed in as the gist's owner.
#
#   scripts/publish-wallpapers.sh            # diff, confirm, publish
#   scripts/publish-wallpapers.sh --dry-run  # diff only
set -euo pipefail

GIST_ID="00e920ad8a4dfd86d0dd165a9b4b924d"
FILE="daily_wallpapers.json"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL="$ROOT/wallpapers/$FILE"
DRY_RUN=false
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=true

command -v gh >/dev/null || { echo "Needs the GitHub CLI (gh)." >&2; exit 1; }
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["pairs"], "no pairs"' "$LOCAL" \
  || { echo "$LOCAL is not a valid list." >&2; exit 1; }

REMOTE="$(mktemp)"
trap 'rm -f "$REMOTE"' EXIT
gh gist view "$GIST_ID" --raw --filename "$FILE" > "$REMOTE"

if diff -q "$REMOTE" "$LOCAL" >/dev/null; then
  echo "The gist is already up to date."
  exit 0
fi
diff -u --label "gist/$FILE" --label "wallpapers/$FILE" "$REMOTE" "$LOCAL" || true
$DRY_RUN && { echo; echo "Dry run: nothing published."; exit 0; }

read -r -p "Publish this to the gist (installed apps pick it up within a day)? [y/N] " answer
[[ "$answer" == [yY]* ]] || { echo "Nothing published."; exit 0; }
gh gist edit "$GIST_ID" --filename "$FILE" "$LOCAL"
echo "Published: https://gist.github.com/$GIST_ID"
