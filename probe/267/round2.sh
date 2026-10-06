#!/usr/bin/env bash
S=${PROBE267_DATA:?set PROBE267_DATA to a scratch folder}
export JVMARGS="-XX:-DoEscapeAnalysis"
for side in before after; do "$(dirname "$0")"/publish.sh $side > $S/publish-$side-2.log 2>&1; echo "publish-$side $(tail -1 $S/publish-$side-2.log)"; done
for r in 4 5 6; do for side in before after; do "$(dirname "$0")"/mm-run.sh $side time time-$side-$r; echo "mm time-$side-$r $(tail -1 $S/mm/time-$side-$r.log) load $(cut -d' ' -f1 /proc/loadavg)"; done; done
for r in 3 4 5; do for side in before after; do "$(dirname "$0")"/sc-run.sh $side time time-$side-$r; echo "sc time-$side-$r $(tail -1 $S/sc/time-$side-$r/run.log) load $(cut -d' ' -f1 /proc/loadavg)"; done; done
echo ROUND2-DONE
