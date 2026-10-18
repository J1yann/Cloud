<p align="center">
  <img src="docs/assets/cloud-banner.svg" alt="Cloud — Find your next favorite" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/J1yann/Cloud/actions/workflows/android.yml"><img src="https://github.com/J1yann/Cloud/actions/workflows/android.yml/badge.svg" alt="Android build" /></a>
  <img src="https://img.shields.io/badge/Android-6.0%2B-346E8B?style=flat" alt="Android 6.0 and newer" />
  <a href="https://github.com/J1yann/Cloud/releases/latest"><img src="https://img.shields.io/github/v/release/J1yann/Cloud?color=346E8B" alt="Latest release" /></a>
  <a href="https://github.com/J1yann/Cloud/releases"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2FJ1yann%2FCloud%2Fmain%2Fdocs%2Fdownloads.json" alt="APK downloads across published releases" /></a>
</p>

<p align="center">Listen to YouTube Music without signing in. Discover songs, build playlists, and keep listening with Cloud.</p>
<p align="center"><a href="https://github.com/J1yann/Cloud/releases/latest"><strong>Download the APK</strong></a> · <a href="https://github.com/J1yann/Cloud/issues">Report an issue</a></p>

## A look inside Cloud

<p align="center">
  <img src="docs/screenshots/home.png" alt="Cloud Home with discovery and new releases" width="23%" />
  <img src="docs/screenshots/player.png" alt="Full-screen player with album artwork" width="23%" />
  <img src="docs/screenshots/lyrics.png" alt="Synchronized lyrics view" width="23%" />
  <img src="docs/screenshots/settings.png" alt="Cloud appearance and playback settings" width="23%" />
</p>

## Your music, within reach

| Discover | Listen | Make it yours |
| --- | --- | --- |
| Discovery shelves, new releases, charts, moods, and genres | Background playback, queues, shuffle, and repeat | Likes, personal playlists, and listening history |
| Search songs, artists, albums, and playlists | Related songs keep playing when your queue ends | Local music, AMOLED mode, and a sleep timer |

Start listening without an account, sign-in, or separate server setup. Your library
stays on your device. Cloud fetches catalog data and resolves audio streams on the
device. Availability depends on YouTube and your network.

## Install

Get the signed APK from [GitHub Releases](https://github.com/J1yann/Cloud/releases/latest)
and install it on **Android 6.0 or newer**. Each release includes a SHA-256 checksum.
Updates use the same signing key, so you can install them over an existing copy.
The app package is `cloud.music`.

## How Cloud works

Cloud browses YouTube Music’s catalog directly for songs, albums, artists,
playlists, and discovery shelves. NewPipe Extractor resolves the selected video’s
audio on the device, and Media3 handles playback, queues, and notifications.
Track IDs stay consistent from search through playback; Cloud does not choose a
replacement recording by title.

Word-by-word lyrics highlight in time with the music when word timestamps are
available. Line-synchronized and plain-text lyrics provide a fallback. Tap a timed
line to seek, or scroll freely through the lyrics.
Cloud uses undocumented service interfaces, so upstream changes can affect browsing
or playback. This app does not provide access to recordings unavailable to your connection.

## Credits

Cloud builds on [PonyMusic by wangchenyan](https://github.com/wangchenyan/ponymusic).
Discovery and player presentation draw inspiration from
[SimpMusic](https://github.com/maxrave-dev/SimpMusic).
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) provides audio
extraction, [Media3](https://github.com/androidx/media) handles playback, and
[BetterLyrics](https://github.com/better-lyrics/better-lyrics) and
[LRCLIB](https://lrclib.net) provide lyrics.

Cloud is an independent app and is not affiliated with YouTube or Google.
See [third-party credits](THIRD_PARTY_NOTICES.md) and [LICENSE](LICENSE).
