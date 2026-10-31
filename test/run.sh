#!/bin/bash
# Runs inside the emulator job: installs the app, signs it in to the fake Plex server,
# walks through a few screens and takes screenshots.
set -x
OUT=shots
mkdir -p $OUT
PKG=com.iceman.ice

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }
# Tap the first on-screen element whose text/description contains $1
tap_text() {
  local b
  b=$(ui | python3 -c "
import re,sys
x=sys.stdin.read(); t=sys.argv[1]
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    tx=re.search(r'text=\"([^\"]*)\"',n).group(1); cd=re.search(r'content-desc=\"([^\"]*)\"',n).group(1)
    if t == tx or t == cd:
        b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n)
        if b:
            x1,y1,x2,y2=map(int,b.groups()); hit=((x1+x2)//2,(y1+y2)//2)
            if len(sys.argv) < 3: break
if 'hit' in dir(): print(*hit)
" "$@")
  if [ -n "$b" ]; then adb shell input tap $b; echo "tapped $1 at $b"; else echo "NOT FOUND: $1"; fi
}

adb install -r app-debug.apk
adb logcat -c
# Sign in: drop saved settings in place (debug build allows run-as)
adb shell am start -n $PKG/.MainActivity; sleep 4
adb shell am force-stop $PKG
adb push test/prefs.xml /data/local/tmp/ice.xml
adb shell chmod 644 /data/local/tmp/ice.xml
adb shell run-as $PKG mkdir -p shared_prefs
adb shell run-as $PKG cp /data/local/tmp/ice.xml shared_prefs/ice.xml

adb shell am start -n $PKG/.MainActivity; sleep 10
shot 01_home
tap_text "Libraries"; sleep 3; shot 02_libraries
tap_text "Movies"; sleep 5; shot 03_movie_grid
adb shell input keyevent KEYCODE_BACK; sleep 1
tap_text "Settings"; sleep 3; shot 04_settings
tap_text "Home"; sleep 4
tap_text "The Long Road"; sleep 5; shot 05_detail
tap_text "Resume" ; tap_text "Play"; sleep 12; shot 06_player_playing
adb shell input tap 600 300; sleep 1; shot 07_player_controls
adb shell input keyevent KEYCODE_BACK; sleep 3; shot 08_mini_player
tap_text "Play/pause"; sleep 4; shot 09_mini_playing
tap_text "The Long Road" last; sleep 2; shot 10_expanded; sleep 4; shot 10b_expanded_later
adb shell input keyevent KEYCODE_BACK; sleep 3
tap_text "Stop"; sleep 3; shot 11_stopped

adb logcat -d > $OUT/logcat.txt
grep -A40 "FATAL EXCEPTION" $OUT/logcat.txt > $OUT/crash.txt || true
curl -s http://127.0.0.1:32400/_reset > $OUT/server_log.json || true
exit 0
