#!/usr/bin/env bash
# runj.sh <before|after> <tag> <warm> <measure> <cpus|all> [jvm args...]: one probe JVM, run directly
# rather than through Gradle, optionally pinned to cpus with taskset. Needs cp-<side>.txt from cp.sh.
# ONLY= picks scenes (default the moving home page), PACE= sleeps that many ms after each frame.
set -u
SIDE=$1; TAG=$2; WARM=$3; MEASURE=$4; CPUS=$5; shift 5
S=${PROBE268_DATA:?set PROBE268_DATA to a scratch folder}
W=${PROBE268_WORKTREES:-/srv/ssd1/workspace/composegl/.claude/worktrees}
SRC=$W/solo-268-$SIDE
JAVA=$(grep '^JAVA=' $S/cp-$SIDE.txt | cut -d= -f2-); CP=$(grep '^CP=' $S/cp-$SIDE.txt | cut -d= -f2-)
O=$S/runs/$TAG; rm -rf $O; mkdir -p $O
PIN=""; [ "$CPUS" != all ] && PIN="taskset -c $CPUS"
cd $SRC
timeout 900 xvfb-run -a -s '-screen 0 2500x2500x24' env __GLX_VENDOR_LIBRARY_NAME=nvidia COMPOSEGL_PROBE_OUT=$O COMPOSEGL_PROBE_MODE=time COMPOSEGL_PROBE_CONTEXT=desktop COMPOSEGL_PROBE_ONLY=${ONLY:-showcase-home-animated} COMPOSEGL_PROBE_WARM=$WARM COMPOSEGL_PROBE_MEASURE=$MEASURE COMPOSEGL_PROBE_PACE=${PACE:-} \
  $PIN $JAVA -XX:-DoEscapeAnalysis "$@" -cp "$CP" dev.wildware.composegl.snake.ProbeMainKt > $O/run.log 2>&1
echo "EXIT $? load $(cut -d' ' -f1 /proc/loadavg)" >> $O/run.log
