# #267: before and after, the scripts and readings behind the numbers

PROFILING ONLY, never for master.

## Readings (`data/raw`)

- `sc/count-<side>/report.txt`: showcase and snake counts, with each side's shader text in `shaders/`.
- `sc/time-<side>-<n>/`, `sc/scroll-<side>-<n>/`: every timed frame's CPU and garbage, one CSV per scene. `scroll-*` are the review-round-1 reruns of the scroll scenes, with a finger held on the page.
- `sc/home-*` and `sc/homelong-*`: the moving home page alone, cold (300-frame warm-up) and warm (3,000). See #268.
- `mm/`: Mega Merge's counts and every timed frame, by phase. Private game details are left out.
- `mali/<side>.json`: Mali-G57 and G52 cycles per shape program and path, from `mali.py`.
- `android/run-<side>-<n>/`: the emulator runs. The probe's lines (one per 300 frames), the phase marks, and the emulator's own frame stats.
- `summary.json`: what the tables are built from.

## Scripts

`tables.py`, `report_tables.py`, `chart.py`, `wentup.py` and `android_tables.py` read `data/raw`, or `PROBE267_DATA` if it is set. The run scripts need `PROBE267_DATA` set to a scratch folder, and the worktrees named in them.

- `sc-run.sh <before|after> <count|time> <tag>`: showcase and snake through `ProbeMain`. Set `ONLY=`, `WARM=` and `MEASURE=` to pick scenes and frames.
- `publish.sh <before|after>`: each side's library into its own Maven folder.
- `mm-run.sh <before|after> <count|time> <tag>`: Mega Merge's `probe_267` scenario against that library.
- `apk.sh <before|after>` and `measure.sh <before|after> <tag>`: the Android release build and one emulator pass. `waitfor.py` matches two reference screenshots of the game (the warning and the tutorial banner). They are not committed, because the game is private.
- `mm-all.sh`, `round2.sh`, `round3.sh`, `round4.sh`: the order the runs went in, sides alternating.
- `mali.py <shader dump> <out.json>`: the Mali Offline Compiler on each program and path.
- `cpu.jfc`: the 1 ms execution-sample settings tried for #268.
