#!/usr/bin/env bash
# Put a B2 key (and optionally a Claude API key) into the release app on the b2test emulator.
# Asks for the keys at the prompt, so they never show up on screen, in history or in logs.
# Boots the emulator headless if it isn't running. Works over ssh.
#   tools/emu-set-key.sh
set -euo pipefail

AVD="${AVD:-b2test}"
PKG=dev.buyolitsez.b2player
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$PATH:/opt/homebrew/bin:$SDK/platform-tools:$SDK/emulator"
# adb reads stdin; keep the terminal on fd 3 for the prompts
exec 3<&0 </dev/null

if ! adb devices | grep -q '^emulator-.*device$'; then
  echo "starting $AVD headless (log in ~/.cache/emulator-$AVD.log)"
  mkdir -p ~/.cache
  nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim >~/.cache/emulator-"$AVD".log 2>&1 &
fi
adb -e wait-for-device
until [ "$(adb -e shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
adb -e root >/dev/null
adb -e wait-for-device
adb -e shell pm path "$PKG" >/dev/null || { echo "$PKG is not installed on the emulator"; exit 1; }

read -rp  "B2 keyID (empty to skip): " key_id <&3
read -rsp "B2 applicationKey: " app_key <&3; echo
read -rsp "Claude API key (empty to skip): " claude_key <&3; echo

xml() { local s=${1//&/'&amp;'}; s=${s//</'&lt;'}; s=${s//>/'&gt;'}; s=${s//\"/'&quot;'}; printf %s "$s"; }
dir=/data/data/$PKG/shared_prefs
# Writes stdin into the app's prefs file with the app's owner and SELinux label.
put() {
  adb -e shell "mkdir -p $dir && cat > $dir/$1.xml && uid=\$(stat -c %u /data/data/$PKG) &&
    chown \$uid:\$uid $dir $dir/$1.xml && chmod 660 $dir/$1.xml && restorecon -R $dir >/dev/null 2>&1"
}

adb -e shell am force-stop "$PKG"
if [ -n "$key_id" ]; then
  printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<string name="keyId">%s</string>\n<string name="appKey">%s</string>\n<string name="bucket">buyolitsez-music</string>\n</map>\n' \
    "$(xml "$key_id")" "$(xml "$app_key")" | put b2
  echo "B2 key saved"
fi
if [ -n "$claude_key" ]; then
  printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<string name="apiKey">%s</string>\n</map>\n' \
    "$(xml "$claude_key")" | put assistant
  echo "Claude key saved"
fi
adb -e shell am start -n "$PKG/.MainActivity" >/dev/null
echo "app started"
