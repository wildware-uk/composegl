#!/usr/bin/env bash
S=${PROBE267_DATA:?set PROBE267_DATA to a scratch folder}
export JVMARGS="-XX:-DoEscapeAnalysis"
for side in before after; do "$(dirname "$0")"/sc-run.sh $side count count-$side; echo "count-$side $(tail -1 $S/sc/count-$side/run.log)"; done
echo COUNTS-DONE
