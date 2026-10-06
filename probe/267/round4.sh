#!/usr/bin/env bash
S=${PROBE267_DATA:?set PROBE267_DATA to a scratch folder}
D="$(dirname "$0")"
export JVMARGS="-XX:-DoEscapeAnalysis"
for side in before after; do "$D"/publish.sh $side > $S/publish-$side-3.log 2>&1; echo "publish-$side $(tail -1 $S/publish-$side-3.log)"; done
for side in before after; do "$D"/apk.sh $side; echo "apk-$side $(tail -1 $S/android/apk-$side.log) $(ls -la $S/android/megamerge-$side.apk 2>&1 | awk '{print $5}')"; done
export ONLY=scroll
for r in 1 2 3 4 5; do for side in before after; do "$D"/sc-run.sh $side time scroll-$side-$r; echo "scroll-$side-$r $(tail -1 $S/sc/scroll-$side-$r/run.log) load $(cut -d' ' -f1 /proc/loadavg)"; done; done
export ONLY=showcase-home-animated
for r in 1 2 3 4 5 6; do for side in before after; do "$D"/sc-run.sh $side time home-$side-$r; echo "home-$side-$r $(tail -1 $S/sc/home-$side-$r/run.log) load $(cut -d' ' -f1 /proc/loadavg)"; done; done
echo ROUND4-DONE
