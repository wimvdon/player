# Architecture

## This app

Player builds a set of tracks from a few controls (mood, familiarity, genres, seed artists,
length) using MusicBrainz and ListenBrainz, and plays it through Spotify in a new private playlist
for each set, with a name Player suggests and the user can change. Each user enters their own
Spotify Client ID and, optionally, their ListenBrainz username (see `docs/decisions.md`, 2026-11-22).

**Screens** (`ui/`): Generator (start), Preview (swipe to remove, Regenerate, Send to Spotify),
Playlist (open in Spotify, progress, Stop), Settings (keys and setup steps, playlist order, appearance) and
"API calls and cache" (debug). `ui/AppNavigation.kt` keeps them as a small back stack.

**How a set is made** (`engine/GenerationEngine.kt`, no Spotify searches; music data comes from
`data/brainz/BrainzMusic.kt`, which turns names into MusicBrainz IDs and caches every answer):
1. Seeds: the picked artists and genres, or the user's top artists (Spotify, then ListenBrainz).
2. Candidate artists: ListenBrainz similar artists (Labs) per seed and, per genre, the artists of
   ListenBrainz tag-radio recordings ranked by tag votes (topped up by a MusicBrainz tag search),
   shared out round-robin, as many as the length needs (`artistBudget`: 100 for 250 tracks,
   40 to 170; both services allow one call a second). When that's too few, the similar artists of
   up to 12 candidates are added.
3. Tracks: ListenBrainz's most listened recordings per artist (15, or 20 with fewer than 50
   artists). Artists without known tracks are replaced by the next candidates.
4. Mood (0 calm … 1 upbeat): cached ReccoBeats tempo and energy when known, otherwise the artist's
   MusicBrainz genres and tags weighted by `MoodModel.TagWeights` (`logic/Scores.kt`, one editable map).
5. Familiarity (0 known … 1 new): known when in the user's Spotify top or saved tracks, their
   ListenBrainz top recordings or their whole ListenBrainz play history (`data/brainz/ListenHistory.kt`,
   read in the background, never awaited by a set); otherwise from the ListenBrainz listener count on a log scale.
6. `logic/Generator.kt` picks the closest tracks to both sliders (with a little randomness), at
   most 3 per artist (up to 6 when that's the only way to reach the length), at least half from artists the user didn't pick (unless familiarity is fully
   "known"), orders them (mood curve, best match or shuffle) and keeps the same artist apart.

**Filling the playlist** (`engine/QueueController.kt`, while `service/QueueService.kt` shows a
notification): first every track is matched on Spotify (`engine/TrackResolver.kt`, one search
per track, cached forever, misses too). Only then is a new private playlist made, with the name
from the send sheet (`logic/PlaylistName.kt` suggests one from the genres or artists, the mood
and the date, plus other names for the dice), and every match goes in straight away, in calls of 100 (Spotify's limit,
`logic/PlaylistPlan`). Then Player starts it on the active device or opens it in the Spotify app.
After a problem, Continue picks up where it stopped without making a second playlist. See
`docs/decisions.md` for why.

**MusicBrainz and ListenBrainz** (`data/brainz/`): two clients (`MusicBrainzSource`,
`ListenBrainzSource`), one call a second each through `BrainzHttp`, combined into the engine's
`MusicSource` by `BrainzMusic`. An artist picked from the search keeps its MusicBrainz ID, so
artists with the same name don't mix. The genre picker offers a fixed list (`logic/Genres.kt`).

**Services** sit behind interfaces in `data/Sources.kt` (`MusicSource`, `MusicBrainzSource`,
`ListenBrainzSource`, `SpotifyCatalog`,
`PlaylistTarget`, `AudioFeatureSource`) so the engine is tested with fakes.

**Spotify's quota:** Development Mode apps have a small, unpublished quota. Every call is logged
(`logic/CallLog.kt`, shown on the debug screen). A 429 with `QUOTA_EXCEEDED` pauses all Web API
calls for 6 hours (stored, so it survives a restart); other 429s wait for `Retry-After`. Endpoints
that are gone for new apps (recommendations, audio features, related artists, artist top tracks,
browse, batch track lookups) and the `popularity` field are not used. Search uses `limit=5`.

**Where data lives:**
- `backed_up_settings` DataStore (`data/store/Stores.kt`, `SettingsStore`): theme, playlist order,
  ListenBrainz username, the generator's last input.
- `device_secrets` DataStore (`SecretStore`): Client ID, Spotify tokens and their
  scopes and the quota pause, each value encrypted with an Android Keystore AES key. Not backed up.
- `noBackupFilesDir/cache/*.json` (`data/cache/Caches.kt`): MusicBrainz and ListenBrainz answers (artist IDs 180 days, the rest 30 days), the user's
  library and charts (1 day), Spotify matches and audio features (forever).
- `noBackupFilesDir/listen_history.json` (`data/brainz/ListenHistory.kt`): every track in the
  user's ListenBrainz play history (artist and title keys only, no times per play), with how far
  reading got. The first read pages through all plays, 1 000 a call (a few minutes for a long
  history, resumed if interrupted, saved every 10 calls); after that only plays newer than the
  last one are read, at most every 6 hours, at app start, when the username changes and when a set
  is generated. Settings shows the count; Clear cache deletes the file.

## Structure
```
app/src/main/kotlin/<package>/
  PlayerApp.kt             Creates the single instances (AppGraph); no DI library.
  MainActivity.kt          Entry point: theme and navigation.
  logic/                   Pure Kotlin rules (generator, scores, matching, rate limits) → unit tested.
  engine/                  Generation and queueing over the data interfaces → unit tested.
  data/                    MusicBrainz, ListenBrainz, Spotify Web API, ReccoBeats, stores and caches.
  service/QueueService.kt  Foreground service with the queue notification.
  ui/theme/                Material 3 theme (Theme.kt, Type.kt) and the shared design kit (see DESIGN.md).
  ui/common/               Player's own pieces: score bars, covers, and the expressive ones
                           (Expressive.kt: wordmark, bars logo, cookie shape, loader, wavy progress, slider,
                           stepper, mood line, record). Shape maths in logic/Blob.kt.
  ui/<screen>/             Composables: display state, forward clicks. No logic.
app/src/main/res/
  font/roboto_flex.ttf     Roboto Flex for the wordmark and big headlines (licence: assets/licenses/).
  values/strings.xml       All user-visible text (English, the default).
  values-xx/strings.xml    Dutch, French, German, Spanish and Italian. Every string must exist in every language.
app/src/test/kotlin/       JVM unit tests (mirrors the main package structure).
scripts/                   Release helpers (Python/Java) with their own tests.
.github/workflows/         ci.yml (every PR), release.yml (signed releases).
```

## Conventions
- **Single `app` module.** Split into modules only if the app grows large; record it in `decisions.md`.
- **Logic in plain Kotlin classes** with no `Context`, `View` or Compose types, so it can be
  tested on the JVM. Example: `logic/Generator.kt`.
- **UI state**: small screens use `rememberSaveable`. When a screen gets real logic or data,
  add a `ViewModel` (`androidx.lifecycle:lifecycle-viewmodel-compose`) that exposes a
  `StateFlow<UiState>` and calls into the plain-Kotlin logic. Record it in `decisions.md`.
- **Navigation**: five screens in a small back stack (`ui/AppNavigation.kt`), no navigation library.
- **Persistence**: Jetpack DataStore (version already in the catalog). Wrap it behind a small
  repository interface so logic tests can use a fake. Decide per value whether it is backed up
  (see "Backup" below).
- **No network, analytics, crash reporting or ads** unless the owner agreed (see `CLAUDE.md`),
  in which case update `PRIVACY.md`.
- **Strings** in resources only; plurals via `<plurals>`.
- **Versions** of libraries only in `gradle/libs.versions.toml`.
- **App identity** (`applicationId`/`namespace`) only in `app/build.gradle.kts` (`appId`).

## Backup
Android backs up app data to the user's Google account (if they turned backup on) and copies it
to a new phone during set-up. The app decides which files take part, in
`app/src/main/res/xml/data_extraction_rules.xml` (`cloud-backup` = Google account,
`device-transfer` = new phone). The rule is an **allow list**: only listed files are copied, so a
new file is never backed up by accident.

- **Backed up:** settings the user chose and would want back after a reset or on a new phone
  (theme, layout, toggles, names). Keep them in one DataStore named `backed_up_settings`
  (`preferencesDataStore(name = "backed_up_settings")`), which the rules already list.
- **Not backed up:** sign-in tokens, passwords and API keys (security, and keys encrypted with the
  Android Keystore can't be read on another phone anyway); state that only means something on this
  phone (a running job, notification IDs, "switched on right now"); settings that only work after
  a permission or set-up on this phone; copies of data an online service can download again; caches
  (Android never backs up `cacheDir` and `noBackupFilesDir`). Keep these in another DataStore file.
- Android rules work per **file**, not per key. Moving a value from one file to another in an app
  that is already released needs a `DataMigration` (see Snoozer's `MoveKeysMigration`).
- `BackupRulesTest` checks both sections list exactly the settings file. Change the test together
  with the rules, and update `PRIVACY.md` when what is backed up changes.
- **minSdk below 31:** Android 8-11 ignore `data_extraction_rules.xml` and read
  `android:fullBackupContent` instead. Add `res/xml/backup_rules.xml` with the same `include` lines,
  point `fullBackupContent` at it in the manifest, and let `BackupRulesTest` check it too.
- To test on a phone: change a setting, then open Settings, search for "Backup" and tap **Back up now**. Uninstall the app and install the same APK again: the
  setting comes back. Restore only works for an APK signed with the same key.
