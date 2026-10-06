#!/usr/bin/env bash
# measure.sh <before|after> <tag>: Mega Merge on my read-only emulator copy (emulator-5590).
# Fresh data, launch, past the warning and the tutorial, the run-start draft armed and held,
# skipped, four fruit dropped and the board held. Every phase's start and end are logged, and the
# probe's lines (one per 300 frames) come back with their times.
set -u
SIDE=$1; TAG=$2
D=$(dirname "$0")
A="$HOME/Android/Sdk/platform-tools/adb -s emulator-5590"
PKG=uk.wildware.fruitgame
OUT=$D/$TAG; rm -rf $OUT; mkdir -p $OUT
mark() { echo "$(date +%s.%N) $1" >> $OUT/phases.txt; }
$A install -r $D/megamerge-$SIDE.apk > $OUT/install.txt 2>&1
$A shell pm clear $PKG > /dev/null
$A logcat -c
$A shell am start -n $PKG/.android.AndroidLauncher > /dev/null
W="python3 $D/waitfor.py"
$W $D/nav2.png 60 900 1020 1560 120 || exit 1   # photosensitivity warning
$A shell input tap 540 1449; sleep 3          # Got it
$A shell input tap 540 534                    # Play
$W $D/nav4.png 150 270 930 540 60 || exit 1    # tutorial banner
$A shell input tap 561 438; sleep 3           # Skip tutorial
$A shell touch /sdcard/Android/data/$PKG/files/arm-draft
sleep 1.5
mark draft-dealing-start; sleep 3; mark draft-dealing-end
sleep 2
mark draft-open-start; sleep 30; mark draft-open-end
$A exec-out screencap -p > $OUT/draft.png
$A shell input tap ${SKIP_X:-878} ${SKIP_Y:-1194}; sleep 3   # Skip the draft
for x in 300 700 500 400; do $A shell input tap $x 1250; sleep 1.5; done
sleep 10
mark board-still-start; sleep 30; mark board-still-end
$A exec-out screencap -p > $OUT/board.png
$A logcat -d -v epoch -s FrameProbe267:I > $OUT/probe.txt
$A logcat -d -v epoch -s EGL_emulation:D | grep app_time_stats > $OUT/frametimes.txt
$A shell input keyevent KEYCODE_HOME
echo DONE
