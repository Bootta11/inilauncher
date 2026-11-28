# App releases and updates


Until the app is on Google Play, testers install it from Zealot (`https://apps.bootta.dev`), and the app keeps itself up to
date. It asks Zealot for newer builds and shows an *Update available* banner; **Update** downloads the APK and
installs it.


There are two builds of the app (Gradle product flavors in `app/build.gradle`):

| | `direct`: the APK on Zealot | `play`: the Google Play bundle |
|---|---|---|
| Built by | `./gradlew assembleDirectRelease` | `./gradlew bundlePlayRelease` |
| Output | `app/build/outputs/apk/direct/release/app-direct-release.apk` | `app/build/outputs/bundle/playRelease/app-play-release.aab` |
| Installs new APKs | yes (`src/direct`: ApkInstaller, install permissions) | no: Play updates the app; Play rejects self-installing apps |
| Signed with | the app signing key | the upload key |

Both have the same app id and are built from the same tag with the same version, so a phone that installed the
Zealot APK takes the Play version later without reinstalling (Play then signs with the same app signing key,
uploaded once to *Play App Signing*).

Debug builds: `./gradlew assembleDirectDebug` → `app/build/outputs/apk/direct/debug/app-direct-debug.apk`, signed with the debug key.

## Releasing

Push a tag `vX.Y.Z`. CI's `android` job:

1. takes the version from the tag: `versionName X.Y.Z`, `versionCode X*10000 + Y*100 + Z` (so `v0.1.2` → 102);
2. builds the signed direct APK, plus the Play bundle when the upload key secrets are set;
3. checks the APK's signature and uploads it to Zealot. The changelog is the version's [What's new](#whats-new) entry (intro + feature titles), or the tag's message when there is none (CI warns);
4. keeps the `.aab` as a workflow artifact, for Google Play.

```bash
git tag -a v0.1.2 -m "Faster search; fixed the share sheet"
git push origin v0.1.2
```

Every release needs a new tag; never move a tag that was released. Local builds keep the default version from the
build file.

## What's new

After an update, the app shows a *What's new* dialog once: an intro page, then one page per feature with a
screenshot, a title and a line or two. Fresh installs don't see it. **Settings → What's new** (footer, both builds)
opens it again. If several updates were skipped, it shows the notes of all of them, newest first, up to 3 versions.

The notes ship in the APK (`app/src/main/assets/whatsnew/`), so they work offline and in the Play build:

```
app/src/main/assets/whatsnew/
  whatsnew.json          # newest version first
  0.1.6/settings-search.webp        # dark theme
  0.1.6/settings-search-light.webp  # light theme
```

```json
[{ "version": "0.1.6",
   "intro": "One or two sentences on what changed.",
   "features": [{ "title": "Search settings", "text": "Type in Settings to jump to any option.",
                  "image": "settings-search.webp", "imageLight": "settings-search-light.webp" }] }]
```

For each release:

1. Add an entry at the top of `whatsnew.json` with the version you will tag. `image` (dark theme) and `imageLight`
   (light theme) are optional; the dialog shows the one that matches the launcher's theme, or `image` when there's no
   light one.
2. Take the screenshots on the screenshot emulator (below), never on a personal phone: once in the dark theme
   (`scripts/whatsnew-shot.sh 0.1.7 my-feature`) and once in the light theme (`… my-feature-light`). Add
   `x y width height` (screen pixels) to crop to the part that matters. Each is a WebP of at most 720 px wide
   (about 20–60 KB); the script prints the folder's total.
3. **Keep the APK from growing:** only the 3 newest versions keep screenshots (both themes). Delete the folder of the oldest
   version and remove its `image` fields (its text can stay). The unit test `WhatsNewTest` fails when more versions
   have screenshots, when a file isn't used, or when the folder goes over 800 KB.
4. Get the pictures approved before committing: put every page of the entry (its text and both screenshots) on a
   private review page that opens on a phone (e.g. a private Claude artifact), so they can be checked away from the
   computer. Commit and tag only after the approval.
5. Tag as usual. The Zealot changelog is built from the same entry.

### The screenshot emulator

Screenshots come from an emulator with a demo setup, so no personal apps, notifications or accounts end up in the
app or the repo, and every release looks the same:

- AVD `inilauncher-shots`: Pixel 6 profile (1080 × 2400), Android 15 *Google APIs* image (no Play Store, which is
  much lighter), GPU mode `angle_indirect` (`host` gives black `screencap`s, software rendering is too slow). Create
  it with `avdmanager create avd -n inilauncher-shots -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_6`
  and set `hw.gpu.enabled=yes`, `hw.gpu.mode=angle_indirect`, `hw.ramSize=3072` in its `config.ini`.
- Install the debug build and make it the home app: `adb shell cmd role add-role-holder android.app.role.HOME app.inilauncher.debug 0`.
- Load the demo setup in one step: `scripts/push-config.sh configs/emulator-demo.json` (home apps Camera, Clock,
  Calendar, Contacts; sub list Files, Maps, Drive with names and free placement; letter dots; dark theme). See
  [Configuration files](configuration.md#debug-builds-and-the-emulator).
- Wallpapers from the daily wallpaper list, the same for every release so all screenshots match: dark theme the
  starry sky with a tree (pair 8, dark), light theme the snowy forest (pair 27, light). Don't change them per release. Push the photo to `/sdcard/Pictures`, then set it with
  `am start -n com.google.android.apps.photos/.setwallpaper.SetWallpaperActivity -a android.intent.action.ATTACH_DATA -d content://media/external/images/media/<id> -t image/jpeg`.
- Run the script with `ANDROID_SERIAL=emulator-5554` when a phone is connected too.

The app compares the installed version code with the one it last showed (`whats_new_seen_version_code`), so local
builds with the default version code see nothing. To try it, build with `-PversionCode=… -PversionName=…` (see
`WhatsNew.kt`).


## APK updates

- `app/src/direct/java/app/olauncher/ApkUpdates.kt` asks Zealot on start and on resume (at most every 6 hours):
  `GET /api/apps/latest?channel_key=…&bundle_id=app.inilauncher&release_version=…&build_version=…`, over native HTTP.
  The Zealot URL and channel key are in `app/src/main/java/app/olauncher/AppRelease.kt`; they aren't secret. The channel's downloads must
  be public.
- The banner shows the newer version with its changelog. **Later** hides it until a newer one.
- **Settings → Check for updates** (footer, direct build only) asks Zealot right away, ignoring the 6 hours. A newer version shows the banner again, even after **Later**; otherwise a toast says it's the latest version or that the check failed.
- **Update** checks *Install unknown apps* for the app (and opens that setting if needed), then the native
  `ApkInstaller` (`src/direct`) downloads the APK and installs it with Android's `PackageInstaller`. Android checks that
  it's signed with the same key as the installed app.
- Safety checks before Android sees the file: the APK must come from the same host as `ZEALOT_URL` (other releases
  are skipped, with a warning in logcat), be at most 150 MB and arrive whole, and be this app at a higher version,
  signed by a key the installed app has. One download runs at a time, into `noBackupFilesDir` (the launcher clears
  its cache every few hours), and the banner shows its progress even after the screen is recreated.
- **The first update** after installing from a browser or adb shows Android's confirm screen. After that the app
  is its own installer, so on Android 12+ later updates install without one (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`).
- Android doesn't let an app reopen itself after updating, so a notification *Inilauncher updated to X — tap to
  open* appears.

## The signing keys

| Key | Signs | Where it lives |
|---|---|---|
| App signing key | the direct APK; later uploaded to Play App Signing | `~/keys/inilauncher-release.jks`; GitHub secrets |
| Upload key | the Play bundle (only once on Play) | `~/keys/inilauncher-upload.jks`; GitHub secrets |

Back them all up (password manager + an offline copy). **Losing the app signing key** means installed apps can never
be updated again (users would have to uninstall), and GitHub can't give a secret back.

### One-time setup

Inilauncher is a fork, and the clone's only remote is `upstream` (Olauncher). First create your own GitHub repo, then
`git remote add origin <your-repo-url>` and push. The setup script uses `origin` and refuses to run against upstream.

**The quick way:** run

```bash
scripts/release-setup.sh             # add --dry-run to see what it would do first
```

It creates the app signing key in `~/keys` (asks for a password), writes the git-ignored
`app-signing.properties`, creates the *Inilauncher* app with an Android channel on Zealot through its
API (asks for your Zealot API token: avatar → *Settings → API token*), writes the channel key into
app/src/main/java/app/olauncher/AppRelease.kt, creates the GitHub environment `android-release` with the tag rule `v*`, and sets its secrets
and variable. It asks before every change on Zealot or GitHub, skips whatever is already done, and can be re-run.
`--play` adds the Google Play upload key.
Afterwards: back up `~/keys`, commit app/src/main/java/app/olauncher/AppRelease.kt, and do the first release (step 7).

The steps below are what the script does, for doing it by hand or fixing one part.

Do these in order, once. Keep every key **outside the repo** (here `~/keys`; the repo ignores `*.pem` and `*.jks`)
and never paste a private key, password or token into a chat or an issue.

#### 1. Create the keys

```bash
mkdir -p ~/keys && chmod 700 ~/keys && cd ~/keys

# App signing key: signs every APK. Asks for a password (twice) and a name/organization (any values).
keytool -genkeypair -v -keystore inilauncher-release.jks \
  -alias inilauncher -keyalg RSA -keysize 4096 -validity 10000
```

Then `chmod 600 ~/keys/*` and back the files up. For local release builds, copy
`app-signing.properties.example` to `app-signing.properties` (git-ignored) and fill it in.

#### 2. Zealot (`https://apps.bootta.dev`)

1. Create the app **Inilauncher** with an **Android** channel, and make the channel's downloads public.
2. Open the channel and copy its **channel key**.
3. In your user **Settings → API token**, copy the **token**.

#### 3. Put the public values into the app

In `app/src/main/java/app/olauncher/AppRelease.kt`, set the Zealot channel key to the channel key from step 2. It isn't secret.

#### 4. The GitHub environment

*Settings → Environments → New environment* `android-release`:

- **Deployment branches and tags:** *Selected branches and tags* → *Add deployment branch or tag rule* → type
  **Tag**, pattern `v*`. It shows "applies to 0 tags" until the first tag exists.
- Required reviewers are optional: with them, every release waits for your approval.

#### 5. Environment secrets and variable

Run in the repo folder, so `gh` picks this repository. Each `gh secret set` asks for the value and hides it.

```bash
base64 -i ~/keys/inilauncher-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --env android-release
gh secret set ANDROID_KEYSTORE_PASSWORD --env android-release   # keystore password from step 1
gh secret set ANDROID_KEY_ALIAS --env android-release           # inilauncher
gh secret set ANDROID_KEY_PASSWORD --env android-release        # key password (same as the keystore one if you pressed Enter)
gh secret set ZEALOT_API_TOKEN --env android-release            # token from step 2
gh variable set ZEALOT_CHANNEL_KEY --env android-release        # channel key from step 2 (a variable, not a secret)
```

Once the app goes to Google Play, add the upload key too (until then CI skips the Play bundle):

```bash
base64 -i ~/keys/inilauncher-upload.jks | gh secret set ANDROID_UPLOAD_KEYSTORE_BASE64 --env android-release
gh secret set ANDROID_UPLOAD_KEYSTORE_PASSWORD --env android-release
gh secret set ANDROID_UPLOAD_KEY_ALIAS --env android-release
gh secret set ANDROID_UPLOAD_KEY_PASSWORD --env android-release
```


#### 6. Check

```bash
gh secret list --env android-release    # 5 secrets (9 with the upload key)
gh variable list --env android-release  # ZEALOT_CHANNEL_KEY
```

#### 7. First release

Commit and push the first tag (e.g. `v0.1.0`). Install that APK from Zealot on the phones; uninstall a debug build
first, since it's signed with a different key (this also clears the app's data).

## Troubleshooting

- **Gradle: `Could not GET 'https://dl.google.com/…'. Received status code 502`**: Google's Maven had a short
  outage. Re-run the failed job (`gh run rerun <id> --failed`); the tag stays as it is.
- **"App not installed" / signature conflict on the phone**: a build signed with another key (usually a debug
  build) is installed. Uninstall it first.
