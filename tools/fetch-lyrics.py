#!/usr/bin/env python3
"""Find time-synced lyrics on lrclib.net for the tracks in ~/Music and write them into the tags.

Tracks without lyrics go first, then tracks whose lyrics have no timestamps; tracks that already
have synced lyrics are left alone. Every answer is appended to ~/.cache/b2-player/lyrics.jsonl,
so a killed run continues where it stopped (lookups that failed on the network are retried).

  tools/fetch-lyrics.py                    # look lyrics up and keep them in the cache, touch nothing
  tools/fetch-lyrics.py --apply            # also write what was found into the files
  tools/fetch-lyrics.py --apply -- "rock/Swans" ...   # only these subfolders

--apply also writes the lyrics the phone found online (found-lyrics/ in the bucket) and removes
those files from the bucket once written. It waits while a restic backup runs.
Tracks with nothing found are listed in ~/.cache/b2-player/lyrics-report.txt.
After --apply, upload the changed files and rebuild the catalog:
  WAIT_FOR_RESTIC=1 tools/sync-music.sh && tools/publish-catalog.sh
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

import mutagen
from mutagen._vorbis import VCommentDict
from mutagen.apev2 import APEv2
from mutagen.id3 import ID3, USLT
from mutagen.mp4 import MP4Tags

from make_catalog import AUDIO_EXT, lyrics_of, read_tags

REMOTE = os.environ.get("B2_PLAYER_REMOTE", "b2music:buyolitsez-music")
MUSIC = os.environ.get("B2_PLAYER_MUSIC", os.path.expanduser("~/Music"))
CACHE = os.environ.get("B2_PLAYER_OUT", os.path.expanduser("~/.cache/b2-player"))
API = os.environ.get("B2_PLAYER_LRCLIB", "https://lrclib.net/api")
STAMP = re.compile(r"\[\d+:\d+(?:[.:]\d+)?]")


def is_synced(text):
    """Same rule as the app: at least three timestamped lines."""
    return sum(1 for line in text.splitlines() if STAMP.match(line.strip())) >= 3


def restic_running():
    return any(subprocess.run(c, capture_output=True).returncode == 0
               for c in (["pgrep", "-x", "restic"], ["pgrep", "-f", "restic/backup.sh"]))


def wait_for_restic():
    while restic_running():
        print("restic backup is running, waiting 5 min…", flush=True)
        time.sleep(300)


def tracks(only):
    """(path under MUSIC, has: none|plain) for every track that has no synced lyrics yet."""
    out = []
    for top in [os.path.join(MUSIC, d) for d in only] or [MUSIC]:
        for d, dirs, files in os.walk(top):
            dirs.sort()
            audio = sorted(f for f in files if os.path.splitext(f)[1].lower() in AUDIO_EXT)
            if len(audio) == 1 and any(f.lower().endswith(".cue") for f in files):
                continue  # one file with many tracks: no per-track tags to write
            for f in audio:
                p = os.path.join(d, f)
                text = lyrics_of(p)
                if not text or not is_synced(text):
                    out.append((os.path.relpath(p, MUSIC), "plain" if text else "none"))
    return sorted(out, key=lambda x: x[1] != "none")


def get_json(url):
    """Parsed body, None for 404; waits out 429s; other trouble raises so the track is retried next run."""
    req = urllib.request.Request(url, headers={"User-Agent": "b2-player-tools (github.com/buyolitsez/b2-player)"})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code != 429 or attempt == 5:
                raise
            retry = e.headers.get("Retry-After", "")
            time.sleep(int(retry) if retry.isdigit() else 5 * 2 ** attempt)


def lookup(t):
    """(status, text): synced / plain / instrumental / missing. Same matching as the app."""
    q = {"artist_name": t["artist"], "track_name": t["title"], "duration": round(t["duration"])}
    if t["album"]:
        q["album_name"] = t["album"]
    hit = get_json(f"{API}/get?{urllib.parse.urlencode(q)}")
    if not (hit and hit.get("syncedLyrics")):
        found = get_json(f"{API}/search?{urllib.parse.urlencode({'track_name': t['title'], 'artist_name': t['artist']})}") or []
        near = [o for o in found if abs((o.get("duration") or 0) - t["duration"]) < 5]
        hit = next((o for o in near if o.get("syncedLyrics")), None) or hit or (near[0] if near else None)
    if not hit:
        return "missing", None
    if hit.get("syncedLyrics"):
        return "synced", hit["syncedLyrics"]
    if hit.get("instrumental"):
        return "instrumental", None
    if hit.get("plainLyrics"):
        return "plain", hit["plainLyrics"]
    return "missing", None


def write_lyrics(full, text):
    """Replace the lyrics of one file (or its .lrc next to it, which the catalog reads first)."""
    lrc = os.path.splitext(full)[0] + ".lrc"
    if os.path.exists(lrc):
        with open(lrc, "w", encoding="utf-8") as fh:
            fh.write(text.strip() + "\n")
        return
    f = mutagen.File(full)
    if f is None:
        raise ValueError("mutagen can't open it")
    if f.tags is None:
        f.add_tags()
    t = f.tags
    if isinstance(t, ID3):
        t.delall("USLT")
        t.add(USLT(encoding=3, lang="eng", desc="", text=text))
    elif isinstance(t, MP4Tags):
        t["\xa9lyr"] = [text]
    elif isinstance(t, (VCommentDict, APEv2)):
        # Drop UNSYNCEDLYRICS too: the catalog takes whichever lyrics tag it meets first.
        for k in [k for k in t.keys() if k.lower() in ("lyrics", "unsyncedlyrics")]:
            del t[k]
        t["Lyrics" if isinstance(t, APEv2) else "LYRICS"] = text if isinstance(t, APEv2) else [text]
    else:
        raise ValueError(f"don't know where {type(t).__name__} keeps lyrics")
    f.save()


def phone_found():
    """{path: (lyrics, bucket key)} from found-lyrics/<hash>.json that the app uploads."""
    with tempfile.TemporaryDirectory() as tmp:
        r = subprocess.run(["rclone", "copy", f"{REMOTE}/found-lyrics", tmp], capture_output=True, text=True)
        if r.returncode != 0:
            print(f"! rclone copy found-lyrics failed: {r.stderr.strip()}")
            return {}
        out = {}
        for name in os.listdir(tmp):
            o = json.load(open(os.path.join(tmp, name), encoding="utf-8"))
            out[o["path"]] = (o["lyrics"], f"found-lyrics/{name}")
        return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", action="store_true", help="write found lyrics into the files in ~/Music")
    ap.add_argument("only", nargs="*", help="subfolders of ~/Music (default: all)")
    a = ap.parse_args()

    os.makedirs(CACHE, exist_ok=True)
    log_path = os.path.join(CACHE, "lyrics.jsonl")
    state = {}  # path -> last record; "written" records mark files already updated
    if os.path.exists(log_path):
        for line in open(log_path, encoding="utf-8"):
            try:
                o = json.loads(line)
            except ValueError:
                continue  # the line a kill cut short
            state[o["path"]] = {**state.get(o["path"], {}), **o}
    log = open(log_path, "a", encoding="utf-8")
    if log.tell() and open(log_path, "rb").read()[-1:] != b"\n":
        log.write("\n")  # finish the cut line so the next record starts clean

    def record(o):
        state[o["path"]] = {**state.get(o["path"], {}), **o}
        log.write(json.dumps(o, ensure_ascii=False) + "\n")
        log.flush()
        os.fsync(log.fileno())

    todo = tracks(a.only)
    print(f"{len(todo)} tracks without synced lyrics "
          f"({sum(1 for _, h in todo if h == 'none')} without any lyrics)", flush=True)

    # 1. Look up everything not asked yet.
    errors = 0
    pending = [(p, h) for p, h in todo if p not in state]
    for i, (path, has) in enumerate(pending, 1):
        t = read_tags(os.path.join(MUSIC, path))
        if not t or not t["artist"] or not t["title"]:
            record({"path": path, "has": has, "status": "no-tags"})
            continue
        try:
            status, text = lookup(t)
            errors = 0
        except Exception as e:
            errors += 1
            print(f"! {path}: {e}", flush=True)
            if errors >= 10:
                sys.exit("10 network errors in a row, stopping. Run again later to continue.")
            time.sleep(min(60, 2 ** errors))
            continue
        record({"path": path, "has": has, "status": status, "lyrics": text})
        print(f"[{i}/{len(pending)}] {status:12} {path}", flush=True)

    # 2. What would be written: synced ones, and plain text where the file had none.
    todo_paths = {p for p, _ in todo}
    write = {p: (o["lyrics"], None) for p, o in state.items()
             if p in todo_paths and not o.get("written")
             and (o["status"] == "synced" or (o["status"] == "plain" and o["has"] == "none"))}
    # The phone's finds win unless lrclib gave timestamps and the phone didn't; the rest are
    # redundant (or for files that are gone) and only get removed from the bucket.
    stale = []
    for p, (text, key) in (phone_found() if a.apply else {}).items():
        if p in todo_paths and (p not in write or is_synced(text) or not is_synced(write[p][0])):
            write[p] = (text, key)
        else:
            stale.append(key)

    # 3. Report.
    def by(status, has=None):
        return sorted(p for p, o in state.items() if p in todo_paths and o["status"] == status and has in (None, o["has"]))
    missing_none = by("missing", "none")
    still_plain = sorted(p for p, o in state.items() if p in todo_paths and o["has"] == "plain" and o["status"] != "synced")
    report = os.path.join(CACHE, "lyrics-report.txt")
    with open(report, "w", encoding="utf-8") as fh:
        for title, paths in (("No lyrics found", missing_none),
                             ("Has plain lyrics, no synced ones found", still_plain),
                             ("Instrumental (per lrclib)", by("instrumental")),
                             ("No artist or title tag", by("no-tags"))):
            fh.write(f"## {title} ({len(paths)})\n" + "".join(p + "\n" for p in paths) + "\n")
    size = sum(os.path.getsize(os.path.join(MUSIC, p)) for p in write)
    print(f"\nTo write: {len(write)} files ({size / 2**30:.1f} GiB to upload again), "
          f"{sum(1 for x, _ in write.values() if is_synced(x))} of them synced. "
          f"Not found: {len(missing_none)} without lyrics, {len(still_plain)} still without timestamps. "
          f"List: {report}")
    if not a.apply:
        print("Nothing written. Run with --apply to write the files.")
        return

    # 4. Write.
    done, used_keys = 0, stale
    for p, (text, key) in sorted(write.items()):
        wait_for_restic()
        try:
            write_lyrics(os.path.join(MUSIC, p), text)
        except Exception as e:
            print(f"! {p}: {e}", flush=True)
            continue
        record({"path": p, "written": True})
        if key:
            used_keys.append(key)
        done += 1
    for key in used_keys:
        subprocess.run(["rclone", "deletefile", f"{REMOTE}/{key}"], check=False)
    print(f"\nWrote lyrics into {done} files. Now upload and rebuild the catalog:\n"
          "  WAIT_FOR_RESTIC=1 tools/sync-music.sh && tools/publish-catalog.sh")


if __name__ == "__main__":
    main()
