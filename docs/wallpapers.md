# Daily wallpapers

Olauncher's **Daily wallpaper** (Settings → Daily wallpaper) sets a new photo every day. Inilauncher keeps the feature but uses its own photos, and adds a way to use your own (Extras → Look → **Daily wallpaper photos**).

## Inilauncher list (default)

- The list is [`wallpapers/daily_wallpapers.json`](../wallpapers/daily_wallpapers.json), published as a public gist. The app reads the gist's latest version (`DailyWallpapers.LIST_URL`), so a changed list reaches installed apps within a day, without a new release.
- Each entry is a **pair**: a light photo (light theme, or by day with Day & night) and a dark one (dark theme, or at night). The day picks the pair: day number since 1970 modulo the number of pairs, so everyone sees the same pair on the same day and the list can be any length.
- No photos are in the APK. The app downloads the list about once a day, keeps the last good copy, and downloads the day's photo from Unsplash. With no list yet (first run offline) it uses one built-in pair.
- Photos are from [Unsplash](https://unsplash.com/license): free to use, credit not required. Settings' daily wallpaper link opens the photo's Unsplash page, which credits the photographer.

Format:

```json
{
  "version": 1,
  "pairs": [
    {
      "light": { "url": "https://images.unsplash.com/photo-…?w=1440&q=85&fit=max", "page": "https://unsplash.com/photos/<id>", "by": "@photographer" },
      "dark":  { "url": "…", "page": "…", "by": "…" }
    }
  ]
}
```

Only `https://` image URLs count; a pair with a broken half is skipped. `spare` (photos waiting for a partner, written by the review page) is ignored by the app.

## Changing the list

1. `npm run wallpapers` opens a review page at `http://inilauncher-wallpapers.localhost:4411`. Every pair is shown behind a mock home screen (black text on the light photo, white on the dark one). Approve or reject each photo; decisions are saved in `wallpapers/review.json` (commit it, so rejected photos stay out).
2. **Apply to list** rewrites `daily_wallpapers.json` without the rejected photos. The remaining light and dark photos are paired up again in order; any left over without a partner wait under `spare`.
3. To add photos, open **Add photo** on the review page. On unsplash.com open a free photo, right-click it → **Copy image address**, paste it, choose light or dark, and optionally add the photo's page link and photographer. The page checks the image loads, skips duplicates and Unsplash+ (paid) photos, and adds it under `spare` as "to review". **Apply to list** pairs it with a waiting photo of the other kind. Pick portrait photos that keep text readable: calm and bright for light, dark and low-contrast for dark.
4. `scripts/publish-wallpapers.sh` shows the diff against the gist and publishes after you confirm (`--dry-run` to only look). It needs `gh`, signed in as the gist's owner.

`DailyWallpapersTest` checks that the repo copy parses.

## Picking photos

The launcher draws plain black text on a light wallpaper and white text on a dark one, so a photo only works if that text stays readable. Check each new photo against these rules before adding it:

- **Light photos are very bright where the text sits**: the clock (top left) and the app names (left, lower middle). On a 150×300 thumbnail those areas should average at least 190 out of 255 grey, with little contrast (standard deviation of 25 or less). Mid-tone photos (150–175) make black text hard to read.
- **Dark photos stay dark everywhere behind the text**, not just on average: the brightest 5% of those areas should stay under about 90. Bright spots such as sea foam, lit clouds or city lights behind the text make white text unreadable.
- **Something to recognise**: clouds, a beach, a tree, a peak, a flower. A plain colour gradient isn't enough.
- **A new theme**: before adding a photo, count the themes already in the list and pick one that's missing or rare. Don't add another of a theme that's already common, such as night skies (10 of 30 dark photos). Ideas for new themes: flowers, leaves, a city at night, rain, water, sand, snow, interiors.
- **Calm, soft photos**: soft pastel skies, pale beaches and soft snow work for light; dark forests, mountains, sea and flowers for dark. Saturated or dramatic skies, fog and grey haze don't.
- **Portrait and free**: portrait orientation, and the free Unsplash License (not Unsplash+).

## My folders

Pick a **Light photos** and a **Dark photos** folder with the system folder picker. Each day one photo is taken from the folder that matches the theme (or day/night phase), in name order, so it changes daily and repeats once all have been shown.

- No internet and no storage permission: the app keeps read access to just those two folders.
- Only photos directly in a folder count, not ones in subfolders.
- If one folder is empty or can't be read, the other one is used. If neither works, the Inilauncher list is used.
- Day & night's own **Day/Night wallpaper** photos still win over both.
