#!/usr/bin/env bash
# publish.sh <side>: publish the composegl modules Mega Merge uses, from that side's worktree, into that side's own Maven folder.
set -u
SIDE=$1
W=${PROBE267_WORKTREES:-/srv/ssd1/workspace/composegl/.claude/worktrees}
case $SIDE in before) SRC=$W/solo-267-before;; after) SRC=$W/solo-267;; esac
M2=$W/solo-267-m2-$SIDE
cd $SRC
T=()
for m in composegl-ui composegl-render composegl-effects composegl-game composegl-debug composegl-testing; do
  T+=(":$m:publishKotlinMultiplatformPublicationToMavenLocal" ":$m:publishJvmPublicationToMavenLocal")
done
timeout 1800 ./gradlew "${T[@]}" :composegl-gdx:publishToMavenLocal -Dmaven.repo.local=$M2 --console=plain -q
echo EXIT $?
