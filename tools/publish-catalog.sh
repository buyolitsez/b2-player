#!/usr/bin/env bash
# Rebuild the catalog and upload it with the cover thumbnails.
#   tools/publish-catalog.sh                  # whole library
#   tools/publish-catalog.sh "rock/Swans" ... # only these subfolders: build in $OUT, don't upload (tests)
set -euo pipefail

REMOTE="${B2_PLAYER_REMOTE:-b2music:buyolitsez-music}"
OUT="${B2_PLAYER_OUT:-$HOME/.cache/b2-player}"
here="$(cd "$(dirname "$0")" && pwd)"

mkdir -p "$OUT"
rclone lsf -R --files-only --fast-list "$REMOTE/music" > "$OUT/bucket-files.txt"
# Tracks deleted from the music folder but still in the bucket stay in the catalog.
rclone copyto "$REMOTE/catalog.json.gz" "$OUT/previous-catalog.json.gz"
python3 "$here/make_catalog.py" --out "$OUT" \
  --previous "$OUT/previous-catalog.json.gz" --bucket-files "$OUT/bucket-files.txt" "$@"
if [ $# -gt 0 ]; then
  echo "built $OUT/catalog.json.gz from part of the library; not uploaded"
  exit 0
fi

# Every catalog path must exist in the bucket (catches unsynced files and name-encoding mismatches).
python3 - "$OUT/catalog.json.gz" "$OUT/bucket-files.txt" <<'EOF'
import gzip, json, sys
cat = json.load(gzip.open(sys.argv[1], "rt", encoding="utf-8"))
remote = set(open(sys.argv[2], encoding="utf-8").read().splitlines())
paths = {t["path"] for a in cat["albums"] for t in a["tracks"]}
missing = sorted(paths - remote)
print(f"catalog paths: {len(paths)}, missing in bucket: {len(missing)}")
for p in missing[:20]:
    print("  missing:", p)
if missing:
    sys.exit("not published: run tools/sync-music.sh first")
EOF

# Light copies of new files for streaming on a slow network; the catalog gives the sizes of those in the bucket.
list_lite() { rclone lsf -R --files-only --fast-list --format tsp --separator $'\t' "$REMOTE/lite" > "$OUT/lite-files.txt"; }
list_lite
python3 "$here/make_lite.py" --catalog "$OUT/catalog.json.gz" --out "$OUT" --lite-files "$OUT/lite-files.txt"
rclone move "$OUT/lite" "$REMOTE/lite" --fast-list --transfers 8 --exclude "*.part"
list_lite
python3 "$here/make_lite.py" --catalog "$OUT/catalog.json.gz" --mark "$OUT/lite-files.txt"

rclone copy "$OUT/covers" "$REMOTE/covers" --fast-list --transfers 16
rclone copy "$OUT/lyrics" "$REMOTE/lyrics" --fast-list --transfers 16
rclone copyto "$OUT/catalog.json.gz" "$REMOTE/catalog.json.gz"
