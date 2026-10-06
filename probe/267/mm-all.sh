#!/usr/bin/env bash
S=${PROBE267_DATA:?set PROBE267_DATA to a scratch folder}
export JVMARGS="-XX:-DoEscapeAnalysis"
for side in before after; do "$(dirname "$0")"/mm-run.sh $side count count-$side; echo "count-$side $(tail -1 $S/mm/count-$side.log) $(grep -m1 'screen is' $S/mm/count-$side.txt | grep -o 'screen is [0-9]*')"; done
for r in 1 2 3; do for side in before after; do "$(dirname "$0")"/mm-run.sh $side time time-$side-$r; echo "time-$side-$r $(tail -1 $S/mm/time-$side-$r.log) load $(cut -d' ' -f1 /proc/loadavg)"; done; done
echo MM-ALL-DONE
