# b2-player

An Android music player for your own library stored in a Backblaze B2 bucket. The music sits in the
bucket as plain files (originals, no transcoding or encryption) and the player streams it straight
from B2. There is no server: the phone talks to B2 itself, and the catalog, covers and lyrics are
prepared in advance by a script on the computer that holds the library.

```
<bucket>/
  music/<same as the music folder>  originals: FLAC, MP3, M4A (ALAC), ...
  catalog.json.gz          catalog built from tags
  covers/<album-id>.jpg    512 px covers
  lyrics/<album-id>.json   lyrics from tags
  state/likes.json         likes (differences from the catalog's flags)
  state/playlists.json     playlists
  state/history.tsv        listening history: time and track id
  state/tags.json          tag edits made on the phone, not yet written to files
  logs/<date>.log          phone log after errors
```

With the B2 lifecycle rule "Keep all versions" the bucket doubles as a music backup: B2 keeps old
versions of every file.

## Library computer: upload and catalog

You need rclone with a remote for the bucket (a Read and Write key, e.g.
`rclone config create b2music b2 account … key …`), `python-mutagen` and `python-pillow`.
The scripts take their paths from environment variables:

| Variable | Meaning |
|---|---|
| `B2_PLAYER_REMOTE` | the bucket in rclone, e.g. `b2music:my-music` |
| `B2_PLAYER_MUSIC` | the music folder, `~/Music` by default |
| `B2_PLAYER_OUT` | where catalog builds and reports go, `~/.cache/b2-player` by default |

```bash
tools/sync-music.sh --dry-run          # show what would change
tools/sync-music.sh                    # full sync of the music folder -> music/
tools/sync-music.sh -- "rock/Artist"   # only these subfolders
tools/publish-catalog.sh               # catalog + covers, then check that every path exists in the bucket
tools/apply-tags.py                    # tag edits from the phone: what would change in the files
tools/apply-tags.py --apply            # write them to the files and drop them from state/tags.json
```

`sync-music.sh` does not start while a restic backup is running on the machine, so the two don't
compete for disk and network; `WAIT_FOR_RESTIC=1` makes it wait instead.

Tag editing: "Track info" in a track's menu → Edit. The phone puts edits into `state/tags.json`
(`{"tracks": {"<path>": {"GENRE": "…"}}}`, an empty value removes the tag) and shows them over the
file's own tags. `apply-tags.py --apply` writes them to the files with mutagen and prints the command
`sync-music.sh -- <folders> && publish-catalog.sh`. When a file's size changes in the catalog, the
phone drops its old copy from the stream cache.

`make_catalog.py`:
- groups tracks into albums by folder (`CD1`/`Disc 2` merge into the parent), and within a folder by the album tag;
- fixes cp1251 tags that were written as latin1;
- splits "one file + CUE" rips into tracks (`start` + `duration`, clipped in the player);
- the cover is a cover/folder/front file in the folder, otherwise the first image, otherwise the embedded one;
- if the `--favourites` folder exists (`~/Favourite Music` by default), marks tracks that are also in it
  as liked and writes the ones it can't match to `unmatched-favourites.txt`. Handy if favourites used to be
  copied into a separate folder; that folder is not uploaded.

## Phone

Kotlin + Jetpack Compose + Media3 (ExoPlayer, `MediaSessionService` for background playback and the notification).
ALAC and anything else the platform can't decode goes through `org.jellyfin.media3:media3-ffmpeg-decoder`.

- B2 access: `b2_authorize_account` (native API), then GET `…/file/<bucket>/<key>` with the token.
- Stream cache: 4 GB SimpleCache, LRU; the cache key is a `b2://` URI, independent of the token.
- Offline: "Offline" on an album or "Download" in Liked downloads whole files (WorkManager).
- Likes live in the bucket in `state/likes.json` as differences from the catalog's flags (`added`/`removed`).
  Every tap on the heart is uploaded right away; on start the app pulls the file from the bucket unless the
  phone has changes that aren't uploaded yet. That's why the phone's key needs write access.

- Playlists, `.m3u` import (e.g. from Musicolet): tracks are matched by file name and folder, then by `#EXTINF` "artist - title".
- A play counts after half the track or 4 minutes (tracks shorter than 30 s don't count); the same rule applies
  to history and Last.fm scrobbling; likes and unlikes go to Last.fm as love/unlove. The Last.fm API key and
  login are entered in settings, the password is not stored.

- Assistant (the ✦ button in the top bar): a chat with Claude, by text or voice. The Claude API key is entered in
  settings and requests go from the phone straight to the API. It finds and plays music and answers questions
  about listening history; changes to likes and playlists apply only after "Apply". The conversation lasts
  until the app is closed.

- Lyrics: from file tags (the catalog puts them in `lyrics/<album-id>.json`). The app goes to lrclib.net only when
  the tags have no synced lyrics, and saves what it finds to `found-lyrics/` in the bucket. `tools/fetch-lyrics.py`
  looks up synced lyrics on lrclib.net for the whole library (tracks without lyrics first, then ones with unsynced
  lyrics) and with `--apply` writes them into the tags together with what the phone found; without the flag it
  only searches and writes a `lyrics-report.txt` report to `B2_PLAYER_OUT`. An interrupted run resumes where it
  stopped. Afterwards run `tools/sync-music.sh && tools/publish-catalog.sh`.
  The translate button in lyrics: Claude (with the assistant's key) translates the song line by line into English
  or Russian, shown next to the original. Cached per track and language in `files/lyrics-translations`.
- Logs: `files/logs/app.log` in the app, rotated at 512 KB, ~1.5 MB at most. After errors they are copied
  to the bucket as `logs/<date>.log` (one version per day). To read them:
  `rclone cat <remote>:<bucket>/logs/<date>.log` or
  `adb logcat -s 'B2P/*'` (the same lines go to logcat; `run-as` only works with a debug build).

## Install

A ready arm64 APK (Android 10+) is in [Releases](../../releases/latest). On first launch, enter the B2 key's
keyID and applicationKey and the bucket name in settings. The key needs Read and Write access so that likes,
playlists and history can sync.

Build (JDK 21, Android SDK):

```bash
./gradlew assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
```

Install the release build: the debug build is several times slower (catalog parsing ≈1 s instead of
≈0.16 s, startup ≈470 ms instead of ≈200 ms). Both are signed with the debug key, so switching between
them keeps the app's data.

The version is `baseVersion.N` (e.g. 0.5.35), where N is the number of commits in `HEAD` and also the versionCode.
Every push to main publishes a `vX.Y.N` release on GitHub Releases. CI signs it with the debug key from the
`DEBUG_KEYSTORE` secret (the keystore in base64), so releases install over local builds. Bump `baseVersion` in
`app/build.gradle.kts` when shipping notable features. A local build from a branch with more commits than main gets a higher versionCode,
so the next release won't install over it until main catches up.
