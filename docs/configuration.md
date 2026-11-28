# Configuration files

Inilauncher can save its setup to a file and load it again, on the same phone or another one: **Settings → Extras… →
Backup & sharing**. A file is plain JSON. It's checked strictly before anything changes, so files can be shared
safely.

## Export

**Export configuration** asks for an optional name and description and which parts to include:

| Part | What it holds |
|---|---|
| **Look** | theme, font, text size, bold, app icons (style or icon pack), alignment, clock and date, status bar, blur, e-ink, gesture animations, sub list button and style, notification dot style |
| **Settings** | how it behaves: search options, A–Z scroller, suggested apps, sort by usage, sub list on/free placement/most-used, notifications, music controls, events, widgets panel, note on/off, weather units, swipe apps on/off, keyboard |
| **Home layout** (off by default) | the home apps and how many, swipe left/right apps, per-app swipes, the sub list's apps and grid, folders |

**Save** writes the file with Android's save dialog; **Share** sends it through the share sheet. A file with only
the Look part is a *style*: it holds none of your apps.

**Never included:** hidden apps, the hidden-apps lock, the note's text, the weather place and coordinates, the day &
night location and photos, daily-wallpaper folders, widgets, notification state, usage history and other
bookkeeping. They're listed in `ConfigSchema.NEVER_EXPORTED`, and a unit test fails when a new setting is in
neither that list nor the schema.

## Import

**Import configuration** opens a file, then shows a preview before anything is written: the file's name and
description (as plain text), a tick box per part it contains (Home layout is off by default because it replaces your
home apps, sub list and folders), every change (*Text size: 1.0 → 1.2*), and everything skipped with the reason.
Settings that send something off the phone (web suggestions) are marked. **Import** applies it, and the launcher
restarts with the new look. **Undo last import** puts back what the last import replaced.

## Format

```json
{
  "format": "inilauncher-config",
  "version": 1,
  "app": "0.1.6",
  "meta": { "name": "Night minimal", "description": "Dark, letter dots, sub list grid" },
  "look": { "theme": "dark", "font": "condensed", "textSize": 1.0, "appIcons": "letter" },
  "settings": { "sublist.enabled": true, "search.calculator": true },
  "layout": {
    "homeAppsCount": 4,
    "homeApps": [{ "package": "com.android.camera2" }, { "folder": "Travel" }, { "widgets": true }, null],
    "swipeLeft": { "package": "com.google.android.deskclock" },
    "slotSwipes": [{ "slot": 1, "side": "left", "package": "com.google.android.apps.maps" }],
    "sublist": { "apps": [{ "package": "com.google.android.apps.maps" }], "cells": [{ "package": "com.google.android.apps.maps", "row": 0, "col": 0 }] },
    "folders": [{ "name": "Travel", "apps": [{ "package": "com.google.android.apps.maps" }] }]
  }
}
```

- Every part and every key is optional; what's missing is left as it is on the phone.
- Keys are stable public names, not the app's internal preference keys. The full list with every allowed value and
  limit is [`docs/config.schema.json`](config.schema.json) (JSON Schema 2020-12), generated from the app's code.
- An app is `{"package", "activity"?}`. Without `activity` the app's main launcher entry is used.
- Exports are canonical (sorted keys, all values), so the same setup gives the same file.
- `version` changes only for breaking changes; older files are upgraded on import (`ConfigMigrations`). A file from a
  newer app version is refused.

## Security

A configuration file is data, never code. The rules are in `extras/config/` and each one is unit tested
(`ConfigTest`):

- **Allowlist schema.** Only declared keys are read, through a small zod-style schema (`Schema.kt`): booleans,
  numbers with ranges, fixed choices, text with a length limit and a pattern, lists with a size limit. Anything
  unknown or invalid is skipped and listed in the preview; nothing else in the file is ever used.
- **Size limits before parsing:** at most 256 KB, 8 levels of nesting and 5,000 values.
- **Plain text only:** names and descriptions can't contain control characters or invisible direction marks, and
  are shown as text, never as links.
- **Apps are only selected, never launched by the file.** A package (and activity) must match an app you can open
  from the launcher on this phone, in your own profile. Anything else, such as a hidden screen of another app, is
  skipped. Labels come from the phone, not the file. No URLs, intents, file paths or shortcut ids are accepted.
- **Icon packs** must already be installed.
- **No permissions by file.** A setting that needs a permission (contacts, calendar, notification access, usage
  access) is only turned on if you've already given it; otherwise it's skipped. Turning things off always works.
- **Security settings can't be changed.** The hidden-apps lock and hidden apps aren't in the schema. When the lock is
  on, importing asks for the same unlock first.
- **You confirm every import** and can undo it. Everything is checked before the first write.

## Debug builds and the emulator

`scripts/push-config.sh` loads a file into the **debug** build in one step:

```bash
scripts/push-config.sh configs/emulator-demo.json          # add --serial emulator-5554 when a phone is connected too
adb logcat -s LauncherConfig                              # what was imported or skipped
```

It writes the file into the app's private folder with `adb run-as`, which Android only allows for debuggable builds,
and restarts the app. On start, the debug app imports `files/import-config.json` with the same checks (without the
preview) and deletes it. Release builds never look for it, and `run-as` refuses them.
[`configs/emulator-demo.json`](../configs/emulator-demo.json) is the screenshot emulator's setup
([App releases & updates → The screenshot emulator](app-updates.md#the-screenshot-emulator)).

## For a future style gallery

The format is meant to be shared on a page where people list their styles. A server accepting uploads should:
validate with `docs/config.schema.json` (same limits as the app), accept only `look` and `settings` (never `layout`,
which holds someone's app list), show `meta` as plain text, and de-duplicate by the canonical JSON.
