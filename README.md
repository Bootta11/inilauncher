# Inilauncher

A minimal, text-first Android launcher with optional extras. Out of the box it behaves like [Olauncher](https://github.com/tanujnotes/Olauncher). Weather, widgets, folders, notifications on apps, smarter search, a day & night look by the sun and more are on the **Extras** screen (Settings → Extras…), and each one is off until you turn it on.

No ads, no analytics. Features that use the internet are opt-in.

## Build and install

Requirements: JDK 17 and the Android SDK (`local.properties` with `sdk.dir=…`).

```sh
./gradlew assembleDirectDebug testDirectDebugUnitTest lintDirectDebug
adb install -r app/build/outputs/apk/direct/debug/app-direct-debug.apk
```

Then pick Inilauncher as your home app (Settings → Set as default launcher).

Two flavors: `direct` (released as an APK on Zealot and updates itself) and `play` (for Google Play, no self-update). Releases are built by CI from `vX.Y.Z` tags; see [App releases & updates](docs/app-updates.md).

## Scripts

- `scripts/release-setup.sh`: one-time setup for releases (signing key, Zealot app and channel, GitHub environment and secrets). Try `--dry-run` first.
- `scripts/push-config.sh`: loads a configuration file into the debug build (see [Configuration files](docs/configuration.md)).
- `scripts/whatsnew-shot.sh`: takes a phone screenshot for the What's new dialog (see [What's new](docs/app-updates.md#whats-new)).
- `npm run wallpapers`: a review page for the daily wallpaper list; `scripts/publish-wallpapers.sh` publishes it (see [Daily wallpapers](docs/wallpapers.md)).
- `scripts/brand-translations.py`: refreshes the translated "Inilauncher" strings after an upstream merge (see [Syncing with Olauncher](docs/upstream-sync.md)).

## Docs

- [Features](docs/features.md): what each extra does
- [Permissions & privacy](docs/permissions.md): what each feature needs and whether it uses the network
- [Architecture](docs/architecture.md): how the fork is laid out
- [Syncing with Olauncher](docs/upstream-sync.md): pulling in upstream fixes
- [App releases & updates](docs/app-updates.md)
- [Configuration files](docs/configuration.md): export, import and share the launcher's setup; loading one into the debug build
- [Daily wallpapers](docs/wallpapers.md): the photo list, reviewing and publishing it, and your own folders

`npm install && npm run docs` serves these docs at `http://inilauncher-docs.localhost:4410`.

## License and credit

Inilauncher is a modified version of [Olauncher](https://github.com/tanujnotes/Olauncher) by Tanuj ([@tanujnotes](https://github.com/tanujnotes)), changed in 2026 to add the optional extras. Like Olauncher it is free software under the [GNU GPL v3](LICENSE). It comes with no warranty.
