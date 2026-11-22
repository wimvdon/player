# Privacy policy

_Last updated: 2026-11-22 (version 0.1.1)_

Player has no server of its own and no analytics, advertising or crash reporting. It talks
directly to the services below, using **your own** developer keys.

## What goes where

- **Last.fm** (`ws.audioscrobbler.com`): the artists and genres you pick, artist names to look up
  similar artists, top tracks and tags, and (if you enter it) your Last.fm username to read your
  most played tracks. Sent with your Last.fm API key.
- **Spotify** (`api.spotify.com`, `accounts.spotify.com`): sign-in, searches for the tracks
  being added, your top tracks, top artists and saved tracks (to spot songs you know), and a new
  private playlist for each set, with the name you confirm, which Player creates, fills and starts.
  Player changes no other playlist. Sent with your Spotify Client ID and
  sign-in token.
- **ReccoBeats** (`api.reccobeats.com`), **only if you switch it on** in the settings: Spotify
  track IDs, to get tempo and energy. No key or account is involved.
- Album covers are downloaded from Spotify's image servers.

## What stays on your phone

- **Encrypted, never backed up:** your Spotify Client ID, Last.fm API key and Spotify sign-in.
  They are encrypted with a key from the Android Keystore.
- **Not backed up:** a cache of Last.fm answers (kept 30 days), your Spotify library and charts
  (kept a day), and the Spotify tracks songs were matched to. Clear it in Settings → API calls and
  cache → Clear cache.
- **Backed up by Android** (to your Google account, if you turned that on, and when moving to a new
  phone): your settings (theme, playlist order, Last.fm username) and the generator's last input
  (sliders, genres, artists, length).

## Permissions

- **Internet**: to reach the services above.
- **Notifications** and a **foreground service**: a notification shows the progress while Player
  fills the playlist, with a Stop button.

Uninstalling Player deletes everything it stored on the phone; the playlists it made stay in
your Spotify account until you delete them there. To remove Player's access to your Spotify account,
open spotify.com → Account → Apps and remove your developer app.

Questions: open an issue in this repository.
