#!/usr/bin/env bash
# apk.sh <before|after>: Mega Merge's release APK (R8, debug-signed) against that side's composegl.
set -u
SIDE=$1
S=${PROBE267_DATA:?set PROBE267_DATA to a scratch folder}
W=${PROBE267_WORKTREES:-/srv/ssd1/workspace/composegl/.claude/worktrees}
mkdir -p $S/android
cd $W/solo-267-mm
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem
timeout 3000 sh gradlew android:assembleRelease -Dmaven.repo.local=$W/solo-267-m2-$SIDE --console=plain > $S/android/apk-$SIDE.log 2>&1
echo "EXIT $?" >> $S/android/apk-$SIDE.log
cp android/build/outputs/apk/release/android-release.apk $S/android/megamerge-$SIDE.apk 2>/dev/null
cp android/build/outputs/mapping/release/mapping.txt $S/android/mapping-$SIDE.txt 2>/dev/null
