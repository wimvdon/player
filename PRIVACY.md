# Privacy policy

_Last updated: 2026-11-22_

Player has no server of its own and no analytics, advertising or crash reporting. It talks
directly to the services below, using **your own** Spotify developer key.

## What goes where

- **MusicBrainz** (`musicbrainz.org`): the artists and genres you pick and artist names, to find
  each artist's MusicBrainz ID, artists with a genre, and an artist's genres. No key or account is
  involved.
- **ListenBrainz** (`api.listenbrainz.org`, `labs.api.listenbrainz.org`): MusicBrainz IDs of
  artists, to get similar artists and their most played tracks, and (if you enter it) your
  ListenBrainz username, to read your most played artists and tracks and your full list of plays
  (so every song you've played counts as known). No key is sent; a ListenBrainz profile is public.
- **Spotify** (`api.spotify.com`, `accounts.spotify.com`): sign-in, searches for the tracks
  being added, your top tracks, top artists and saved tracks (to spot songs you know), and a new
  private playlist for each set, with the name you confirm, which Player creates, fills and starts.
  Player changes no other playlist. Sent with your Spotify Client ID and
  sign-in token.
- **ReccoBeats** (`api.reccobeats.com`), **only if you switch it on** in the settings: Spotify
  track IDs, to get tempo and energy. No key or account is involved.
- Album covers are downloaded from Spotify's image servers.

## What stays on your phone

- **Encrypted, never backed up:** your Spotify Client ID and Spotify sign-in.
  They are encrypted with a key from the Android Keystore.
- **Not backed up:** a cache of MusicBrainz and ListenBrainz answers (kept 30 days,
  artist IDs 180 days), your Spotify library and charts
  (kept a day), the Spotify tracks songs were matched to, and the list of tracks in your ListenBrainz
  play history (artist and title only, kept until you clear it or change the username). Clear it in
  Settings → API calls and cache → Clear cache.
- **Backed up by Android** (to your Google account, if you turned that on, and when moving to a new
  phone): your settings (theme, playlist order, ListenBrainz username) and the generator's last input
  (sliders, genres, artists, length).

## Permissions

- **Internet**: to reach the services above.
- **Notifications** and a **foreground service**: a notification shows the progress while Player
  fills the playlist, with a Stop button.

Older versions used Last.fm. Player no longer contacts it, and removes the Last.fm key, username
and cached answers it stored.

Uninstalling Player deletes everything it stored on the phone; the playlists it made stay in
your Spotify account until you delete them there. To remove Player's access to your Spotify account,
open spotify.com → Account → Apps and remove your developer app.

Questions: open an issue in this repository.
