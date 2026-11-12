#!/usr/bin/env python3
"""Write tag edits made in the app into the files in ~/Music.

The app keeps edits in state/tags.json in the bucket:
  {"version": 1, "tracks": {"<path under music/>": {"GENRE": "Post-rock", "MOOD": ""}}}
An empty value removes the tag. Tag names are Vorbis comment names (as FLAC has them);
for MP3 and M4A they go through mutagen's easy interface, unknown names as TXXX / freeform atoms.

  tools/apply-tags.py           # show what would change, touch nothing
  tools/apply-tags.py --apply   # write the files, then drop what was written from state/tags.json

After --apply, upload the changed folders and rebuild the catalog:
  tools/sync-music.sh -- "<folder>" ... && tools/publish-catalog.sh
"""
import argparse
import json
import os
import subprocess
import sys

import mutagen
from mutagen.easyid3 import EasyID3
from mutagen.easymp4 import EasyMP4Tags

REMOTE = os.environ.get("B2_PLAYER_REMOTE", "b2music:buyolitsez-music")
MUSIC = os.environ.get("B2_PLAYER_MUSIC", os.path.expanduser("~/Music"))
KEY = "state/tags.json"
# Names the app shows that mutagen's easy ID3 / MP4 interfaces call differently.
EASY_NAMES = {"LABEL": "organization", "ENCODER": "encodedby"}


def fetch():
    r = subprocess.run(["rclone", "cat", f"{REMOTE}/{KEY}"], capture_output=True, text=True)
    if r.returncode != 0:
        if "not found" in r.stderr.lower():
            return {}
        sys.exit(f"rclone cat {KEY} failed: {r.stderr.strip()}")
    return json.loads(r.stdout or "{}").get("tracks", {})


def easy_key(f, name):
    k = name.lower()
    if isinstance(f.tags, EasyID3):
        k = EASY_NAMES.get(name, k)
        if k not in EasyID3.Get:
            EasyID3.RegisterTXXXKey(k, name)
    elif isinstance(f.tags, EasyMP4Tags) and k not in EasyMP4Tags.Get:
        EasyMP4Tags.RegisterFreeformKey(k, name)
    return k


def changes(f, edits):
    """(key, old list, new list or None to delete) for the edits that change something."""
    out = []
    for name, value in edits.items():
        k = easy_key(f, name)
        old = list(f.tags.get(k, [])) if f.tags is not None else []
        if value == "":
            if old:
                out.append((k, old, None))
            continue
        # The app joins repeated tags with "; ": split back only where the file has several values.
        new = [v.strip() for v in value.split("; ")] if len(old) > 1 else [value]
        if [str(v) for v in old] != new:
            out.append((k, old, new))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", action="store_true", help="write the files and update state/tags.json")
    a = ap.parse_args()

    tracks = fetch()
    if not tracks:
        print("No tag edits waiting.")
        return
    written, folders, saved = {}, set(), 0
    for path, edits in sorted(tracks.items()):
        full = os.path.join(MUSIC, path)
        if not os.path.exists(full):
            print(f"! {path}: not in {MUSIC}, skipped")
            continue
        f = mutagen.File(full, easy=True)
        if f is None:
            print(f"! {path}: mutagen can't open it, skipped")
            continue
        if f.tags is None:
            f.add_tags()
        try:
            ch = changes(f, edits)
        except Exception as e:
            print(f"! {path}: {e}, skipped")
            continue
        print(path)
        if not ch:
            print("    already as edited")
        for k, old, new in ch:
            print(f"    {k}: {'; '.join(map(str, old)) or '—'} -> {'(removed)' if new is None else '; '.join(new)}")
        if a.apply:
            for k, _, new in ch:
                if new is None:
                    del f.tags[k]
                else:
                    f.tags[k] = new
            if ch:
                f.save()
                saved += 1
                folders.add(os.path.dirname(path))
            written[path] = edits

    if not a.apply:
        print("\nNothing written. Run with --apply to write these files.")
        return
    # Drop only what was written: edits made on the phone meanwhile stay for the next run.
    now = fetch()
    left = {p: e for p, e in now.items() if written.get(p) != e}
    if left != now:
        body = json.dumps({"version": 1, "tracks": left}, ensure_ascii=False, indent=1)
        subprocess.run(["rclone", "rcat", f"{REMOTE}/{KEY}"], input=body, text=True, check=True)
    print(f"\nWrote {saved} file(s); {len(left)} edit(s) left in {KEY}.")
    if folders:
        print("Now upload and rebuild the catalog:")
        print("  tools/sync-music.sh -- " + " ".join(f'"{d}"' for d in sorted(folders)) + " && tools/publish-catalog.sh")


if __name__ == "__main__":
    main()
