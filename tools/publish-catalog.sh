#!/usr/bin/env bash
# Rebuild the catalog and upload it with the cover thumbnails.
#   tools/publish-catalog.sh                  # whole library
#   tools/publish-catalog.sh "rock/Swans" ... # only these subfolders (tests)
set -euo pipefail

REMOTE="${B2_PLAYER_REMOTE:-b2music:buyolitsez-music}"
OUT="${B2_PLAYER_OUT:-$HOME/.cache/b2-player}"
here="$(cd "$(dirname "$0")" && pwd)"

python3 "$here/make_catalog.py" --out "$OUT" "$@"
rclone copy "$OUT/covers" "$REMOTE/covers" --fast-list --transfers 16
rclone copyto "$OUT/catalog.json.gz" "$REMOTE/catalog.json.gz"

# Every catalog path must exist in the bucket (catches name-encoding mismatches).
python3 - "$OUT/catalog.json.gz" <(rclone lsf -R --files-only --fast-list "$REMOTE/music") <<'EOF'
import gzip, json, sys
cat = json.load(gzip.open(sys.argv[1], "rt", encoding="utf-8"))
remote = set(open(sys.argv[2], encoding="utf-8").read().splitlines())
paths = {t["path"] for a in cat["albums"] for t in a["tracks"]}
missing = sorted(paths - remote)
print(f"catalog paths: {len(paths)}, missing in bucket: {len(missing)}")
for p in missing[:20]:
    print("  missing:", p)
EOF
