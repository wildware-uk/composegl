#!/usr/bin/env bash
# gpuburn.sh <seconds> <loop>: GpuBurn.java in the background, keeping the GPU busy. Stop it early with pkill -f "Gpu[B]urn".
S=${PROBE268_DATA:?set PROBE268_DATA to a scratch folder}
CP=$(grep '^CP=' $S/cp-after.txt | cut -d= -f2-); JAVA=$(grep '^JAVA=' $S/cp-after.txt | cut -d= -f2-)
cd "$(dirname "$(readlink -f "$0")")"
timeout $(( $1 + 30 )) xvfb-run -a -s '-screen 0 2000x1200x24' env __GLX_VENDOR_LIBRARY_NAME=nvidia $JAVA -cp "$CP" GpuBurn.java $1 $2 > $S/gpuburn.log 2>&1 &
