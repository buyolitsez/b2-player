#!/usr/bin/env python3
"""Make light Opus copies of the music, which the app streams on a slow network.

For every file of the catalog whose bitrate is at least --min-kbps (lossless and
320 kbps MP3, say), writes OUT/lite/<path>.opus at --kbps. Files that are already
light are skipped: their copy would save little, and the app plays the original.
A CUE image becomes one Opus file too, so the track start times stay valid.

The source is the file in --music, or, for tracks that are only in the bucket,
the same file through the bucket mount (--mount). OUT/lite-sources.json keeps
the size of the source of each copy, so a file changed since (tags rewritten)
is encoded again. publish-catalog.sh uploads the copies to lite/ in the bucket
and, with --mark, gives the catalog's tracks the size of their copy as "lite".

  tools/make_lite.py                      # the whole catalog
  tools/make_lite.py "rock/Swans" ...     # only these folders
  tools/make_lite.py --mark lite-files.txt   # mark tracks; the list is rclone lsf --format sp of lite/
"""
import argparse
import concurrent.futures
import gzip
import json
import os
import subprocess
import sys


def files_of(catalog, only):
    """path -> (size, duration in seconds) of each file of the catalog; CUE tracks add up."""
    with gzip.open(catalog, "rt", encoding="utf-8") as fh:
        cat = json.load(fh)
    files = {}
    for al in cat["albums"]:
        for t in al["tracks"]:
            p = t["path"]
            if only and not any(p.startswith(o.rstrip("/") + "/") for o in only):
                continue
            size, dur = files.get(p, (t.get("size", 0), 0))
            files[p] = (size, dur + (t.get("duration") or 0))
    return files


def mark(catalog, lite_files):
    lite = {}
    with open(lite_files, encoding="utf-8") as fh:
        for line in fh.read().splitlines():
            size, _, path = line.partition("\t")
            lite[path] = int(size)
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
    ap.add_argument("-j", "--jobs", type=int, default=os.cpu_count())
    ap.add_argument("--mark", metavar="LITE_FILES", help="only mark the catalog's tracks that have a copy in this list")
    ap.add_argument("only", nargs="*", help="limit to these folders of the library")
    a = ap.parse_args()
    if a.mark:
        return mark(a.catalog, a.mark)

    state_file = os.path.join(a.out, "lite-sources.json")
    try:
        with open(state_file, encoding="utf-8") as fh:
            done = json.load(fh)
    except FileNotFoundError:
        done = {}
    todo, light = [], 0
    for path, (size, dur) in sorted(files_of(a.catalog, a.only).items()):
        if dur <= 0 or size * 8 / dur / 1000 < a.min_kbps:
            light += 1
            continue
        dst = os.path.join(a.out, "lite", path + ".opus")
        if done.get(path) == size and os.path.exists(dst):
            continue
        local = os.path.join(a.music, path)
        src = local if os.path.exists(local) and os.path.getsize(local) == size else os.path.join(a.mount, path)
        todo.append((path, size, src, dst))
    print(f"to encode: {len(todo)} files, {sum(t[1] for t in todo) / 1e9:.1f} GB; already light: {light}", flush=True)

    failed = 0
    with concurrent.futures.ThreadPoolExecutor(a.jobs) as pool:
        jobs = {pool.submit(encode, src, dst, a.kbps): (path, size) for path, size, src, dst in todo}
        for i, f in enumerate(concurrent.futures.as_completed(jobs), 1):
            path, size = jobs[f]
            err = f.result()
            if err:
                failed += 1
                print(f"failed: {path}: {err[0]}", file=sys.stderr, flush=True)
            else:
                done[path] = size
            if i % 50 == 0 or i == len(todo):
                print(f"{i}/{len(todo)}", flush=True)
                with open(state_file + ".tmp", "w", encoding="utf-8") as fh:
                    json.dump(done, fh, ensure_ascii=False)
                os.replace(state_file + ".tmp", state_file)
    print(f"encoded {len(todo) - failed}, failed {failed}")


if __name__ == "__main__":
    main()
