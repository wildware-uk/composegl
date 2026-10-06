#!/usr/bin/env bash
# mm-run.sh <side> <mode count|time> <tag>: Mega Merge's probe scenario against that side's composegl.
set -u
SIDE=$1; MODE=$2; TAG=$3
S=${PROBE267_DATA:?set PROBE267_DATA to a scratch folder}/mm
W=${PROBE267_WORKTREES:-/srv/ssd1/workspace/composegl/.claude/worktrees}
cd $W/solo-267-mm
mkdir -p "$S/prefs-$TAG"
rm -f "$S/$TAG.txt" "$S/$TAG.txt.csv"
rm -rf "$S/$TAG.txt.shaders"
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem
if [ "$MODE" = count ]; then P="-Pprobe=$S/$TAG.txt"; else P="-Ptiming=$S/$TAG.txt"; fi
timeout 3000 xvfb-run -a -s "-screen 0 1200x2500x24" \
  env MELON_GPU=1 $(MELON_GPU=1 bash tools/lib/gl-env.sh) \
  sh gradlew lwjgl3:run -PprefsDir="$S/prefs-$TAG" -Pscenario=probe_267 -PscenarioExit -Pportrait -PtouchDensity=2.75 \
    $P "-PprobeJvmArgs=${JVMARGS:--XX:-DoEscapeAnalysis}" -Dmaven.repo.local=$W/solo-267-m2-$SIDE --console=plain > "$S/$TAG.log" 2>&1
echo "EXIT $?" >> "$S/$TAG.log"
cp lwjgl3/build/scenario-report.txt "$S/$TAG-report.txt" 2>/dev/null
for f in probe267-board probe267-draft; do cp build/debug-screenshots/$f.png "$S/$TAG-$f.png" 2>/dev/null; done
