# Architecture

Inilauncher is a fork of Olauncher (Kotlin, XML views, fragments + Navigation, view binding). The fork is laid out so that upstream fixes merge with few conflicts.

## Layout

- `app/src/main/java/app/olauncher/…`: upstream code. The Kotlin package stays `app.olauncher`. Only the `applicationId` changed (`app.inilauncher`).
- `app/src/main/java/app/olauncher/extras/`: everything Inilauncher adds, one sub-package per feature.
  - `ExtrasPrefs`: the extras' settings, stored in their own SharedPreferences file (`app.inilauncher.extras`), so upstream's `Prefs` stays unchanged.
  - `ExtrasSettingsFragment`: the Extras screen, with Home screen, Search & app list, Look, and Privacy & security sections.
  - `UpstreamSettingsHooks`: Inilauncher's changes to upstream's settings screen (relabelled rows, hidden rows, the About dialog).
  - `ExtrasActivityHooks`: activity-level observers (open folder, refresh after folder changes, relock hidden apps when the screen turns off).
  - `HomeExtras`: fills `layout/extras_home.xml`, the block under the clock (note, later weather/events/media).
  - `look/`: fonts (theme overlays on upstream's `mainFontFamily`), background blur, forced e-ink.
  - `folders/`: folders appear in upstream's app list as `AppModel.App` entries with the package `inilauncher.folder` and the folder id as activity class, so home slots and swipe gestures can hold them. Launching one opens the folder dialog.
  - `privacy/HiddenAppsLock`: BiometricPrompt guard on the hidden-apps list. `privacy/HiddenApps`: upstream's hidden set for the extras' own lists (pickers leave hidden apps out while locked; the sub list never auto-adds them).
  - `net/Http`: the extras' https GETs (weather, suggestions, upstream's wallpaper hooks), time- and size-limited.
  - `weather/`: Open-Meteo client (`HttpURLConnection` + `org.json`), pure `WeatherParser`, hourly `WeatherWorker`, city search dialog.
  - `calendar/UpcomingEvents`: `CalendarContract.Instances` query for the next 24 hours.
  - `notifications/`: `InilauncherNotificationListener` (a `StateFlow` of active notifications), dots, the notifications dialog with `RemoteInput` replies, and `AppNotifier` for the app's own local notifications.
  - `media/MediaControls`: active media sessions through the notification listener.
  - `drag/`: hold-and-drag reordering for the sub list, folders and widgets. `DragSession` (lift, others make room by translation, settle, then the drop is laid out and saved; targets are places in the order, or any numbered targets via `arrange`, e.g. the sub list's grid cells), `HoldDrag` (touch listener for app rows and icons), pure `DragSlots` (wrapping rows, columns, nearest slot) and `EdgeScroller`. Home apps have their own `HomeReorder`.
  - `widgets/`: `WidgetPanelFragment` (`AppWidgetHost`, bind + configure flow), `WidgetStore`, `WidgetGrid` (the 4-column grid; keeps its cell views) with pure `WidgetGridLayout`, `WidgetTouchFrame` (one cell: scrolling lists, hold) and `WidgetEditOverlay` (the resize frame, handles and bar). The panel is opened through a `Widgets ›` entry in the app list (package `inilauncher.widgets`), so it works on swipe gestures and home slots too.
  - `search/`: `DrawerExtras` (suggested row, extra results, A–Z scroller in the app list), `SearchProviders` (contacts, shortcuts, web suggestions), pure `ExpressionEvaluator` (exact `BigDecimal` maths; `couldBeCalculation` for the auto-launch check), `SuggestionRanking`, `SuggestedBlocklist` (Don't suggest and pinned apps, the row's hold menu), `PinBadge` (the pinned app's badge) and `LetterIndex` (letter → row table, built once per list, skipping folders and Widgets), `AlphabetScrollerView` (wave letters and bubble drawn from pre-rendered sprites, ⌨ slot, double tap via the pure `DoubleTap`, pull sideways to pick an app; `DrawerExtras` handles side/height, the keyboard and the pick: suggested row first (nearest the strip first), then the letter's rows from pure `LetterPick`; `DockHighlight` / `RowHighlight` are the gliding boxes on the picked app), `SlotSwipes`. Letter jumps replace every row at once, so `DrawerExtras` keeps 32 spare rows and pre-creates them while idle.
  - `sublist/`: the home sub list. `SubList` binds `layout/extras_home_sublist.xml` (button, open/closed kept in the views, closes on the home view's `ON_STOP`), `SubListStore` (picked apps as `FolderApp` JSON via `FolderJson.encodeApps`), pure `SubListEntries` (most-used fill), `SubListMenu` (dialogs), `SubListFlow` (wrapping rows aligned like the home apps, or with Free placement a grid of cells), pure `SubListGrid` (Free placement: columns, cells, swap; saved as `package|user=row,col`) and `SubListScroll` (height cap; ignores touches when there's nothing to scroll, so home swipes still work), `SubListActions` (the app list button). Most-used counts come from `search/LaunchHistory` (shared with the app list).
  - `picker/`: the full-screen app picker used for every app choice. `AppPickerScreen` (a full-screen `Dialog` with the window-only `ExtrasFullScreenPicker` style, so the app's theme and font carry over; search with `appLabelMatches`, `AlphabetScrollerView` set up like `WidgetPicker`, `AppIcons` icons, `pickOne` / `pickMany`), pure `AppPickerRows` (sections, search, A–Z labels) and `InstalledApps` (every launchable app in every profile).
  - `notifications/NotificationDots`: the one place that decides the dot or count after a name (home, app list, sub list), including folder totals and the dot options.
  - `iconpack/`: `AppIcons` (system, monochrome, grayscale, letter-dot or icon-pack icons, LRU cache), `LetterIconDrawable` and `AppFilterParser`. List icons load on a background thread (on-screen rows first, then the whole list as prefetch) and are cached as bitmaps at their display size, so scrolling and letter jumps never wait on the package manager. The setting lives in upstream's Settings → Appearance (`UpstreamSettingsHooks.bindIcons`).
  - `Choices.kt`: `View.showChoices`, the settings popup that scrolls the page up first so it never opens behind the navigation bar.
  - `InternalEntries`: tells whether a folder or the Widgets panel placed on a home slot still exists (they use made-up `inilauncher.*` packages, so upstream's installed-app check would clear the slot).
  - `ExtrasState`: `awaitingSystemScreen` stops upstream's "back to home on stop" while a widget permission/setup screen is open. `ForegroundStart.options()` lends the on-screen launcher's right to start activities to other apps' PendingIntents and IntentSenders (notification taps, widget settings), which Android 14+ requires.
- `app/src/main/res/values/*_extras.xml`: the extras' strings, styles and sizes (English only, other languages fall back to English).
- `app/src/brand/res/`: branding (app name, English strings that say "Inilauncher" instead of "Olauncher", launcher icon) and `xml/accessibility_service_config.xml` (upstream's, with this app's ids, so double tap to lock hears Inilauncher's taps). It's added to the debug and release source sets, so it overrides upstream resources without editing them.

## Flavors and updates

- `direct` (Zealot APK, self-updating) and `play` (Google Play), same app id. `app/src/direct/` holds the installer (`ApkInstaller`, `ZealotClient`, the install-status and "updated" receivers, `ApkUpdates`); `app/src/play/` has an `ApkUpdates` stub, so the Play build has no install permissions or installer code.
- `AppRelease.kt` (main) holds the Zealot URL and channel key and the version line shown at the bottom of the Extras screen.
- `extras/update/UpdateBanner` is the "Version X is available" card, added over every screen by `ExtrasActivityHooks`.

## Hooks into upstream code

Upstream files only get short hooks, each marked with an `// Inilauncher:` comment:

- `MainActivity.checkForMessages`: stops after the wallpaper tip (no review/rate/share prompts).
- `SettingsFragment`: no Pro upsell. "More features" opens the Extras screen, About shows the in-app About, and `UpstreamSettingsHooks.apply` runs after setup.
- `nav_graph.xml`: the Extras destination and the action to it.
- `MainActivity.onCreate`: font overlay, background blur, `ExtrasActivityHooks.observe`.
- `MainViewModel`: `launchApp` opens folders, `getAppList` adds folders to the list.
- `AppDrawerAdapter` + `adapter_app_drawer.xml`: the **Folder** button, and long-press on a folder opens its menu.
- `HomeFragment.populateDateTime` and both `fragment_home.xml` layouts: the extras block under the clock.
- Both `fragment_home.xml` layouts: the sub list as the last child of `homeAppsLayout` (bound from `HomeExtras.decorateHomeApps`, so `HomeFragment` itself has no extra hook).
- `AppDrawerAdapter` + `adapter_app_drawer.xml`: the **Sub list** button after **Folder**.
- `helper/Extensions.kt` `isEinkDisplay()`: honours "Force e-ink mode".
- `HomeFragment.onResume` / `onViewCreated`: notification dots on home apps and the notification observer.
- `AppDrawerAdapter`: the notification dot (or count) after app names, from `NotificationDots.suffix`.
- `AppDrawerFragment` (`onViewCreated`, `onQueryTextChange`) and both `fragment_app_drawer.xml` layouts: the extras block above the list, and the list wrapped in a frame for the A–Z scroller.
- `AppDrawerAdapter` / `HomeExtras.decorateHomeApps`: icons before app names.
- `HomeFragment.setHomeAppText`: asks `InternalEntries` about folder/Widgets slots instead of the installed-app check.
- `HomeFragment.showAppList`: the home-slot picker leaves hidden apps out while "Lock hidden apps" is locked.
- `AppDrawerAdapter.autoLaunchAllowed` (checked in `autoLaunch` with the query the shown results belong to) and `AppDrawerFragment.onQueryTextSubmit`: with the calculator on, `DrawerExtras` holds auto-launch back while the search could still be a sum, and Enter on a sum copies its answer.
- `helper/Utils.kt` wallpaper download (`getBitmapFromURL`, `getTodaysWallpaper`): https only, time- and size-limited, decoded at screen size.
- `app/build.gradle` / `proguard-rules.pro` release: resource shrinking, debug logs stripped. Manifest: cleartext off.
- `fragment_settings.xml` (portrait + land): the **App icons** row in Appearance.
- `HomeFragment.getViewSwipeTouchListener`: per-app swipe actions before the usual swipe apps.
- `MainActivity.backToHomeScreen`: skipped while `ExtrasState.awaitingSystemScreen`. `onNewIntent` (Home pressed) clears that flag first, so Home always goes home.
- `AndroidManifest.xml`: the notification listener service and the extras' permissions.
- `app/build.gradle`: app id, version, brand source set, lint baseline.
