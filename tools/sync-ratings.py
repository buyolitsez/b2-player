#!/usr/bin/env python3
"""Sync album ratings between the listening diary and state/ratings.json in the bucket.

The diary is a folder of "послушал <year>.md" files with numbered lines like
  207. Youth of America (1981) - Wipers, 12.11.2026, 7/10
Each line is matched to the catalog albums by artist and title; the last rating
of an album wins. Ratings set in the app or in cmus come as "pending" entries:
they are appended to the diary as new lines and then read back like the others.

  tools/sync-ratings.py          # show what would change
  tools/sync-ratings.py --apply  # append to the diary and upload state/ratings.json

Diary lines that match no album go to ~/.cache/b2-player/ratings-unmatched.txt.
An album can be named by hand in ~/.config/b2-player/rating-aliases.tsv:
"artist<TAB>title<TAB>album id" with the artist and title as in the diary.
"""
import argparse
import datetime
import difflib
import gzip
import json
import os
import re
import subprocess
import sys
import unicodedata

p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
p.add_argument("--remote", default=os.environ.get("B2_PLAYER_REMOTE", "b2music:buyolitsez-music"))
p.add_argument("--diary", default="~/Desktop/personal/notes/Base/отдых/культурный/музыка/послушал")
p.add_argument("--aliases", default="~/.config/b2-player/rating-aliases.tsv")
p.add_argument("--out", default="~/.cache/b2-player")
p.add_argument("--apply", action="store_true", help="write the diary and the bucket")
p.add_argument("-v", "--verbose", action="store_true", help="also list the diary lines matched despite typos")
a = p.parse_args()
diary = os.path.expanduser(a.diary)
STATE = f"{a.remote}/state/ratings.json"

LINE = re.compile(r"^\s*(\d+)\.\s+(.*)$")
RATING = re.compile(r"(\d+(?:[.,]\d+)?)\s*/\s*10\b")
DATE = re.compile(r",?\s*~?\d{1,2}[./-]\d{1,2}[./-]\d{2,4}")
BRACKETS = re.compile(r"\s*[(\[][^)\]]*[)\]]")


def rclone_cat(path):
    # a missing file gives empty output; a failed read must not pass for one
    r = subprocess.run(["rclone", "cat", path], capture_output=True)
    if r.returncode != 0:
        sys.exit(f"rclone cat {path} failed: {r.stderr.decode().strip()}")
    return r.stdout


def norm(s):
    s = unicodedata.normalize("NFKD", s.casefold().replace("ё", "е"))
    return re.sub(r"\W+", "", "".join(c for c in s if not unicodedata.combining(c)))


def keys(artist, title):
    """Exact key first, then the title without (edition) and [label] parts."""
    return [(norm(artist), norm(title)), (norm(artist), norm(BRACKETS.sub("", title)))]


def similar(a, b):
    """Typos in the diary: close enough and with the same numbers ("Kraftwerk 2" is not "Kraftwerk 3")."""
    return re.findall(r"\d+", a) == re.findall(r"\d+", b) and difflib.SequenceMatcher(None, a, b).ratio() >= 0.9


def number(r):
    return int(r) if r == int(r) else r


def diary_files():
    names = [n for n in os.listdir(diary) if n.endswith(".md") and not n.startswith("._")]
    # "послушал 2024.md" before "послушал 2024 — продолжение ….md"
    return sorted(names, key=lambda n: (re.search(r"\d{4}", n).group(), len(n)))


def diary_rows():
    """(artist, title, rating, line) of the rated diary lines, oldest first."""
    rows = []
    for name in diary_files():
        with open(os.path.join(diary, name), encoding="utf-8") as f:
            for line in f:
                m = LINE.match(line)
                r = RATING.search(m.group(2)) if m else None
                if not r:
                    continue
                core = DATE.split(RATING.sub("", m.group(2)))[0].strip(" ,.")
                if " - " not in core:
                    continue
                title, artist = core.rsplit(" - ", 1)
                rows.append((artist.strip(" ,."), title.strip(), float(r.group(1).replace(",", ".")), line.strip()))
    return rows


out_dir = os.path.expanduser(a.out)
os.makedirs(out_dir, exist_ok=True)
# rclone copies the catalog only when it changed
subprocess.run(["rclone", "copyto", f"{a.remote}/catalog.json.gz", os.path.join(out_dir, "ratings-catalog.json.gz")], check=True)
with gzip.open(os.path.join(out_dir, "ratings-catalog.json.gz"), "rt", encoding="utf-8") as f:
    catalog = json.load(f)["albums"]
albums = {al["id"]: al for al in catalog}
index = {}
by_artist = {}
for al in catalog:
    # "Flying Teapot: Radio Gnome Invisible, Part 1" is also "Flying Teapot"
    for i, k in enumerate(keys(al["artist"], al["title"]) + keys(al["artist"], al["title"].split(": ")[0])[1:]):
        index.setdefault((min(i, 1), k), []).append(al["id"])
    by_artist.setdefault(norm(al["artist"]), {}).setdefault(norm(BRACKETS.sub("", al["title"])), []).append(al["id"])
aliases = {}
try:
    with open(os.path.expanduser(a.aliases), encoding="utf-8") as f:
        for line in f:
            if line.strip() and not line.startswith("#"):
                artist, title, aid = line.rstrip("\n").split("\t")
                aliases.setdefault((norm(artist), norm(title)), []).append(aid)
except FileNotFoundError:
    pass


fuzzy = []


def match(artist, title):
    k = keys(artist, title)
    ids = aliases.get(k[0]) or index.get((0, k[0])) or index.get((1, k[1]))
    if ids:
        return ids
    titles = by_artist.get(k[1][0], {})
    close = [t for t in titles if similar(k[1][1], t)]
    if not close:
        return []
    best = max(close, key=lambda t: difflib.SequenceMatcher(None, k[1][1], t).ratio())
    fuzzy.append(f"{artist} - {title} = {albums[titles[best][0]]['title']}")
    return titles[best]


def read_diary():
    ratings, unmatched = {}, []
    for artist, title, rating, line in diary_rows():
        ids = match(artist, title)
        if not ids:
            unmatched.append(line)
        for aid in ids:
            ratings[aid] = number(rating)
    return ratings, unmatched


raw = rclone_cat(STATE)
state = json.loads(raw)["albums"] if raw else {}
pending = {aid: e for aid, e in state.items() if e.get("pending")}

# pending ratings -> new diary lines in the file of their year
appends = {}
for aid, e in sorted(pending.items(), key=lambda kv: kv[1]["time"]):
    al = albums.get(aid)
    if not al:
        print(f"pending rating of an album not in the catalog: {aid}", file=sys.stderr)
        continue
    day = datetime.datetime.fromtimestamp(e["time"] / 1000)
    year = f" ({al['year']})" if al.get("year") else ""
    appends.setdefault(day.year, []).append(
        f"{al['title']}{year} - {al['artist']}, {day:%d.%m.%Y}, {number(e['rating'])}/10")

for year, lines in appends.items():
    path = os.path.join(diary, f"послушал {year}.md")
    text = open(path, encoding="utf-8").read() if os.path.exists(path) else f"Послушал {year}\n"
    n = max((int(m.group(1)) for m in map(LINE.match, text.splitlines()) if m), default=0)
    new = [f"{n + i + 1}. {line}" for i, line in enumerate(lines)]
    print(f"{os.path.basename(path)}:", *("  + " + line for line in new), sep="\n")
    if a.apply:
        with open(path, "w", encoding="utf-8") as f:
            f.write(text.rstrip("\n") + "\n" + "\n".join(new) + "\n")

ratings, unmatched = read_diary()
if not a.apply:  # the dry run reads the lines it would have appended
    for aid, e in pending.items():
        if aid in albums:
            ratings[aid] = number(e["rating"])
out = {aid: {"rating": r} for aid, r in sorted(ratings.items())}
for aid, e in pending.items():
    if aid not in albums:
        out[aid] = e

with open(os.path.join(out_dir, "ratings-unmatched.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(unmatched) + "\n")
changed = {aid for aid in out.keys() | state.keys() if out.get(aid) != state.get(aid)}
print(*sorted(set(fuzzy)), sep="\n") if a.verbose else None
print(f"{len(ratings)} albums rated, {len(unmatched)} diary lines unmatched, {len(set(fuzzy))} matched with typos, "
      f"{len(changed)} changes in state/ratings.json")
for aid in sorted(changed, key=lambda i: (albums.get(i, {}).get("artist", ""), albums.get(i, {}).get("title", ""))):
    al = albums.get(aid, {})
    old, new = state.get(aid, {}).get("rating"), out.get(aid, {}).get("rating")
    print(f"  {al.get('artist', '?')} - {al.get('title', aid)}: {old} -> {new}")
if not changed or not a.apply:
    sys.exit()

# A rating set on the phone while this ran stays pending and is written next time.
raw = rclone_cat(STATE)
for aid, e in (json.loads(raw)["albums"] if raw else {}).items():
    if e.get("pending") and e != pending.get(aid):
        out[aid] = e
data = json.dumps({"version": 1, "albums": out}, indent=1, ensure_ascii=False)
subprocess.run(["rclone", "rcat", STATE], input=data.encode(), check=True)
