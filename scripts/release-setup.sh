#!/usr/bin/env bash
# One-time setup for Zealot releases (docs/app-updates.md). Safe to re-run: every step skips what's already done.
#   scripts/release-setup.sh             app signing key, Zealot app + channel, GitHub environment + secrets
#   scripts/release-setup.sh --play      also the Google Play upload key (only once the app goes to Play)
#   scripts/release-setup.sh --dry-run   show what it would write (keys, Zealot, GitHub) without doing it
# With live UI updates (LIVE_UPDATES=1) it also creates the bundle signing key and its secret.
# Asks for a key password and your Zealot API token, and asks before every change on Zealot or GitHub.
# Template from the zealot-android-updates skill (assets/shared/scripts). Overrides: KEYS_DIR (default ~/keys), ZEALOT_URL (default https://apps.bootta.dev), ZEALOT_TOKEN (skips the prompt).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT"
APP_ID=app.inilauncher
APP_NAME="Inilauncher"
SLUG=inilauncher
ENV_NAME=android-release
KEYS_DIR="${KEYS_DIR:-$HOME/keys}"
ZEALOT_URL="${ZEALOT_URL:-https://apps.bootta.dev}"
# The release config file holding ZEALOT_CHANNEL_KEY (Kotlin/TS) or zealotChannelKey (Dart), and BUNDLE_PUBLIC_KEY.
RELEASE_CONFIG="$ROOT/app/src/main/java/app/olauncher/AppRelease.kt"
LIVE_UPDATES=0

DRY=0
PLAY=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY=1 ;;
    --play) PLAY=1 ;;
    -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }
confirm() {
  [[ $DRY == 1 ]] && { echo "  [dry-run] would: $1"; return 1; }
  local answer
  read -r -p "  $1? [Y/n] " answer
  [[ -z "$answer" || "$answer" =~ ^[Yy] ]]
}
ask_secret() { # ask_secret <prompt> <var>
  local value
  read -r -s -p "  $1: " value; echo
  printf -v "$2" '%s' "$value"
}

# ---------------------------------------------------------------------------------------------------------------
bold "1. Tools"
tools=(keytool gh curl jq base64); [[ $LIVE_UPDATES == 1 ]] && tools+=(openssl)
for tool in "${tools[@]}"; do
  command -v "$tool" >/dev/null || die "$tool is missing (keytool comes with a JDK; brew install gh jq)"
done
gh auth status >/dev/null 2>&1 || die "gh isn't logged in: run 'gh auth login'"
# Inilauncher is a fork: use the `origin` remote (your repo), never `upstream` (Olauncher), which gh would pick
# on its own while origin is missing.
ORIGIN_URL="$(git -C "$ROOT" remote get-url origin 2>/dev/null)" ||
  die "No 'origin' remote. Create your GitHub repo and add it first: git remote add origin <your-repo-url>"
REPO="$(cd "$ROOT" && gh repo view "$ORIGIN_URL" --json nameWithOwner -q .nameWithOwner)" || die "Can't find the GitHub repo $ORIGIN_URL"
[[ "$REPO" == tanujnotes/* ]] && die "origin points at upstream Olauncher ($REPO); point it at your own repo"
ok "GitHub repo $REPO, Zealot $ZEALOT_URL, keys in $KEYS_DIR"

# ---------------------------------------------------------------------------------------------------------------
# key_setup <jks> <alias> <properties file> → sets KEY_PASSWORD. Creates the key if missing, writes the git-ignored
# properties file for local release builds.
key_setup() {
  local jks="$1" alias="$2" props="$3"
  KEY_PASSWORD=""
  if [[ -f "$props" ]]; then
    KEY_PASSWORD="$(sed -n 's/^storePassword=//p' "$props")"
    KEY_PASSWORD="${KEY_PASSWORD//\\\\/\\}"
  fi
  if [[ -f "$jks" ]]; then
    ok "Key exists: $jks"
    if [[ -z "$KEY_PASSWORD" ]]; then ask_secret "Password of $(basename "$jks")" KEY_PASSWORD; fi
    if [[ $DRY == 0 ]]; then
      KEY_PASSWORD="$KEY_PASSWORD" keytool -list -keystore "$jks" -storepass:env KEY_PASSWORD -alias "$alias" >/dev/null 2>&1 \
        || die "Wrong password, or no key '$alias' in $jks"
    fi
  else
    echo "  Creating $jks (RSA 4096, valid ~27 years). Pick a strong password and save it in your password manager."
    if [[ $DRY == 0 ]]; then
      local again
      while :; do
        ask_secret "New key password (6+ characters)" KEY_PASSWORD
        ask_secret "Same password again" again
        [[ ${#KEY_PASSWORD} -ge 6 && "$KEY_PASSWORD" == "$again" ]] && break
        warn "Passwords differ or are shorter than 6 characters, try again"
      done
    fi
    if confirm "Create the key $jks"; then
      mkdir -p "$KEYS_DIR" && chmod 700 "$KEYS_DIR"
      KEY_PASSWORD="$KEY_PASSWORD" keytool -genkeypair -keystore "$jks" -storetype PKCS12 -alias "$alias" \
        -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=$APP_NAME" \
        -storepass:env KEY_PASSWORD -keypass:env KEY_PASSWORD >/dev/null
      chmod 600 "$jks"
      ok "Created $jks"
      warn "Back it up now (password manager + an offline copy): losing it means installed apps can never update."
    fi
  fi
  if [[ ! -f "$props" ]]; then
    if confirm "Write $(basename "$props") (git-ignored) for local release builds"; then
      ( umask 077
        printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
          "$jks" "${KEY_PASSWORD//\\/\\\\}" "$alias" "${KEY_PASSWORD//\\/\\\\}" > "$props" )
      ok "Wrote $props"
    fi
  else
    ok "$(basename "$props") exists"
  fi
}

bold "2. App signing key"
APP_JKS="$KEYS_DIR/$SLUG-release.jks"
key_setup "$APP_JKS" "$SLUG" "$ANDROID/app-signing.properties"
APP_PASSWORD="$KEY_PASSWORD"

if [[ $PLAY == 1 ]]; then
  bold "2b. Google Play upload key"
  UPLOAD_JKS="$KEYS_DIR/$SLUG-upload.jks"
  key_setup "$UPLOAD_JKS" "$SLUG-upload" "$ANDROID/keystore.properties"
  UPLOAD_PASSWORD="$KEY_PASSWORD"
fi

# ---------------------------------------------------------------------------------------------------------------
bold "3. Zealot app and channel"
TOKEN="${ZEALOT_TOKEN:-}"
[[ -n "$TOKEN" ]] || ask_secret "Zealot API token ($ZEALOT_URL → your avatar → Settings → API token)" TOKEN
[[ -n "$TOKEN" ]] || die "No Zealot token"

# zealot <method> <path> [field=value …]: the token goes through curl's stdin config, never the command line.
zealot() {
  local method="$1" path="$2"; shift 2
  local args=(-sS -m 30 -X "$method" -K -)
  [[ "$method" == GET ]] && args+=(-G)
  for field in "$@"; do args+=(--data-urlencode "$field"); done
  printf 'data-urlencode = "token=%s"\n' "$TOKEN" | curl "${args[@]}" "$ZEALOT_URL$path"
}

apps="$(zealot GET /api/apps)" || die "Can't reach $ZEALOT_URL"
echo "$apps" | jq -e 'type == "array"' >/dev/null 2>&1 || die "Zealot refused the token: $(echo "$apps" | jq -r '.error? // .' 2>/dev/null | head -c 200)"
CHANNEL_KEY="$(echo "$apps" | jq -r --arg name "$APP_NAME" --arg id "$APP_ID" '
  [.[] | select(.name == $name) | .schemes[]?.channels[]? | select((.device_type | ascii_downcase) == "android")]
  | (map(select(.bundle_id == $id)) + .) | first | .key // empty')"

if [[ -n "$CHANNEL_KEY" ]]; then
  ok "Found the $APP_NAME Android channel"
elif confirm "Create the app '$APP_NAME' with an Android channel on Zealot"; then
  app_id="$(echo "$apps" | jq -r --arg name "$APP_NAME" '[.[] | select(.name == $name)] | first | .id // empty')"
  if [[ -z "$app_id" ]]; then
    app_id="$(zealot POST /api/apps "name=$APP_NAME" | jq -r '.id // empty')"
    [[ -n "$app_id" ]] || die "Creating the app failed (is your Zealot user allowed to create apps?)"
  fi
  scheme_id="$(echo "$apps" | jq -r --arg id "$app_id" '[.[] | select((.id | tostring) == $id) | .schemes[]?] | first | .id // empty')"
  if [[ -z "$scheme_id" ]]; then
    scheme_id="$(zealot POST "/api/apps/$app_id/schemes" "name=Release" | jq -r '.id // empty')"
    [[ -n "$scheme_id" ]] || die "Creating the scheme failed"
  fi
  # No password: the channel's downloads are public, so the app can fetch the APK.
  CHANNEL_KEY="$(zealot POST "/api/schemes/$scheme_id/channels" "name=Android" "device_type=android" "bundle_id=$APP_ID" \
    | jq -r '.key // empty')"
  [[ -n "$CHANNEL_KEY" ]] || die "Creating the channel failed"
  ok "Created the app, scheme and Android channel"
fi
if [[ -z "$CHANNEL_KEY" && $DRY == 0 ]]; then
  read -r -p "  Paste the channel key from Zealot (the Android channel's page) instead: " CHANNEL_KEY
  [[ -n "$CHANNEL_KEY" ]] || die "No channel key"
fi
[[ -n "$CHANNEL_KEY" ]] && ok "Channel key: $CHANNEL_KEY (not secret)"

# ---------------------------------------------------------------------------------------------------------------
bold "4. Public values in the app"
KEY_RE="(ZEALOT_CHANNEL_KEY|zealotChannelKey)( *= *)[\"'][^\"']*[\"']"
current="$(grep -Eo "$KEY_RE" "$RELEASE_CONFIG" | head -1 | sed -E "s/.*[\"']([^\"']*)[\"']$/\\1/")"
if [[ -n "$CHANNEL_KEY" && "$current" == "$CHANNEL_KEY" ]]; then
  ok "$(basename "$RELEASE_CONFIG") already has the channel key"
elif [[ -n "$CHANNEL_KEY" ]] && confirm "Set the Zealot channel key in $(basename "$RELEASE_CONFIG")"; then
  sed -i.bak -E "s/$KEY_RE/\\1\\2\"$CHANNEL_KEY\"/" "$RELEASE_CONFIG" && rm "$RELEASE_CONFIG.bak"
  ok "Updated $(basename "$RELEASE_CONFIG") (commit it with the release)"
fi

# ---------------------------------------------------------------------------------------------------------------
if [[ $LIVE_UPDATES == 1 ]]; then
  bold "4b. Bundle signing key (live UI updates)"
  BUNDLE_PEM="$KEYS_DIR/$SLUG-bundle.pem"
  if [[ -f "$BUNDLE_PEM" ]]; then
    ok "Key exists: $BUNDLE_PEM"
  elif confirm "Create the bundle signing key $BUNDLE_PEM (ECDSA P-256)"; then
    mkdir -p "$KEYS_DIR" && chmod 700 "$KEYS_DIR"
    ( umask 077; openssl ecparam -name prime256v1 -genkey -noout -out "$BUNDLE_PEM" )
    ok "Created $BUNDLE_PEM"
    warn "Back it up too (password manager + an offline copy)."
  fi
  if [[ -f "$BUNDLE_PEM" ]]; then
    public="$(openssl pkey -in "$BUNDLE_PEM" -pubout | grep -v -- ----- | tr -d '\n')"
    if grep -q "BUNDLE_PUBLIC_KEY = \"$public\"" "$RELEASE_CONFIG" || grep -q "${public:0:40}" "$RELEASE_CONFIG"; then
      ok "$(basename "$RELEASE_CONFIG") already has the public key"
    elif grep -q 'BUNDLE_PUBLIC_KEY = ""' "$RELEASE_CONFIG"; then
      if confirm "Set BUNDLE_PUBLIC_KEY in $(basename "$RELEASE_CONFIG")"; then
        sed -i.bak "s#BUNDLE_PUBLIC_KEY = \"\"#BUNDLE_PUBLIC_KEY = \"$public\"#" "$RELEASE_CONFIG" && rm "$RELEASE_CONFIG.bak"
        ok "Set BUNDLE_PUBLIC_KEY"
      fi
    else
      warn "BUNDLE_PUBLIC_KEY holds another key: replace it by hand with $public"
    fi
  fi
fi

# ---------------------------------------------------------------------------------------------------------------
bold "5. GitHub environment '$ENV_NAME' (tags v* only)"
if confirm "Create/update the environment $ENV_NAME on $REPO"; then
  echo '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' \
    | gh api -X PUT "repos/$REPO/environments/$ENV_NAME" --input - >/dev/null \
    || die "Couldn't create the environment (private repos need GitHub Pro/Team for environments)"
  ok "Environment $ENV_NAME"
  if gh api "repos/$REPO/environments/$ENV_NAME/deployment-branch-policies" \
      --jq '.branch_policies[] | select(.type == "tag" and .name == "v*") | .id' | grep -q .; then
    ok "Tag rule v* exists"
  else
    gh api -X POST "repos/$REPO/environments/$ENV_NAME/deployment-branch-policies" -f name='v*' -f type=tag >/dev/null
    ok "Added tag rule v*"
  fi
fi

# ---------------------------------------------------------------------------------------------------------------
bold "6. GitHub secrets and variable"
secret() { # secret <name> <value>: through stdin, never on the command line
  printf '%s' "$2" | gh secret set "$1" --env "$ENV_NAME" --repo "$REPO" >/dev/null && ok "Secret $1"
}
if confirm "Set the release secrets on $REPO ($ENV_NAME)"; then
  [[ -f "$APP_JKS" ]] || die "$APP_JKS is missing"
  secret ANDROID_KEYSTORE_BASE64 "$(base64 < "$APP_JKS" | tr -d '\n')"
  secret ANDROID_KEYSTORE_PASSWORD "$APP_PASSWORD"
  secret ANDROID_KEY_ALIAS "$SLUG"
  secret ANDROID_KEY_PASSWORD "$APP_PASSWORD"
  secret ZEALOT_API_TOKEN "$TOKEN"
  if [[ $PLAY == 1 ]]; then
    [[ -f "$UPLOAD_JKS" ]] || die "$UPLOAD_JKS is missing"
    secret ANDROID_UPLOAD_KEYSTORE_BASE64 "$(base64 < "$UPLOAD_JKS" | tr -d '\n')"
    secret ANDROID_UPLOAD_KEYSTORE_PASSWORD "$UPLOAD_PASSWORD"
    secret ANDROID_UPLOAD_KEY_ALIAS "$SLUG-upload"
    secret ANDROID_UPLOAD_KEY_PASSWORD "$UPLOAD_PASSWORD"
  fi
  if [[ $LIVE_UPDATES == 1 && -f "${BUNDLE_PEM:-}" ]]; then
    # A repository secret (the server image is built on every push), from the file so it keeps its line breaks.
    gh secret set APP_BUNDLE_SIGNING_KEY --repo "$REPO" < "$BUNDLE_PEM" >/dev/null && ok "Repository secret APP_BUNDLE_SIGNING_KEY"
  fi
  if [[ -n "$CHANNEL_KEY" ]]; then
    gh variable set ZEALOT_CHANNEL_KEY --env "$ENV_NAME" --repo "$REPO" --body "$CHANNEL_KEY" >/dev/null && ok "Variable ZEALOT_CHANNEL_KEY"
  fi
fi

# ---------------------------------------------------------------------------------------------------------------
bold "7. Check"
if [[ $DRY == 0 ]]; then
  echo "  Secrets on $ENV_NAME:"; { gh secret list --env "$ENV_NAME" --repo "$REPO" || true; } | sed 's/^/    /'
  echo "  Variables:"; { gh variable list --env "$ENV_NAME" --repo "$REPO" || true; } | sed 's/^/    /'
fi
if [[ -f "$ANDROID/app-signing.properties" ]] && confirm "Build a signed release APK locally to test the key (a few minutes)"; then
  (cd "$ROOT" && ./gradlew -q assembleDirectRelease)
  apk="$ROOT/app/build/outputs/apk/direct/release/app-direct-release.apk"
  apksigner="$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/ | sort -V | tail -1)apksigner"
  "$apksigner" verify --print-certs "$apk" | head -3 | sed 's/^/    /'
  ok "Signed APK: $apk"
fi

bold "Done. Next:"
cat <<EOF
  1. Back up $KEYS_DIR (password manager + offline copy), if you haven't.
  2. Commit (incl. $(basename "$RELEASE_CONFIG") with the public values) and push.
  3. Release: git tag -a v0.1.0 -m "First release" && git push origin v0.1.0
  4. On each phone/TV: uninstall the debug build (different key, clears app data), install from Zealot.
EOF
