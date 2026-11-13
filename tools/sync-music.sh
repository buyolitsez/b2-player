#!/usr/bin/env bash
# Upload the music library as plain files to the B2 bucket used by the player.
#
#   tools/sync-music.sh                 # upload new and changed files of $B2_PLAYER_MUSIC (~/Music) -> $B2_PLAYER_REMOTE/music
#   tools/sync-music.sh --dry-run       # show what would change
#   WAIT_FOR_RESTIC=1 tools/sync-music.sh   # wait until the restic backup finishes, then upload
#   tools/sync-music.sh -- "rock/Pink Floyd/1973 - ..."   # only these subfolders
#
# Nothing is deleted from the bucket: the music folder may keep only part of
# the music, and publish-catalog.sh keeps the rest in the catalog. To remove an
# album (or the old path after a rename): rclone purge "$B2_PLAYER_REMOTE/music/<folder>".
# B2 keeps the previous version of every file (lifecycle "keep all versions").
# Extra rclone flags go before "--", e.g. --bwlimit 5M.
set -euo pipefail

REMOTE="${B2_PLAYER_REMOTE:-b2music:buyolitsez-music}"
MUSIC="${B2_PLAYER_MUSIC:-$HOME/Music}"

flags=(); only=()
while [ $# -gt 0 ]; do
  if [ "$1" = "--" ]; then shift; only=("$@"); break; fi
  flags+=("$1"); shift
done

restic_running() { pgrep -x restic >/dev/null || pgrep -f "restic/backup.sh" >/dev/null; }
if [ ${#only[@]} -eq 0 ] && [ "${WAIT_FOR_RESTIC:-}" = 1 ]; then
  while restic_running; do sleep 300; done
fi
if [ ${#only[@]} -eq 0 ] && restic_running; then
  echo "restic backup is running; full upload would compete for the uplink. Try later." >&2
  exit 1
fi

common=(--fast-list --transfers 8 --checkers 16 --exclude ".DS_Store" --exclude "._*" --progress)

if [ ${#only[@]} -eq 0 ]; then
  rclone copy "$MUSIC" "$REMOTE/music" "${common[@]}" ${flags[@]+"${flags[@]}"}
else
  for d in "${only[@]}"; do
    rclone copy "$MUSIC/$d" "$REMOTE/music/$d" "${common[@]}" ${flags[@]+"${flags[@]}"}
  done
fi
