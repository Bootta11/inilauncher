# Website

The public site lives in `site/` and is published to **https://inilauncher.bootta.dev** by GitHub Pages
(`.github/workflows/pages.yml`, on every push to `master` that touches `site/`). Only `site/` is published; the
developer docs in `docs/` stay out of it.

| Page | Source | Address |
|---|---|---|
| Home (presentation) | `site/index.html` | `/` |
| Privacy policy | `site/privacy.md` | `/privacy` (Settings → Privacy and the Play listing link here) |
| Not found | `site/404.html` | any missing page |

## Editing

- **Text and layout:** `site/index.html`, `site/_layouts/default.html` (header and footer), `site/assets/site.css`.
  Dark by default, light when the device prefers it. The header's switch (Auto → Dark → Light) overrides that and is
  remembered in the visitor's browser.
- **Feature cards:** `site/_data/features.yml`, grouped as on the page. Each card names a `shot`: the page shows
  `site/assets/shots/features/<shot>-dark.webp` or `-light.webp` to match the theme (`single: true` uses one picture for
  both, `fit: contain` for pictures that aren't a whole screen). `more` lists features without a picture.
- **Download buttons:** set `play_url` (and optionally `apk_url`) in `site/_config.yml`. A button shows only when it
  has a link; until then the page offers the source code.
- **Screenshots:** `site/assets/shots/features/*.webp`, 720 px wide, taken on the screenshot emulator
  ([App releases & updates → The screenshot emulator](app-updates.md#the-screenshot-emulator)) with every extra turned
  on, made-up contacts and calendar events, and text messages sent with `adb emu sms send`. Nothing comes from a personal
  phone. Convert with `cwebp -q 78 -resize 720 0 in.png -o out.webp`.
- **Privacy policy:** keep `site/privacy.md` in step with [Permissions & privacy](permissions.md) whenever a feature
  changes what it needs or sends, and update its date.
- **Preview locally:** `docker run --rm -v "$PWD/site:/srv/jekyll" -p 4000:4000 jekyll/jekyll:3.8 jekyll serve`, then
  open http://localhost:4000.

## One-time setup

1. **DNS** (where `bootta.dev` is managed): a `CNAME` record `inilauncher` → `bootta11.github.io`. On Cloudflare set it
   to **DNS only** (grey cloud), so GitHub can issue the HTTPS certificate.
2. **Repo → Settings → Pages:** Source **GitHub Actions**. Under Custom domain enter `inilauncher.bootta.dev` and save.
3. Push (or run the **Website** workflow by hand), wait for the certificate, then tick **Enforce HTTPS**. `.dev` sites
   only open over HTTPS, so the site is unreachable until the certificate exists.
4. Recommended: **GitHub account → Settings → Pages → Verified domains**, verify `bootta.dev`, so no other GitHub
   Pages site can claim a subdomain of it.
5. **Play Console:** Store listing → Website `https://inilauncher.bootta.dev`; App content → Privacy policy
   `https://inilauncher.bootta.dev/privacy`.
