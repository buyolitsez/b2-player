#!/usr/bin/env python3
"""Build catalog.json.gz and cover thumbnails for the b2-player app.

Reads tags from the music library (FLAC, MP3, M4A/ALAC, ...), groups tracks
into albums by folder, splits single-file CUE rips into tracks, marks tracks
that also exist in the old "Favourite Music" folder as liked, and writes:

  OUT/catalog.json.gz   the catalog the app downloads
  OUT/covers/<id>.jpg   512px album thumbnails
  OUT/unmatched-favourites.txt  favourites with no match in the library

Paths in the catalog are relative to the library root; in the bucket they
live under music/.
"""
import argparse
import collections
import datetime
import gzip
import hashlib
import io
import json
import os
import re
import sys

import mutagen
from PIL import Image

AUDIO_EXT = {".flac", ".mp3", ".m4a", ".ogg", ".opus", ".wav", ".ape", ".wv", ".aac"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
DISC_DIR = re.compile(r"^\s*(cd|disc|disk|диск)\s*\d+\s*$", re.I)
COVER_NAMES = ("cover", "folder", "front", "обложка")
THUMB = 512


def fix_mojibake(s):
    """Repair cp1251 text that was stored as latin1 (common in old Russian ID3 tags)."""
    try:
        raw = s.encode("latin1")
    except UnicodeEncodeError:
        return s
    letters = [c for c in s if c.isalpha()]
    high = [c for c in letters if ord(c) >= 0xC0]
    if not letters or len(high) * 2 < len(letters):
        return s
    try:
        fixed = raw.decode("cp1251")
    except UnicodeDecodeError:
        return s
    cyr = sum(1 for c in fixed if "Ѐ" <= c <= "ӿ")
    return fixed if cyr * 2 >= len(letters) else s


def first(tags, key):
    if not tags:
        return None
    v = tags.get(key)
    if isinstance(v, list):
        v = v[0] if v else None
    if v is None:
        return None
    v = fix_mojibake(str(v).strip().replace("\x00", ""))
    return v or None


def num(v):
    m = re.match(r"\s*(\d+)", v or "")
    return int(m.group(1)) if m else None


def read_tags(path):
    try:
        f = mutagen.File(path, easy=True)
    except Exception as e:
        print(f"warn: cannot read {path}: {e}", file=sys.stderr)
        return None
    if f is None:
        return None
    t = f.tags
    return {
        "title": first(t, "title"),
        "artist": first(t, "artist"),
        "albumartist": first(t, "albumartist"),
        "album": first(t, "album"),
        "track": num(first(t, "tracknumber")),
        "disc": num(first(t, "discnumber")),
        "year": num(first(t, "date") or first(t, "originaldate")),
        "duration": round(f.info.length, 3) if f.info and f.info.length else 0,
    }


def embedded_picture(path):
    try:
        f = mutagen.File(path)
    except Exception:
        return None
    if f is None:
        return None
    pics = getattr(f, "pictures", None)  # FLAC
    if pics:
        return pics[0].data
    tags = f.tags or {}
    for k in list(tags.keys()):
        if k.startswith("APIC"):  # ID3
            return tags[k].data
    covr = tags.get("covr") if hasattr(tags, "get") else None  # MP4
    if covr:
        return bytes(covr[0])
    return None


# ---------- CUE ----------

def read_text(path):
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "cp1251"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return raw.decode("latin1")


def cue_value(line):
    rest = line.split(None, 1)[1] if len(line.split(None, 1)) > 1 else ""
    rest = rest.strip()
    if rest.startswith('"'):
        return rest[1:rest.rfind('"')] if rest.rfind('"') > 0 else rest[1:]
    return rest


def parse_cue(path):
    """Return (album, performer, file, [ {track,title,performer,start} ]) or None."""
    album = performer = fname = None
    tracks = []
    cur = None
    for line in read_text(path).splitlines():
        s = line.strip()
        word = s.split(" ", 1)[0].upper()
        if word == "FILE":
            if fname is not None:
                return None  # multi-file cue: tracks are already separate files
            rest = s.split(None, 1)[1].strip() if " " in s else ""
            fname = cue_value(s) if rest.startswith('"') else rest.rsplit(" ", 1)[0]
        elif word == "TRACK":
            cur = {"track": num(s.split()[1]), "title": None, "performer": None, "start": None}
            tracks.append(cur)
        elif word == "TITLE":
            if cur is None:
                album = cue_value(s)
            else:
                cur["title"] = cue_value(s)
        elif word == "PERFORMER":
            if cur is None:
                performer = cue_value(s)
            else:
                cur["performer"] = cue_value(s)
        elif word == "INDEX" and cur is not None:
            parts = s.split()
            if len(parts) == 3 and parts[1] == "01":
                mm, ss, ff = (int(x) for x in parts[2].split(":"))
                cur["start"] = round(mm * 60 + ss + ff / 75, 3)
    tracks = [t for t in tracks if t["start"] is not None]
    if not fname or len(tracks) < 2:
        return None
    return album, performer, fname, tracks


# ---------- covers ----------

def find_cover_file(d, files, extra_dirs):
    imgs = [f for f in files if os.path.splitext(f)[1].lower() in IMAGE_EXT]
    for f in sorted(imgs):
        if os.path.splitext(f)[0].lower().startswith(COVER_NAMES):
            return os.path.join(d, f)
    for sub in extra_dirs:
        try:
            sub_files = sorted(os.listdir(sub))
        except OSError:
            continue
        for f in sub_files:
            if os.path.splitext(f)[1].lower() in IMAGE_EXT and os.path.splitext(f)[0].lower().startswith(COVER_NAMES):
                return os.path.join(sub, f)
    if imgs:
        return os.path.join(d, sorted(imgs)[0])
    return None


def make_thumb(src_bytes, dst):
    img = Image.open(io.BytesIO(src_bytes))
    img = img.convert("RGB")
    img.thumbnail((THUMB, THUMB))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    img.save(dst, "JPEG", quality=85)


# ---------- main ----------

def common(values):
    vals = [v for v in values if v]
    if not vals:
        return None
    return collections.Counter(vals).most_common(1)[0][0]


def album_id(key):
    return hashlib.sha1(key.encode("utf-8", "surrogateescape")).hexdigest()[:16]


def walk(root, only):
    starts = [os.path.join(root, o) for o in only] if only else [root]
    for start in starts:
        for d, dirs, files in os.walk(start):
            dirs.sort()
            yield d, dirs, sorted(files)


def favourite_index(fav_root):
    by_name_size, by_tag = set(), {}
    if not fav_root or not os.path.isdir(fav_root):
        return by_name_size, by_tag
    for d, _, files in os.walk(fav_root):
        for f in files:
            if os.path.splitext(f)[1].lower() not in AUDIO_EXT:
                continue
            p = os.path.join(d, f)
            by_name_size.add((f, os.path.getsize(p)))
            t = read_tags(p) or {}
            if t.get("artist") and t.get("title"):
                by_tag[(t["artist"].lower(), t["title"].lower())] = os.path.relpath(p, fav_root)
    return by_name_size, by_tag


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--music", default=os.path.expanduser("~/Music"))
    ap.add_argument("--favourites", default=os.path.expanduser("~/Favourite Music"))
    ap.add_argument("--out", default=os.path.expanduser("~/.cache/b2-player"))
    ap.add_argument("only", nargs="*", help="limit to these subfolders of --music (for tests)")
    a = ap.parse_args()

    root = os.path.abspath(a.music)
    fav_ns, fav_tags = favourite_index(a.favourites)
    matched_fav_tags = set()
    albums = {}  # key -> dict
    bad_names = []

    for d, dirs, files in walk(root, a.only):
        audio = [f for f in files if os.path.splitext(f)[1].lower() in AUDIO_EXT]
        if not audio:
            continue
        rel_dir = os.path.relpath(d, root)
        try:
            rel_dir.encode("utf-8")
        except UnicodeEncodeError:
            bad_names.append(rel_dir)
            continue
        group_dir = os.path.dirname(rel_dir) if DISC_DIR.match(os.path.basename(d)) else rel_dir
        cover_dirs = [os.path.join(d, x) for x in dirs] + ([os.path.dirname(d)] if group_dir != rel_dir else [])

        cue_tracks = {}
        cues = [f for f in files if f.lower().endswith(".cue")]
        if len(audio) == 1 and len(cues) >= 1:
            parsed = parse_cue(os.path.join(d, cues[0]))
            if parsed:
                cue_tracks[audio[0]] = parsed

        for f in audio:
            p = os.path.join(d, f)
            rel = os.path.relpath(p, root)
            try:
                rel.encode("utf-8")
            except UnicodeEncodeError:
                bad_names.append(rel)
                continue
            t = read_tags(p)
            if t is None:
                continue
            liked = (f, os.path.getsize(p)) in fav_ns
            if not liked and t["artist"] and t["title"]:
                k = (t["artist"].lower(), t["title"].lower())
                if k in fav_tags:
                    liked = True
                    matched_fav_tags.add(k)
            entries = []
            if f in cue_tracks:
                c_album, c_perf, _, ctr = cue_tracks[f]
                for i, ct in enumerate(ctr):
                    end = ctr[i + 1]["start"] if i + 1 < len(ctr) else t["duration"]
                    entries.append({
                        "path": rel, "title": ct["title"] or f"Track {ct['track']}",
                        "artist": ct["performer"] or c_perf or t["artist"], "albumartist": c_perf or t["albumartist"],
                        "album": c_album or t["album"], "track": ct["track"], "disc": None, "year": t["year"],
                        "start": ct["start"], "duration": round(end - ct["start"], 3), "liked": liked,
                    })
            else:
                entries.append({**t, "path": rel, "title": t["title"] or os.path.splitext(f)[0], "liked": liked})

            for e in entries:
                key = group_dir + "\0" + (e["album"] or "")
                al = albums.get(key)
                if al is None:
                    al = albums[key] = {"key": key, "dir": group_dir, "abs": d, "files": files,
                                        "cover_dirs": cover_dirs, "tracks": []}
                al["tracks"].append(e)

    covers_dir = os.path.join(a.out, "covers")
    os.makedirs(covers_dir, exist_ok=True)
    out_albums = []
    for al in albums.values():
        tr = al["tracks"]
        aid = album_id(al["key"])
        artists = {x["artist"] for x in tr if x["artist"]}
        artist = common(x["albumartist"] for x in tr) or (common(x["artist"] for x in tr) if len(artists) <= 2 else "Various Artists")
        title = common(x["album"] for x in tr) or os.path.basename(al["dir"])
        tr.sort(key=lambda x: (x.get("disc") or 1, x.get("track") or 9999, x.get("start") or 0, x["path"]))

        thumb = os.path.join(covers_dir, aid + ".jpg")
        cover = None
        if os.path.exists(thumb):
            cover = f"covers/{aid}.jpg"
        else:
            src = find_cover_file(al["abs"], al["files"], al["cover_dirs"])
            data = open(src, "rb").read() if src else embedded_picture(os.path.join(root, tr[0]["path"]))
            if data:
                try:
                    make_thumb(data, thumb)
                    cover = f"covers/{aid}.jpg"
                except Exception as e:
                    print(f"warn: cover for {al['dir']}: {e}", file=sys.stderr)

        tracks = []
        for x in tr:
            o = {"path": x["path"], "title": x["title"], "duration": x["duration"]}
            if x["artist"] and x["artist"] != artist:
                o["artist"] = x["artist"]
            for k in ("track", "disc", "start"):
                if x.get(k) is not None:
                    o[k] = x[k]
            if x["liked"]:
                o["liked"] = True
            tracks.append(o)
        out_albums.append({"id": aid, "dir": al["dir"], "title": title, "artist": artist or "Unknown",
                           "year": common(x["year"] for x in tr), "cover": cover, "tracks": tracks})

    out_albums.sort(key=lambda x: (x["artist"].lower(), x["year"] or 0, x["title"].lower()))
    catalog = {"version": 1, "generated": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
               "musicPrefix": "music/", "albums": out_albums}
    with gzip.open(os.path.join(a.out, "catalog.json.gz"), "wt", encoding="utf-8") as fh:
        json.dump(catalog, fh, ensure_ascii=False, separators=(",", ":"))

    unmatched = []
    if not a.only:
        liked_names = set()
        for al in out_albums:
            for t in al["tracks"]:
                if t.get("liked"):
                    liked_names.add(os.path.basename(t["path"]))
        for d, _, files in os.walk(a.favourites):
            for f in files:
                if os.path.splitext(f)[1].lower() in AUDIO_EXT and f not in liked_names:
                    unmatched.append(os.path.relpath(os.path.join(d, f), a.favourites))
        with open(os.path.join(a.out, "unmatched-favourites.txt"), "w") as fh:
            fh.write("\n".join(sorted(unmatched)) + "\n")

    n_tracks = sum(len(x["tracks"]) for x in out_albums)
    n_liked = sum(1 for x in out_albums for t in x["tracks"] if t.get("liked"))
    print(f"albums={len(out_albums)} tracks={n_tracks} liked={n_liked} "
          f"no_cover={sum(1 for x in out_albums if not x['cover'])} bad_names={len(bad_names)} "
          f"unmatched_favourites={len(unmatched) if not a.only else 'n/a'}")
    for b in bad_names[:20]:
        print("non-utf8 name skipped:", b.encode("utf-8", "surrogateescape"), file=sys.stderr)


if __name__ == "__main__":
    main()
