# Features

Everything Olauncher does still works the same way. The extras below are on **Settings → Extras…** and are all off by default.

| Feature | Where | Status |
|---|---|---|
| Custom fonts (system Sans, Condensed, Serif, Monospace, Casual) | Look | done |
| Folders | Search & app list | done |
| Lock hidden apps (fingerprint, face or screen lock) | Privacy & security | done |
| Note on home screen | Home screen | done |
| Blur background (Android 12+) | Look | done |
| Force e-ink mode | Look | done |
| Gesture animations | Look | done |
| Daily wallpaper photos (Inilauncher list or your own folders) | Look | done |
| Day & night by sun (theme + wallpaper) | Look | done |
| Weather (Open-Meteo) | Home screen | done |
| Upcoming events | Home screen | done |
| Widget panel | Home screen | done |
| Notifications on apps (read, reply, dismiss) | Home screen | done |
| Music controls | Home screen | done |
| Sub list (⋯ under the home apps) | Home screen | done |
| Contact search | Search & app list | done |
| Calculator in search | Search & app list | done |
| Web suggestions | Search & app list | done |
| App shortcuts in search | Search & app list | done |
| A–Z scroller | Search & app list | done |
| Suggested apps | Search & app list | done |
| Sort by usage | Search & app list | done |
| App icons and icon packs | Search & app list | done |
| Per-app swipe actions | Search & app list | done |
| Self-update from the release server | Extras | planned (phase 4) |

## How the extras work

- **Fonts** use the phone's own font families, so nothing is downloaded. Olauncher's "Bold font" setting only changes the default font.
- **App info and Uninstall everywhere:** long-press an app on the home screen for **Change app**, **Info** (Android's app info) and **Uninstall**. The same two actions are in the sub list and folder menus. System and work-profile apps open App info instead of uninstalling. Empty slots, folders and **Widgets ›** still go straight to the app picker.
- **Folders:** long-press an app in the app list → **Folder** → pick a folder or type a new name. Folders show at the top of the app list with a `›`. Tap one to open it, hold an app inside it and drag to move it, or hold and let go to remove it (or open its **Info** / **Uninstall**), and use **Edit** (or long-press the folder) to rename or delete it. You can put a folder (or **Widgets ›**) on the home screen or on a swipe gesture like an app. A folder on the home screen keeps the name it had when you placed it.
- **Note on home screen:** a short note under the clock. Tap it to edit (up to 500 characters).
- **Blur background** blurs the wallpaper behind the app list and settings. Android turns blur off in battery saver and on some phones.
- **Force e-ink mode** does everything Olauncher already does for e-ink screens it detects: light theme, no animations, no blur.
- **Gesture animations** (off by default) animate the home screen's gestures. Upstream still decides whether a swipe counts; this only adds motion.
  - While you swipe, the home screen follows your finger and springs back if you let go early. A sideways swipe shows the name of the app it would open at the edge.
  - Swiping up slides the app list in from below. Swiping left or right sends the home screen that way and opens the app from the other side. Swiping down gives a small pull while the notification shade opens.
  - Holding shrinks the home screen a little until settings open. Double-tap to lock dims the screen first. A home app's own swipe app slides that row before it opens.
  - A swipe with nothing set on it stretches a little further and bounces back.
  - Nothing moves on e-ink screens (detected or forced) or with system animations off.
- **Daily wallpaper photos** picks where Settings → Daily wallpaper gets its photos: the **Inilauncher list** (light/dark Unsplash pairs, downloaded) or **My folders** (a light and a dark folder on the phone, no internet). See [Daily wallpapers](wallpapers.md).
- **Day & night by sun** switches to the light theme and the **Day wallpaper** at sunrise, and to the dark theme and the **Night wallpaper** at sunset, on the home and lock screen.
  - **Switch times:** sunrise and sunset are worked out on the phone (no network) from the weather city, or from your approximate location. Pick **Fixed times** to set the switch times yourself (default 07:00 and 19:00). The fixed times are also used when no place is known.
  - **Wallpapers:** pick a photo for day and for night (the system photo picker, no permission needed). A copy is saved in the app, so the photo can be deleted from the gallery. If a time of day has no photo, Olauncher's daily wallpaper (its light or dark set) is used when it's on in Settings. Otherwise the wallpaper stays as it is.
  - The switch runs in the background at sunrise and sunset, and again when you return home in case Android delayed it. E-ink screens always stay light.
  - The theme changes only once the new wallpaper is in place, so the text always suits the wallpaper. The next daily wallpaper is downloaded ahead, at the previous switch, so the switch normally works offline. If it can't be set yet, the old look stays, and the switch is tried again when there's a network (and each time you return home).
  - Picking a theme by hand in Settings turns day & night off, so it doesn't undo your choice.
- **Lock hidden apps** asks for your fingerprint, face or screen lock before showing hidden apps, and before the lock can be turned off. While it's locked, hidden apps also stay out of every app picker (home slots, folders, the sub list, swipes), and the hidden-apps list can't be screenshotted or screen-shared. It stays unlocked for 5 minutes or until the screen turns off. Hidden apps never show up in search, and the sub list's "most-used" apps never include them, lock or not.

- **Weather** shows the temperature and sky under the date. Pick **My location** (approximate, asks for location access) or **Search city** (no permission needed). Refreshes about once an hour on a network; the last value stays while offline. Set **Tap opens** to the weather app you want. Data by [Open-Meteo](https://open-meteo.com) (CC BY 4.0).
- **Upcoming events** lists the next three events of the coming 24 hours from your visible calendars. Tap one to open it.
- **Widget panel:** turn on **Widgets**, then open **Widgets ›** at the top of the app list (or put it on a swipe gesture). **+ Add widget** lists every widget your apps offer; Android asks once per app whether Inilauncher may show it. Widgets sit in a 4-column grid: each starts at the width it asks for, and small ones share a row. Hold a widget and drag it to move it; the others make room. Hold it and let go for a frame with three dots: drag the one on the side for the width, at the bottom for the height, or in the corner for both. Widths snap to columns and heights to steps. The bar next to the frame has **Remove** and, for widgets with their own setup screen (to pick a list, account or style), **Settings**, which opens that screen again; the widget redraws when you're done, and Back keeps it as it was. Tap outside the frame or press Back when you're done. With TalkBack, the **Widget** action on a widget opens a menu with the same choices.
- **Notifications on home screen** (needs Notification access): a dot after the names of apps with notifications, and an "N notifications" line under the clock. Tap the line to see them as cards (app, time, title, text): tap a card to open it, swipe it sideways to dismiss it, or use **Reply** and **Clear all**. Ongoing notifications spring back instead of going away.
  - **Dot options** (under the switch while it's on): turn off the **Notifications line under the clock** to keep only the dots (the shade still lists everything), or turn off **Dots on apps** to keep only the line. **Dot style → Count** shows how many an app has (`Messages •3`, `•9+` above 9). **Dots on folders** marks a folder, on the home screen and in the app list, when an app inside it has notifications (with Count, their total). The same settings apply to the sub list.
- **Sub list:** a small `⋯` under the home apps opens a row of apps you use often but don't want in the main list. Tap an app to open it; the row closes again after that and whenever you leave the home screen.
  - **Adding apps:** long-press `⋯` → **Choose apps…** (a full-screen list like the app list, with search, icons and the A–Z strip; the apps you already picked are on top with a ✓, then, with **Add most-used apps** on, a **Most used** section; tap to tick or untick, new ones go to the end), or long-press an app in the app list → **Sub list** (tap again to remove it).
  - **Long-press `⋯`** also sets the **Button** (dots, grid icon, count `+N`, or a peek of the first icons) and what it **Shows** (icons, icons with names, or names). The same rows are on the Extras screen.
  - **Hold an app in it and drag** to move it. Hold and let go to remove it, or open its **Info** / **Uninstall**.
  - **Add most-used apps** (Android 10+, usage access): fills it up to 10 apps with the ones you opened most in the last 30 days, leaving out apps on the home screen. Hold one of those and let go to **Keep in sub list** or **Don't suggest** it; dragging it in among your own apps keeps it there.
  - Icons follow the App icons style; with App icons off, the sub list uses system icons. A long sub list scrolls instead of running off the screen.
- **Music controls** (same Notification access) show what's playing with previous, play/pause and next.
- On Android 13+ Inilauncher asks once at startup whether it may post notifications. It only uses that for its own messages, such as "updated, tap to open".

- **Search** shows extra results above the app list, grouped as Calculator, Contacts, App shortcuts and Web, each turned on separately:
  - **Calculator:** type a sum such as `12*3-4` and the answer shows above the list. Tap it or press Enter to copy it.
    - You can type `+ - * / ^` and brackets, and the usual alternatives: `×` `·` `x` `÷` `−` `–` `**` `²` `³` `[ ]` `{ }`, a leading or trailing `=`, full-width signs and digits of any script (Persian, Arabic…). Open brackets are closed for you (`(1+2` = 3).
    - `%` works like on a phone calculator: `50%` = 0.5, `200+10%` = 220, `200-10%` = 180, `200*10%` = 20. Between two numbers it's the remainder (`7%3` = 1).
    - The maths is exact decimal (`0.1+0.2` = 0.3, long numbers aren't cut off). Answers have up to 15 digits; very large or small ones are shown like `1E+20`.
    - When a number is unclear it shows no answer rather than a wrong one: `1,000` on a phone where `,` groups thousands could be one thousand or one, and `1.2.3` isn't a number. The same goes for impossible maths (dividing by zero, `(-8)^(1/3)`) and very long or deeply nested input.
    - While the search could still be a sum (it starts with a digit, `(`, `-` or `.` and has only calculator characters), the app list doesn't open the only matching app by itself, so the `1` of `12*3` doesn't open an app. Type a letter (`1p`) and it works as usual. With the calculator off nothing changes.
  - **Contacts** (contacts access): matching names; tap to open the contact.
  - **App shortcuts:** shortcuts like "New message" from your apps. Android only allows this while Inilauncher is your default home app.
  - **Web suggestions:** DuckDuckGo suggestions; tap one to search it. What you type is sent to DuckDuckGo while this is on, except sums while the calculator is on (it answers them on the phone).
- **A–Z scroller:** a letter strip on the side of the app list. Drag to jump; letters without apps are fainter (also while searching).
  - **Pull to pick:** while holding a letter, slide sideways towards the app names. The suggested row on top lights up first, from the app nearest your finger to the farthest; sliding further walks through the held letter's apps from the top of the list down. Lift to open it; slide back onto the strip to cancel.
  - **Scroller side:** opposite the app names (default), or fixed **Left** / **Right** for left- or right-handed use. **Scroller height** → **Thumb reach** makes it cover only the lower part of the screen.
  - **Keyboard:** touching the strip or dragging the list puts the keyboard away. Double-tap the strip, or tap **⌨** at its bottom, to open or close it; a double tap leaves the list where it was.
- **Suggested apps** (Android 10+, usage access): four apps above the list, ranked on your phone by how often, how recently and at what time of day you use them. Hold one for **Pin** (it stays at the front of the row, pinned apps in the order you pinned them, with a small pin badge on its icon, or a pin above its name with icons off, until you **Unpin** it; while every place is pinned, Pin isn't offered) or **Don't suggest**.
- **Sort by usage** (Android 10+, usage access): the app list stays A to Z, but within each letter the apps you opened most in the last 30 days come first. The A–Z scroller and search work as before.
- **App icons** (Settings → Appearance → App icons): off, system icons, **Monochrome** (the app's single-colour icon in your text colour, grayscale for apps without one), **Grayscale**, **Letter dots** (a small circle with a two-letter monogram: the initials of the first two words, or the first letter plus the start of the next syllable, e.g. WA for WhatsApp, VB for Viber), **Colour letter dots** (the same, filled with the main colour of the app's real icon), **Colour outline dots** (see-through, with the ring and letters in that colour, adjusted to stay readable), or any installed icon pack. It applies to the app list and the home screen; folders and Widgets › get simple built-in icons.
- **Per-app swipe actions:** for each home-screen app, choose an app that opens when you swipe left or right on its name. Other swipes keep Olauncher's swipe-left/right apps.

- **Choosing an app** (the sub list, weather **Tap opens**, per-app swipe actions) opens a full-screen list like the app list: search by name, icons, and the A–Z strip with the same side and height settings. Choosing one app also has a **Nothing** / **Usual swipe app** button to clear it.

## Changes from Olauncher

- The "rate us", "share" and "Pro" prompts are gone. Upstream's daily-wallpaper tip stays.
- "More features…" on the settings screen is now **Extras…**.
- The About link opens an in-app About with the credit and license. The Play Store share/rate, developer social links, app promo and upstream privacy-policy rows are hidden.
- The daily wallpaper uses Inilauncher's own photo list (a gist) instead of Olauncher's, or your own folders. See [Daily wallpapers](wallpapers.md).
- The app id is `app.inilauncher` (debug: `app.inilauncher.debug`), so it installs next to Olauncher.
