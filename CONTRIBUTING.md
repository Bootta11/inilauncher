# Contributing to Inilauncher

Inilauncher stays minimal: new features go on the Extras screen and are off until turned on. Bigger standalone features belong in a separate app.

## Before opening a PR

- For anything beyond a small, obvious fix, open an issue first so we can agree on scope.
- Keep PRs focused: one concern per PR.
- Our code lives in `app/src/main/java/app/olauncher/extras/`. Upstream Olauncher files get small hooks only, marked `// Inilauncher` (see [docs/architecture.md](docs/architecture.md)).
- Every visible change animates; see the Motion rules in [CLAUDE.md](CLAUDE.md).

## Building

JDK 17 and the Android SDK. Before opening a PR:

```sh
./gradlew assembleDirectDebug testDirectDebugUnitTest lintDirectDebug
```

## License

GPLv3, like Olauncher. By contributing, you agree your changes are licensed under the same terms.
