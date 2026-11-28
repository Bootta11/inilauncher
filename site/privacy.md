---
layout: default
title: Privacy policy
description: What Inilauncher does with your data. In short, nothing leaves your phone unless you turn on a feature that needs the internet.
permalink: /privacy
---
<article class="doc" markdown="1">

# Privacy policy

*Last updated: 28 November 2026*

Inilauncher is a minimal Android home screen app. This policy explains what it does with your data.

## In short

- Inilauncher has **no account, no ads, no analytics and no tracking**.
- The developer **does not collect, receive or store any of your data**. There is no Inilauncher server that your data goes to.
- Everything the app knows about you (your apps, settings, notes, calendar events, contacts in search, screen time) **stays on your phone**.
- A few **optional** features need the internet. They are off until you turn them on, and each one is listed below with exactly what it sends.

## Permissions and what they are for

Inilauncher asks for a permission only when you turn on the feature that needs it. If you say no, the feature stays off.

| Permission | Used for | Leaves your phone? |
|---|---|---|
| See installed apps | Showing your app list | No |
| Usage access (optional) | Screen time, suggested apps and sorting apps by use | No |
| Accessibility service (optional) | Double tap to lock the screen (see below) | No |
| Device admin (optional) | Double tap to lock on older Android versions | No |
| Approximate location (optional) | Weather and sunrise/sunset for "My location" | Rounded coordinates go to the weather service (see below) |
| Calendar (optional) | Showing upcoming events on the home screen | No |
| Contacts (optional) | Finding contacts in search | No |
| Notification access (optional) | Notifications and music controls on the home screen | No |
| Notifications | Inilauncher's own notices | No |
| Set wallpaper | Daily wallpaper and day/night wallpaper | No |
| Fingerprint/face (optional) | Locking the hidden apps list | No, handled by Android |

### Accessibility service

Inilauncher uses Android's Accessibility Service API for **one feature only: double tap on the home screen to lock your phone**. The service only reacts to taps inside Inilauncher. It does not read other apps, what is on your screen, or anything you type. Nothing it sees is stored or sent anywhere.

## Optional features that use the internet

All requests use encrypted HTTPS. None of them include your name, accounts, contacts or any identifier from Inilauncher.

| Feature | Service | What is sent |
|---|---|---|
| Weather, "My location" | [Open-Meteo](https://open-meteo.com/en/terms#privacy) (api.open-meteo.com) | Your coordinates rounded to about 1 km, about once an hour |
| Weather, "Search city" | [Open-Meteo](https://open-meteo.com/en/terms#privacy) (geocoding-api.open-meteo.com) | The city name you type, once |
| Web search suggestions | [DuckDuckGo](https://duckduckgo.com/privacy) (duckduckgo.com) | What you type in the search box while suggestions are on |
| Web search | [DuckDuckGo](https://duckduckgo.com/privacy) | Your search, opened in your browser when you choose to search the web |
| Daily wallpaper | GitHub (gist.githubusercontent.com) and [Unsplash](https://unsplash.com/privacy) (images.unsplash.com) | A request for the wallpaper list and the day's photo |

Like any website, these services can see your IP address when your phone contacts them. Their own privacy policies apply to that.

Versions of Inilauncher installed from outside Google Play can also check for app updates. That check asks the Inilauncher update server for the latest version and sends nothing about you. Copies from Google Play are updated by Google Play.

## Feedback

Settings → Feedback opens a GitHub issue form or your email app. They are filled in with the app version, Android version and phone model so a problem can be reproduced. Nothing is sent unless you submit the form or send the email yourself, and you can remove anything before you do.

## Backups and configuration files

- Android may include Inilauncher's settings in your own device backup (for example to your Google account), depending on your phone's backup settings. That backup is handled by Android and your backup provider, not by Inilauncher.
- Exporting a configuration creates a file where you choose. It contains your launcher settings, never permissions. It is shared only if you share it.

## Children

Inilauncher is a general home screen app. It does not knowingly collect data from anyone, including children.

## Changes

If this policy changes, the new version is published at this address with a new date. The full history is in the app's [public source code](https://github.com/Bootta11/inilauncher).

## Contact

Questions about privacy: open an issue at [github.com/Bootta11/inilauncher/issues](https://github.com/Bootta11/inilauncher/issues).

</article>
