# Syncing with Olauncher

The clone keeps Olauncher's history, with the remote named `upstream`.

```sh
git fetch upstream
git merge upstream/master
```

Upstream code is changed only in small hooks marked `// Inilauncher:` (see [Architecture](architecture.md#hooks-into-upstream-code)). Conflicts, if any, should be in those lines.

To try a merge without keeping it:

```sh
git merge --no-commit --no-ff upstream/master
git merge --abort
```

After a merge:

- If upstream added English strings that say "Olauncher", override them in `app/src/brand/res/values/strings_brand.xml`, add their names to `SHOWN` in `scripts/brand-translations.py`, and run `python3 scripts/brand-translations.py .` to refresh the translated overrides.
- `fastlane/metadata/android/en-US/*.txt` is Inilauncher's own text, so keep ours if it conflicts. Upstream's store screenshots, icon and other languages' listings are Olauncher's branding: if a merge brings them back, delete them again.
- Run `./gradlew assembleDirectDebug testDirectDebugUnitTest lintDirectDebug`. Lint compares against `app/lint-baseline.xml`, so only new problems fail.
