# Inilauncher: project rules

Inilauncher is a fork of Olauncher. Our own code lives in `app/src/main/java/app/olauncher/extras/`. Upstream files carry small hooks only, marked `// Inilauncher`.

## Motion: everything moves fluidly

Every visible change in the launcher is animated. Nothing jumps, pops, blinks or gets cut. This applies to every new feature and every change.

- **What counts:** showing or hiding a view, a view growing or shrinking (including text that gets longer or shorter), adding or removing views, opening or closing panels and dialogs, swapping content, and reordering.
- **Use `helper/Motion.kt`.** Don't hard-code durations, interpolators or the e-ink check.
  - `Motion.SHORT` (150 ms) for small things, `Motion.medium(context)` (250 ms, same as the dialog fade) for bigger moves, `Motion.easing` for every interpolator.
  - `ViewGroup.animateChanges()` on any container whose children appear, disappear or resize. The container and its parents then grow and shrink smoothly. Pass `changing = false` when a child animates its own size every frame (e.g. a collapsing card).
  - `View.fadeVisible(visible)` instead of `isVisible = …` for anything the user can see change.
  - `View.fadeTo(alpha)` to change how faded a view already on screen is. For a view that is about to appear, pass the alpha to `fadeVisible(true, alpha)` instead: a container's appear animation always ends fully opaque.
  - `ViewGroup.beginTransition()` before a one-off layout change: padding, gravity, moving views.
  - `View.expandVisible(visible)` inside a list whose items collapse their own height (e.g. swiped-away cards). It grows or shrinks the view in the same frames. A container transition would aim at a stale size there and make the edge overshoot.
- **E-ink and "animations off":** `Context.animationsOn()` is false on e-ink screens (detected or forced) and when any system animation scale is 0. Everything must then change instantly, with no half-run animations. The helpers handle this; custom animators must check it too.
- **Animate transforms, not layout.** Prefer translation, alpha and scale. A layout change animates through `animateChanges()` / `beginTransition()`, not by setting layout params every frame. The one exception is collapsing a removed item's space.
- **Don't rebuild what didn't change.** `removeAllViews()` + re-add on every bind makes rows blink. Compare first (see `HomeExtras.showEvents`).
- **Dialogs:** use `OlDialog`. Its card sits inside a full-height, see-through window, so content changes resize the card smoothly. It opens centred, then keeps its top edge in place. Don't call `window.setLayout` or set a window background on it. To change the soft-input mode, keep `SOFT_INPUT_ADJUST_RESIZE`.
- **Don't animate:** typing (search results per keystroke) and scrolling. They must stay instant.
- **Check it on a phone:** watch for jumps, overshoots and edges moving the wrong way, and check frame stats (`adb shell dumpsys gfxinfo <package>`: reset, act, read; few janky frames). `adb shell screenrecord` crashes on some phones (e.g. OnePlus). A burst of raw `screencap` shots, about 140 ms apart, still shows each edge's path. Also check with "Force e-ink" turned on, which must make everything instant.

## Releases: always add What's new

Every release with a change people can see gets a What's new entry for the version being tagged. Follow `docs/app-updates.md#whats-new`.

- **Before tagging:** add the entry at the top of `app/src/main/assets/whatsnew/whatsnew.json`, with an intro and one page per visible change.
- **Screenshots when possible:** take them on the screenshot emulator with `scripts/whatsnew-shot.sh`, in dark and light themes. If no emulator can be used, still add the entry without images. Never skip the entry.
- **Get it approved first:** put the pages on a private review page for the user, and tag only after they approve it.
- **Only fixes nobody sees:** ask the user before tagging without an entry. Don't decide alone.
- **If a release already went out without one:** say so plainly, and add the notes to the next version. Never move a pushed tag.
