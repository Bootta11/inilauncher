# Permissions & privacy

The public, plain-language version for users and the Play listing is the [privacy policy](../site/privacy.md). Keep both in step when a feature changes what it needs or sends.

Inilauncher asks for a permission only when you turn on the feature that needs it. If you say no, the feature stays off and says why.

## Inherited from Olauncher

| Permission | Used for |
|---|---|
| Query all packages | Listing your apps |
| Usage access (optional) | Screen time on the home screen |
| Accessibility service (optional) | Double tap to lock (Android 9+) |
| Device admin (optional) | Double tap to lock (older Android) |
| Set wallpaper, Internet | The optional daily wallpaper: the list from gist.githubusercontent.com about daily, the photo from images.unsplash.com ([details](wallpapers.md)) |
| Expand status bar | Swipe down for notifications |

## Added by the extras

Filled in as each feature lands. Every row lists the permission, what it's used for, and whether the feature contacts the network.

| Feature | Permission | Network |
|---|---|---|
| Lock hidden apps | Biometric (`USE_BIOMETRIC`, added by the AndroidX library) | No |
| Weather, "My location" | Approximate location (`ACCESS_COARSE_LOCATION`) | Yes: rounded coordinates (~1 km) to api.open-meteo.com about hourly |
| Weather, "Search city" | None | Yes: the city name to geocoding-api.open-meteo.com once, then its coordinates hourly |
| Day & night by sun | Approximate location for "My location" (shared with weather), or none with a city or fixed times; changing the wallpaper (`SET_WALLPAPER`, already in Olauncher) | No: sunrise and sunset are worked out on the phone. Only the optional daily wallpaper downloads |
| Daily wallpaper from "My folders" | None: read access to the two folders you pick in the system folder picker | No |
| Upcoming events | Calendar (`READ_CALENDAR`) | No |
| Notifications on home screen, music controls | Notification access (granted in system settings) | No |
| Widget panel | Android's per-app "create widget" confirmation | No (the widget's own app may) |
| App's own notifications | Notifications (`POST_NOTIFICATIONS`, Android 13+) | No |
| Contact search | Contacts (`READ_CONTACTS`) | No |
| Web suggestions | None | Yes: what you type in the search box to duckduckgo.com (not sums while the calculator is on) |
| App shortcuts in search | Being the default home app | No |
| Suggested apps | Usage access (the same one screen time uses) | No |
| Sort by usage | Usage access (the same one screen time uses) | No |
| Calculator, A–Z scroller, icons, per-app swipes | None | No |
| Fonts, folders, note, blur, e-ink | None | No |

Importing a configuration file never grants a permission: a setting that needs one is only switched on when you've
already allowed it, otherwise it's skipped ([Configuration files](configuration.md#security)).

## Network and hardening

- Every request is https only, with a time limit and a size limit (weather, web suggestions, the daily wallpaper and its list, update checks), so a slow or broken server can't hang a feature or use up memory. Cleartext traffic is off for the whole app.
- Direct build updates come only from the Zealot host in `AppRelease.ZEALOT_URL`, and the APK is checked (this app, newer, same signing key) before Android installs it. See [App releases & updates](app-updates.md).
- **Lock hidden apps** also keeps hidden apps out of app pickers while locked, needs the lock opened to turn it off, and blocks screenshots of the hidden-apps list. Private Space apps never appear in Inilauncher's own app picker.
- Release builds drop debug logging.
