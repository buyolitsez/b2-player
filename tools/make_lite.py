#!/usr/bin/env python3
"""Make light Opus copies of the music, which the app streams on a slow network.

For every file of the catalog whose bitrate is at least --min-kbps (lossless and
320 kbps MP3, say), writes OUT/lite/<path>.opus at --kbps. Files that are already
light are skipped: their copy would save little, and the app plays the original.
A CUE image becomes one Opus file too, so the track start times stay valid.

The source is the file in --music, or, for tracks that are only in the bucket
(or on a computer without the library), the same file through the bucket mount
(--mount). A file whose copy is in the bucket (--lite-files) or in OUT/lite and
is newer than the file is skipped, so a file changed since (tags rewritten) is
encoded again, and any computer can continue the work. publish-catalog.sh moves
the copies to lite/ in the bucket and, with --mark, gives the catalog's tracks
the size of their copy as "lite".

  LIST: rclone lsf -R --files-only --format tsp --separator $'\t' <remote>/lite > lite-files.txt
  tools/make_lite.py --lite-files lite-files.txt                  # the whole catalog
  tools/make_lite.py --lite-files lite-files.txt "rock/Swans" ... # only these folders
  tools/make_lite.py --mark lite-files.txt                        # mark the catalog's tracks
"""
import argparse
import concurrent.futures
import datetime
import gzip
import json
import os
import subprocess
import sys


def files_of(catalog, only):
    """path -> (size, duration in seconds, mtime) of each file of the catalog; CUE tracks add up."""
    with gzip.open(catalog, "rt", encoding="utf-8") as fh:
        cat = json.load(fh)
    files = {}
    for al in cat["albums"]:
        for t in al["tracks"]:
            p = t["path"]
            if only and not any(p.startswith(o.rstrip("/") + "/") for o in only):
                continue
            size, dur, mtime = files.get(p, (t.get("size", 0), 0, t.get("mtime", 0)))
            files[p] = (size, dur + (t.get("duration") or 0), mtime)
    return files


def read_list(lite_files):
    """path -> (size, mtime) from an rclone lsf --format tsp listing of lite/."""
    lite = {}
    if lite_files:
        with open(lite_files, encoding="utf-8") as fh:
            for line in fh.read().splitlines():
                t, size, path = line.split("\t", 2)
                lite[path] = (int(size), datetime.datetime.strptime(t, "%Y-%m-%d %H:%M:%S").timestamp())
    return lite


def mark(catalog, lite_files):
    lite = {path: size for path, (size, _) in read_list(lite_files).items()}
    with gzip.open(catalog, "rt", encoding="utf-8") as fh:
        cat = json.load(fh)
    n = 0
    for al in cat["albums"]:
        for t in al["tracks"]:
            size = lite.get(t["path"] + ".opus")
            if size:
                t["lite"] = size
                n += 1
            else:
                t.pop("lite", None)
    with gzip.open(catalog + ".tmp", "wt", encoding="utf-8") as fh:
        json.dump(cat, fh, ensure_ascii=False, separators=(",", ":"))
    os.replace(catalog + ".tmp", catalog)
    print(f"tracks with a lite copy: {n}")


def encode(src, dst, kbps):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    tmp = dst + ".part"
    r = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", src, "-map", "0:a:0", "-map_metadata", "0",
                        "-c:a", "libopus", "-b:a", f"{kbps}k", "-f", "ogg", tmp], capture_output=True, text=True)
    if r.returncode != 0:
        if os.path.exists(tmp):
            os.remove(tmp)
        return r.stderr.strip().splitlines()[-1:] or ["ffmpeg failed"]
    os.replace(tmp, dst)
    return None


def main():
    home = os.path.expanduser("~")
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", default=f"{home}/.cache/b2-player/catalog.json.gz")
    ap.add_argument("--music", default=f"{home}/Music")
    ap.add_argument("--mount", default=f"{home}/b2music", help="the bucket's music/ mounted, for files not in --music")
    ap.add_argument("--out", default=f"{home}/.cache/b2-player")
    ap.add_argument("--min-kbps", type=int, default=256, help="lighter files are not copied")
    ap.add_argument("--kbps", type=int, default=128)
    ap.add_argument("-j", "--jobs", type=int, default=4, help="the computer that holds the library overheats on all cores")
    ap.add_argument("--lite-files", help="the copies already in the bucket, see LIST above")
    ap.add_argument("--mark", metavar="LITE_FILES", help="only mark the catalog's tracks that have a copy in this list")
    ap.add_argument("only", nargs="*", help="limit to these folders of the library")
    a = ap.parse_args()
    if a.mark:
        return mark(a.catalog, a.mark)

    in_bucket = read_list(a.lite_files)
    todo, light = [], 0
    for path, (size, dur, mtime) in sorted(files_of(a.catalog, a.only).items()):
        if dur <= 0 or size * 8 / dur / 1000 < a.min_kbps:
            light += 1
            continue
        dst = os.path.join(a.out, "lite", path + ".opus")
        # empty copies are left by a crash of the computer (seen on arch): encode those again
        size_b, time_b = in_bucket.get(path + ".opus", (0, 0))
        if (size_b and time_b > mtime) or (os.path.exists(dst) and os.path.getsize(dst) and os.path.getmtime(dst) > mtime):
            continue
        local = os.path.join(a.music, path)
        src = local if os.path.exists(local) and os.path.getsize(local) == size else os.path.join(a.mount, path)
        todo.append((path, size, src, dst))
    print(f"to encode: {len(todo)} files, {sum(t[1] for t in todo) / 1e9:.1f} GB; already light: {light}", flush=True)

    failed = 0
    with concurrent.futures.ThreadPoolExecutor(a.jobs) as pool:
        jobs = {pool.submit(encode, src, dst, a.kbps): path for path, size, src, dst in todo}
        for i, f in enumerate(concurrent.futures.as_completed(jobs), 1):
            err = f.result()
            if err:
                failed += 1
                print(f"failed: {jobs[f]}: {err[0]}", file=sys.stderr, flush=True)
            if i % 50 == 0 or i == len(todo):
                print(f"{i}/{len(todo)}", flush=True)
    print(f"encoded {len(todo) - failed}, failed {failed}")


if __name__ == "__main__":
    main()
