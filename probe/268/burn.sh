#!/usr/bin/env bash
# burn.sh <n> <seconds>: n busy CPU threads for that long (pids in $PROBE268_DATA/burn.pids).
S=${PROBE268_DATA:?set PROBE268_DATA to a scratch folder}
: > $S/burn.pids
for i in $(seq $1); do timeout $2 python3 -c 'while 1: pass' & echo $! >> $S/burn.pids; done
