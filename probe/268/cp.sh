#!/usr/bin/env bash
# cp.sh <before|after>: builds that side's probe and writes its java and classpath to cp-<side>.txt.
set -u
S=${PROBE268_DATA:?set PROBE268_DATA to a scratch folder}
W=${PROBE268_WORKTREES:-/srv/ssd1/workspace/composegl/.claude/worktrees}
cd $W/solo-268-$1
timeout 900 ./gradlew -q -I "$(dirname "$(readlink -f "$0")")/cp.init.gradle.kts" :composegl-demo-snake:printProbeCp --console=plain > $S/cp-$1.txt 2>&1
echo "EXIT $?"
