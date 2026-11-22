# Decision log

Newest first. Add an entry for every notable choice: what was decided, why, and what was rejected.

## 2026-11-22: Top tracks come from ListenBrainz's artist radio, which needs no token

The genre fix above still gave 18 tracks from 3 artists on the phone. Running the real set-building
code against the live services showed why: ListenBrainz's `/1/popularity/top-recordings-for-artist`
now answers 401 ("you need to provide an Auth token for this endpoint", because of AI scrapers) for
most artists; only answers it still has cached, for very popular artists, come back. Every other
artist gave no tracks, whatever the genre or seeds. The app now reads, after the first 401, a sample
of 200 of the artist's recordings from `/1/lb-radio/artist/{mbid}` (no token), keeps the most
listened, and names them with `/1/metadata/recording`; listener counts are estimated as listens / 8.
That is two calls per artist instead of one, so ListenBrainz runs at 2 calls a second (it allows 30
per 10 seconds) and MusicBrainz mood tags are read alongside instead of afterwards. Genre artists are
now ranked by the most votes on one recording instead of the sum, so a big band with many lightly
tagged songs no longer comes first. Live check: shoegaze, 100 tracks, gave 100 tracks from 34 artists
in 82 seconds; one seed artist, 250 tracks, gave 250 tracks in 169 seconds.
Rejected: asking every user for a ListenBrainz token (an extra setup step; can be added later to
use the one-call endpoint again).

## 2026-11-22: Genre artists come from ListenBrainz tag radio; sets reach their length

A "calm shoegaze" set of 100 tracks came back with 12 tracks from 4 artists, none of them really
shoegaze. The MusicBrainz tag search ranks artists by how few other tags they have, so its first
50 for a genre are mostly little-known artists with one vote, whose tracks ListenBrainz doesn't
know; the few left were artists with a stray tag. Genre artists now come from ListenBrainz's tag
radio (`/1/lb-radio/tags`, recordings whose recording, album or artist carries the tag) named with
one metadata lookup per 50 recordings, ranked by the sum of their tag votes; the MusicBrainz tag
search only tops up the list (100 artists per genre). Artists without known tracks are replaced by
the next candidates (at most twice as many lookups as the set needs), a seed with too few similar
artists adds the similar artists of up to 12 of them, and when the pool still can't fill the set
at 3 tracks per artist the generator allows up to 6 per artist. Every track carries the name the
artist was looked up with, so "Smashing Pumpkins" and "The Smashing Pumpkins" no longer show as two.
Rejected: filtering similar artists by their tags (one MusicBrainz call each at one a second) and
dropping the per-artist cap altogether (one artist could then fill a set).

## 2026-11-22: Player gets a playful, dark-by-default look (Material 3 Expressive ideas)

The owner asked for a much more fun UI, inspired by Material 3 Expressive, with dark mode as the
default like Spotify, from an approved mockup (wordmark option A, the cookie icon).

- **Dark by default:** `ThemeMode.DARK` is the new default for anyone who never picked a theme;
  a stored choice (System, Light) stays. The window background is dark in every mode, so a user
  who picks Light sees a dark flash for a moment at start. Accepted: the default start matters more.
- **Hand-tuned colours** around the icon's middle stop instead of a generated tonal scheme: a
  vivid coral `primary` that a tonal dark scheme would wash out to pink, and amber only for mood.
- **Roboto Flex is bundled** (`res/font/roboto_flex.ttf`, Latin subset with the weight, width and
  optical-size axes, about 90 KB) for the wordmark and Display/Headline Large. It is a font file,
  not a library; its SIL Open Font Licence ships in `assets/licenses/`. Rejected: downloadable
  Google Fonts (needs a new dependency and network at first start) and the system font (Roboto
  has no width axis, so no wide wordmark).
- **No alpha libraries:** Material 3 1.4 (the stable one in our BOM) has no expressive loading
  indicator, wavy progress, button groups or shape library yet; those are only in 1.5 alphas. Player
  draws its own (`ui/common/Expressive.kt`) from plain Canvas and shapes, with the shape math in
  `logic/Blob.kt`. When they reach a stable release, Material's own versions can replace these.
- **Not in this change:** the "vibe pad" from the mockup (one square for mood and energy) needs a
  second value (energy or happiness) the generator doesn't have yet. It comes with the new tuning
  options; until then mood and familiarity use the new thick sliders. Order stays in Settings.

## 2026-11-22: "Known" tracks come from the whole ListenBrainz play history

ListenBrainz keeps only a user's top 1 000 recordings in its statistics, so tracks played a few
times counted as new. The owner chose to read every play instead (`GET /1/user/{name}/listens`,
1 000 a call) and keep the set of tracks on the phone (`noBackupFilesDir/listen_history.json`).

- **Cost:** about 100 calls for 100 000 plays at one call a second, once; later reads fetch only
  newer plays (usually one call) at most every 6 hours. The read runs in the background and a set
  never waits for it; while the first read runs, other ListenBrainz calls share the same pace.
- **Kept:** artist and title keys only, not when each play happened. Cleared by Clear cache.
- Rejected: raising Player's 1 000 limit on the statistics (ListenBrainz itself stops there).

## 2026-11-22: Spotify's streaming history is imported on ListenBrainz, not in the app

The plan was to import Spotify's Extended Streaming History ZIP on the phone. ListenBrainz now
imports that ZIP itself (listenbrainz.org → Settings → Import), and Player already reads the user's
ListenBrainz top artists and top recordings, so the owner chose to use that instead. Settings and
the README now point to the importer for both Last.fm and Spotify history.

- Rejected: parsing the ZIP in the app (a second copy of the history on the phone, more code and
  more stored personal data for the same result).

## 2026-11-22: Sets are built from ListenBrainz and MusicBrainz; Last.fm is removed

The engine now reads all music data through `MusicSource` (`data/brainz/BrainzMusic.kt`), and
Last.fm's client, settings and texts are gone. Only a Spotify Client ID is needed to start.

- **Genres:** MusicBrainz has no chart of the most used tags, so the picker offers a fixed list of
  40 genres (`logic/Genres.kt`); typing any other genre still works. Artists for a genre come from
  a MusicBrainz tag search. Last.fm's tag top tracks have no counterpart, so tracks come only from
  each artist's most listened recordings on ListenBrainz.
- **Candidate artists follow the length** (`GenerationEngine.artistBudget`): enough to fill the set
  at 3 tracks per artist with a fifth to spare, so 100 for the default 250 tracks, between 40 and
  170. Each new artist costs about two calls at one a second, so a first 250-track set from new
  artists takes a few minutes. Answers are cached (artist IDs 180 days, the rest 30 days), and IDs
  found in similar-artist and genre answers are kept, so later sets need few calls.
- **Name to ID:** an artist picked from the search keeps its MusicBrainz ID. Other names take the
  exact name match among MusicBrainz results with a score of at least 90, otherwise the top
  result; a miss is cached too.
- **Familiarity scale:** ListenBrainz has far fewer listeners than Last.fm, so the listener
  count's log scale now runs from 10 (new) to 100 000 (a hit).
- **Old data:** at start, Player removes the Last.fm key, username and cache files older versions
  stored.
- Rejected: keeping Last.fm as a fallback for genre charts (a second key to set up for little gain).

## 2026-11-22: A new, named playlist for every set, filled in one go

The owner wants every run to make a new playlist instead of replacing "Player", with all tracks at
once, and to choose its name. This replaces the "one reused playlist" and "rolling" choices below.

- **One new private playlist per set.** The saved playlist ID and `PlaylistMemory` are gone. The
  start dialog suggests a name (`logic/PlaylistName.kt`: up to two genres, or else artists, a word
  for a calm or upbeat mood, and the date) that the user can edit; an empty name can't be sent.
- **All at once, no batches.** The batches existed so Spotify searches (one per track) would be
  spread over the listening session to go easy on the Development Mode quota. Now every track is
  looked up first, and only then is the playlist made and filled, in calls of 100 because that is
  Spotify's limit per call, so it appears complete. The cost: a 250-track set does up to 250
  searches before playback starts (cached matches cost none). The "Fill the playlist" setting is
  removed.
- Rejected: making the playlist first and filling it while searching (it would show up half
  empty in Spotify).

## 2026-11-22: Player moves from Last.fm to ListenBrainz, MusicBrainz and ReccoBeats

The owner chose to drop Last.fm. History, taste and similar artists will come from
**ListenBrainz** (the owner imports their Last.fm scrobbles there once and links Spotify so new
plays keep arriving), artist IDs and genres from **MusicBrainz**, and sound from **ReccoBeats**
(already used for tempo and energy). Spotify keeps the library, top items and recent plays.

- MusicBrainz allows **one call a second** per client and ListenBrainz 30 per 10 seconds, so they
  share `BrainzHttp` with a `TokenBucket` each (0.9 and 2 a second since the artist-radio change), wait on 429/503 (`X-RateLimit-Reset-In`,
  `Retry-After`) and send `Player/<version> ( https://github.com/wimvdon/player )` as User-Agent,
  which both ask for. A repository link instead of an email, so no personal address is in the app.
- Reading ListenBrainz needs only a username. A user token is optional (higher rate limits).
- ListenBrainz works with MusicBrainz IDs, not names, so names go through MusicBrainz first.
- Done in steps: first the clients with tests, then the engine switch that removes Last.fm
  (entry above). Importing Spotify's extended streaming history moved to ListenBrainz (entry at the top).
- Rejected: keeping Last.fm for history (the ListenBrainz importer covers it) or as a fallback
  for genres (more code and settings; can be added later if MusicBrainz tags prove too thin).

## 2026-11-22: Player fills a Spotify playlist instead of the play queue

On the owner's phone, App Remote's connect never answered, so nothing could be queued. The
owner chose to use a playlist instead (the brief had ruled playlists out). Player now puts each
set in **one private playlist called "Player"**, reused for every set (its contents are replaced),
so the library doesn't fill up with playlists. It's made with `POST /me/playlists` and filled with
`PUT`/`POST /playlists/{id}/items` (the endpoints Spotify kept for Development Mode apps in
February 2026), then started with `PUT /me/player/play`; when no Spotify device is active, Player
opens the playlist in the Spotify app so the user presses play.

- **Rolling** mode adds 10 tracks whenever less than 40 minutes of the playlist are left, counted
  from when it started (track lengths come from the Spotify search). It doesn't read what's
  playing, so it costs no extra calls; after a long pause it simply runs further ahead.
- **App Remote, its AAR and Gson are removed.** The scopes change to `playlist-modify-private`,
  `playlist-modify-public`, `user-modify-playback-state`, `user-top-read` and `user-library-read`;
  a sign-in from 0.1.0 (other scopes) counts as signed out, so the app asks to connect again.
- Rejected: a new playlist per set (clutters the library), and polling "currently playing" to
  follow the listener (extra Web API calls against the quota).

## 2026-11-22: Player: libraries, storage and how the brief was adapted

The owner approved network access, the notification and foreground-service permissions, and the
libraries the brief needs. Choices made while building:

- **Libraries:** OkHttp, kotlinx.serialization, Coil (covers), Material icons, lifecycle
  ViewModel/service, DataStore, Spotify Auth (Maven Central) and Spotify App Remote 0.8.0 (an AAR
  in `app/libs`: Spotify only publishes it on GitHub) with Gson, which App Remote needs. Same set
  as Noter where possible. Rejected: **Hilt** (a hand-written `AppGraph` is enough at this size),
  **Retrofit** (a few hand-written calls on OkHttp), **Room** (caches are small JSON files in
  `noBackupFilesDir`; Room would add KSP and a schema for key-value data) and
  **EncryptedSharedPreferences** (deprecated; values are encrypted with an Android Keystore key
  and kept in a DataStore that isn't backed up).
- **minSdk stays 31**, not the brief's 26 (see the template baseline).
- **Spotify searches happen while queueing**, not while generating: a 250-track preview costs no
  Spotify calls, and rolling mode spreads the searches over the listening session. Covers show in
  the preview only for tracks matched earlier.
- **"Known" from Last.fm uses `user.getTopTracks`** (up to 3 000 tracks, cached a day) instead of
  `track.getInfo` per track, which would be hundreds of calls per set.
- **Quota pause:** after `QUOTA_EXCEEDED` all Web API calls stop for 6 hours (Spotify doesn't say
  how long); the debug screen can lift it early.
- **Foreground service type `specialUse`**: the app doesn't play audio itself, it keeps another
  app's queue filled, which fits no other type.
- **Colours:** Last.fm red as seed, fidelity primary roles with neutral surfaces (see DESIGN.md,
  "This app").

## 2026-11-22: Player: app identity, shared debug key, bring-your-own API keys

The app is **Player**, application ID `to.wim.player` (debug builds: `to.wim.player.debug`).
It builds play queues from Last.fm data and plays them through Spotify.

Spotify only lets an Android app sign in when its package name and signing fingerprint (SHA-1)
are registered in the Spotify developer app. CI used to sign each debug APK with a new random
key, so the fingerprint changed every build. Debug builds now use one fixed debug key: the file
`debug.keystore` in the project root (never committed), which CI decodes from the
`DEBUG_KEYSTORE_BASE64` secret. Its password is Android's standard `android`, because the key
only signs test builds. Releases keep their own key. Rejected: signing debug builds with the
release key (the release key would then be handed to every pull request's CI run).

Users enter their own Spotify Client ID and Last.fm API key in the app's settings, with short
instructions and links to both developer sites. Nothing is built into the APK. Spotify's
Development Mode limits each developer app to a few users, so one shared key could never serve
other people anyway; with their own keys anyone can use the app, and no key ends up in the
public repository or its APKs. Rejected: keys from `local.properties` baked in at build time.

## 2026-11-01: Claude Code sessions don't build the APK before pushing

Sessions ran `./gradlew testDebugUnitTest lintDebug assembleDebug` before every push, and CI then
runs the same and more. The APK built in the session was never used: the owner installs the one
from CI. Sessions now run only the unit tests and lint, which catch nearly every problem and
compile the app's code and resources. CI still builds the debug and release APKs, so a packaging
problem still turns the pull request red. Rejected: no local checks at all (every test or lint
failure would then cost a CI round trip).

## 2026-10-17: Design review across the apps: one way to reorder, readable widths, shared wording

A Material 3 review of every app built from this template found the same things done in different ways. Lists in the user's own order used four controls (drag handles, a long press, chevrons and arrows), even within one app; navigation bars mixed filled and outlined icons differently; most lists stretched across tablets although `DESIGN.md` asked for about 640dp; English said "Log out" next to "Sign in" and "Dismiss" where other apps said "Close"; and one app removed a workspace (with its presets) without asking. New shared rules in `DESIGN.md`: "Reordering" (a drag handle at the end of the row, `Reorder.kt`; a long press only for grids of cards), filled selected and outlined unselected navigation icons, `Modifier.readableWidth()` (`Layout.kt`), and wording for Close, Done, Sign out, Reorder and Move up / Move down. The two new kit files are copied into every app (Snoozer, without lists, takes only `Layout.kt`). The template now ships the same six languages as the apps. Rejected: up and down arrows for short lists (easier to build, but a second way to do the same thing) and a long press on list rows (hidden: nothing on screen says the row can move).

## 2026-10-17: Help text only where the screen doesn't speak for itself

The owner found the apps over-explanatory. A scan of every hint, summary and help paragraph removed text that restated a label, described standard controls (drag handles, tapping a row) or explained the app's design, and shortened the rest to what the screen can't show: consequences, where data goes, unfamiliar terms. `DESIGN.md` ("Wording") now says so, and the Dynamic color supporting text is one shared phrase without the app's colour name. Rejected: removing all help text (consequences and privacy notes are information, not decoration) and tooltips or info buttons instead (hidden text people rarely open, and more to translate).

## 2026-10-16: One empty state for every app, setup as a full-screen state

A second Material 3 audit found empty screens drawn three different ways: an icon in a tinted circle with a title (Tracker for Simkl), a title and text whose icon didn't show (RSS Reader), and a grey icon with one line of text (Noter for Notion). Apps that can't work until something is set up showed it differently too: Time Tracker for Notion put a small banner above a disabled timer, Noter filled the screen, Snoozer did both. Material 3 asks an empty state to say what it means and offer the next useful step, so the shared kit gained `ui/theme/EmptyState.kt` and `DESIGN.md` a section "Empty and setup states": one centred layout for empty, no-result, failed and setup-needed screens, with setup-needed replacing the screen's content. The same audit added two smaller rules: button labels name the action in as few words as possible ("Gezien", not "Markeren als gezien"), and rows that scroll sideways keep one height. Rejected: illustrations per screen (more to draw and translate for each app, and Material 3 warns against decoration that hides the next step) and keeping the banner for setup (easy to miss, and it leaves a screen full of controls that do nothing).

## 2026-10-16: Settings are one list; top-level navigation is a docked bar

An audit of the apps against Material 3 found two structural problems. Settings were a stack of collapsed cards, so even a single switch took two taps to find and every section was boxed in a card, which Material 3 lists as an anti-pattern. Settings are now one list grouped by section headers, with `ListItem` rows (switches apply at once) and only setup instructions folded away (`SettingsKit.kt`: `SettingsSection`, `SwitchSetting`, `SettingItem`, `ExpandableSetting`, `SettingsBlock`). Some apps put their top-level sections in a floating pill bar; Material 3 Expressive reserves floating toolbars for actions on the current screen, and keeps the navigation bar docked for destinations, so the shared rules now require the docked `NavigationBar`. `DESIGN.md` gained a "Navigation and structure" section. Rejected: a settings home page that links to sub-pages (more taps for apps with a handful of settings) and keeping the collapsible cards with everything expanded (still a card per section).

## 2026-10-15: Android's backup copies settings only, from an allow list

Users expect their settings back after a reset or on a new phone, and Android does that for free with its own backup (Google account) and phone-to-phone transfer. The template now lists exactly one file, the DataStore `backed_up_settings`, in `data_extraction_rules.xml`; everything else stays on the phone. An allow list means a file added later (a sign-in token, device state) is never backed up by accident, and `BackupRulesTest` fails if the rules drift. Rules for what goes where are in `docs/architecture.md`, "Backup". Rejected: backing up everything with exclusions (one forgotten exclusion leaks a token into the backup), switching backup off (users lose their settings) and our own backup to Google Drive (needs network access, sign-in and more code for what Android already does).

## 2026-10-15: Phone testing doesn't block merging

The owner can't review code and can't always test a pull request on the device right away, so pull requests waited for a manual test that adds little over CI. Green CI (unit tests, lint, builds) is now enough to merge; the owner tests afterwards with the steps from the pull request and always before a release, and problems are fixed in a new pull request. Merging to `main` does not reach users: releases stay manual. Rejected: keeping the phone test as a merge gate (pull requests pile up and go stale).

## 2026-10-15: Code is "all rights reserved" by default

Without a `LICENSE` file the legal default is already all rights reserved, but that isn't visible and is easy to misread for a public repository. The template now ships an explicit all-rights-reserved `LICENSE`, so every app starts strict and the owner opts in to sharing. Switching to a permissive licence (MIT or Apache-2.0) is a one-file swap, described in the README. Rejected: a commented-out alternative inside `LICENSE` (GitHub and licence scanners read the whole file, so it would be misdetected) and starting with MIT (opening up later is easy, taking it back for code already shared is not).

## 2026-10-14: Shared design rules have their master copy in the template

`DESIGN.md` was kept identical in the three sibling apps by hand, with no single place to start a change, and the copies had drifted (Snoozer was missing the Loading section; the Tracker for Simkl icon row was out of date in two apps). The android-app template (`wimvdon/android-app`) now holds the master copy; each app keeps a full copy so Claude sessions can read the rules without opening another repository, and only its "This app" section is its own. Rejected: keeping the file only in the template (sessions in an app would have to fetch it every time) and a shared library for the design kit (a new dependency and more setup than three small apps need). The design kit files (`ui/theme/`) stay in the apps.

## 2026-10-13: Template baseline

- **Language/UI:** Kotlin + Jetpack Compose + Material 3, single `app` module. This is Google's
  recommended, mainstream stack with the most documentation and examples.
- **Tool versions (latest stable on this date):** Gradle 9.8.1, Android Gradle Plugin 9.4.1
  (uses its built-in Kotlin support, so no separate `kotlin-android` plugin), Kotlin 2.4.21
  (Compose compiler plugin), Compose BOM 2026.09.00. Dependabot keeps these current.
- **minSdk 31 (Android 12).** The owner installs apps on their own, recent phone, so older
  Android versions aren't worth the extra code and testing. Android 12 brings Material You dynamic
  colour (no fallback code path), the modern splash screen and per-app language settings. It still
  covers the large majority of active devices. Lower it only if someone with an older phone needs
  the app.
- **compileSdk / targetSdk 37 (Android 17)**, the latest stable API level. The minor SDK release
  37.2 exists but isn't needed by anything yet.
- **Java 17 bytecode, JDK 21 to build.** JDK 21 is what Claude Code cloud sessions provide, so local
  checks match CI exactly.
- **Version numbers:** release `X.Y.Z` → `versionCode = X*10000 + Y*100 + Z`, passed in by the release
  workflow. All other builds use `git describe --tags --long` (e.g. `1.2.0-3-gabc1234`), falling back to
  `0.0.0-dev`, so a dev build can never be mistaken for a release. Version logic lives in
  `scripts/release_version.py` (unit tested) and `app/build.gradle.kts`.
- **Debug builds use `applicationIdSuffix = ".debug"`** so a pull request's APK installs next to the
  released app instead of conflicting with it.
- **Releases are GitHub Releases with a signed APK**, published by `gh release create` (preinstalled
  on runners) instead of a third-party action, which means one fewer dependency with write access.
  The R8 mapping file is attached to each release.
- **Signing secrets** are base64 and decoded by a tolerant, tested script (`scripts/decode_keystore.py`)
  that never prints the secret. The key password is checked with a small Java program
  (`scripts/VerifySigningKey.java`), because `keytool` ignores the key password of PKCS12 keystores and
  the build would otherwise fail later with an unclear error.
- **Caching:** `gradle/actions/setup-gradle` with `cache-provider: basic` (MIT licensed). The default
  "enhanced" provider is closed source under separate terms of use.
- **CI artifacts:** the debug APK is uploaded unzipped (`archive: false`) so it can be installed on a
  phone straight from the download.
- **Languages:** English (default), Dutch, French, German, Spanish and Italian, the languages every app built from this template ships. `generateLocaleConfig`
  makes the app appear under Android's per-app language settings.
- **Persistence:** DataStore when needed (version in the catalog, not added as a dependency yet). No
  network, analytics or crash-reporting libraries.
- **No instrumented/UI tests** yet: they need an emulator in CI (slow, flaky). Logic is kept in plain
  Kotlin and unit tested instead. Manual device checks are listed in `docs/testing.md`.
- **No lint baseline:** the template has zero lint issues.
- **Play Store upload** is prepared but commented out in `release.yml` until a Play Console app and
  service account exist.
