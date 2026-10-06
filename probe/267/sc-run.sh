#!/usr/bin/env bash
# sc-run.sh <side> <count|time> <tag>: showcase and snake scenes from that side's worktree.
set -u
SIDE=$1; MODE=$2; TAG=$3
S=${PROBE267_DATA:?set PROBE267_DATA to a scratch folder}/sc/$TAG
W=${PROBE267_WORKTREES:-/srv/ssd1/workspace/composegl/.claude/worktrees}
case $SIDE in before) SRC=$W/solo-267-before;; after) SRC=$W/solo-267;; esac
rm -rf $S; mkdir -p $S
cd $SRC
if [ "$MODE" = time ]; then CTX=desktop; else CTX=es3; fi
timeout 3000 xvfb-run -a -s '-screen 0 2500x2500x24' env __GLX_VENDOR_LIBRARY_NAME=nvidia COMPOSEGL_PROBE_OUT=$S COMPOSEGL_PROBE_MODE=$MODE COMPOSEGL_PROBE_CONTEXT=$CTX COMPOSEGL_PROBE_ONLY="${ONLY:-}" COMPOSEGL_PROBE_WARM="${WARM:-300}" COMPOSEGL_PROBE_MEASURE="${MEASURE:-600}" \
  ./gradlew :composegl-demo-snake:probe "-PprobeJvmArgs=${JVMARGS:--XX:-DoEscapeAnalysis}" --console=plain -q > $S/run.log 2>&1
echo "EXIT $?" >> $S/run.log
