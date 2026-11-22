# Testing

## Automatic (CI, on every pull request and push to `main`)

| Check | Command | Catches |
|---|---|---|
| Release script tests | `python3 -m unittest discover -s scripts/tests` | Version maths, keystore-secret decoding |
| Unit tests | `./gradlew testDebugUnitTest` | Logic bugs in plain-Kotlin classes (`app/src/test/`) |
| Lint | `./gradlew lintDebug` | Android API misuse, missing translations, accessibility, hard-coded text, … (errors fail the build) |
| Debug build | `./gradlew assembleDebug` | Compile errors; produces the APK to test on the phone |
| Release build | `./gradlew assembleRelease` | Problems caused by R8 minification / resource shrinking |

Reports (test results, lint HTML) are attached to every CI run as the `reports` artifact, also
when the run fails.

Run all of it locally before pushing:
```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
python3 -m unittest discover -s scripts/tests
```

### Writing unit tests
- Every logic change gets a test. Put logic in plain Kotlin classes (no Android types) and test
  them with JUnit in `app/src/test/kotlin/`, mirroring the package. See `GeneratorTest`.
- Services are faked behind their interfaces (`FakeMusic`, `FakeCatalog`, `FakePlayer` in the
  tests), so generation and queueing run without network. MusicBrainz (`mb_*`) and ListenBrainz
  (`lb_*`) parsing reads answers shaped like the documented ones from
  `app/src/test/resources/fixtures/`. `BrainzMusicTest` checks caching and name-to-ID matching.
  Tests use made-up usernames only. `BrainzHttpTest` fakes HTTP with an OkHttp
  interceptor to check the User-Agent, the token and waiting on rate limits.
- Test names describe behaviour in words: `` fun `tap stops at the maximum`() ``.
- No device tests (instrumented/UI tests) are set up: they need an emulator, which makes CI slow and
  flaky. Add them only if a decision in `decisions.md` says so.

## Manual (on the phone)

What automation can't check: how it looks and feels on a real device. For each pull request (before or after it is merged: merging only waits for green CI),
install the debug APK (see README, "Testing a pull request") and follow the steps in the pull request
description. In addition, check every now and then (always before a release):

- [ ] The app starts, and the changed screen looks right.
- [ ] **Dark mode**: switch the phone's dark theme on/off. Text stays readable.
- [ ] **Rotation** (if auto-rotate is on): the screen still works and keeps its state (sliders, picked genres).
- [ ] **Language**: Settings → System → Languages → App languages → this app → Nederlands.
      All text is translated.
- [ ] **Large text**: Settings → Display → Font size at maximum. Nothing is cut off.
- [ ] **Back** gesture/button behaves as expected.
- [ ] After a release: install the release APK over the previous release. It updates without
      losing data.
- [ ] **Backup** (once the app stores settings): change a setting, Settings → search "Backup" →
      Back up now, uninstall, install the same APK again. The setting is back.

## Player on the phone (needs Spotify Premium and the Spotify app)

- [ ] **Setup:** with no keys, the start screen asks to connect. Settings → Spotify → the steps show
      the redirect URI, package name and SHA-1 with copy buttons. Connect Spotify signs in and shows
      "Signed in as …".
- [ ] **Generate:** pick 2 artists and 1 genre, 250 tracks. The progress bar runs; with an empty
      cache the preview appears in under a minute, the second time (same input) in seconds.
- [ ] **Preview:** swipe a track left, it disappears; Undo brings it back. Regenerate gives a
      different set.
- [ ] **Playlist:** Send to Spotify. The dialog suggests a name such as "Upbeat Indie & Jazz · 22 Nov";
      change it. Start. The notification reads "Finding tracks on Spotify: x of 250", then a new
      private playlist with your name appears in Spotify with every track at once and starts
      playing (or opens in Spotify to press play).
- [ ] **Empty name:** clear the name field. Start is greyed out until you type something.
- [ ] **A second set** makes a second playlist; the first one keeps its tracks.
- [ ] **Debug:** Settings → API calls and cache lists the calls; Spotify searches only appear as
      tracks get added.

## Reading a crash from a release build
Release builds are minified, so stack traces show short names like `a.b.c`. Download
`<app>-<version>-mapping.txt` from the matching GitHub Release and decode the trace with R8's
`retrace` (Android SDK `cmdline-tools/latest/bin/retrace`):
```sh
retrace <app>-<version>-mapping.txt stacktrace.txt
```
Or ask Claude Code to do it: give it the stack trace and the version.
