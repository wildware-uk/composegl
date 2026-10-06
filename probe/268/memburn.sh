#!/usr/bin/env bash
# memburn.sh <n> <MB each> <seconds>: n memory walkers (memburn.c, built on first use) keeping caches and the memory bus busy.
S=${PROBE268_DATA:?set PROBE268_DATA to a scratch folder}
[ -x $S/memburn ] || gcc -O2 -o $S/memburn "$(dirname "$(readlink -f "$0")")/memburn.c"
: > $S/memburn.pids
for i in $(seq $1); do timeout $3 $S/memburn $2 & echo $! >> $S/memburn.pids; done
