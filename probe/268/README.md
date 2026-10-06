# #268: the moving home page after a cold start, measured again

PROFILING ONLY, never for master. The scripts behind the numbers on #268.

## What this branch adds to the #267 probe

The same additions are on `issue-268-probe-before` (the commit before #234) and
`issue-268-probe-after` (8c79d49d), so the two sides measure the same way.

- `RenderProbe.Hook.mark`: thread-CPU marks inside `UiRenderer.render` and `UiHost.frame`. Each
  timed frame's CSV gets the frame split into recompose, layout, focus and draw, and recompose
  further into clocks, apply, `sendFrame` (the recomposition itself) and the coroutine drain.
- `COMPOSEGL_PROBE_PACE=<ms>`: sleep after each frame, like a game at 60 fps, instead of drawing
  flat out.
- `PROBE-FRAME <n> uptime <ms>` lines every 100 frames, so `-XX:+PrintCompilation` times can be
  turned into frame numbers (`c2cmp.py`).

## Running it

Worktrees: `solo-268-before` and `solo-268-after` under `PROBE268_WORKTREES` (default
`.claude/worktrees`). `PROBE268_DATA` is a scratch folder for the runs.

```
probe/268/cp.sh before; probe/268/cp.sh after          # build each side, note its java and classpath
probe/268/runj.sh after cold-1 300 600 all             # frames 300-900 of a fresh JVM, all cores
probe/268/runj.sh after squeezed-1 300 600 22,23       # pinned to two cores
PACE=15 probe/268/runj.sh after paced-1 300 600 all    # paced like a 60 fps game
probe/268/runj.sh after curve-1 0 3000 all             # the whole warm-up
probe/268/runj.sh after jit-1 0 2000 all -XX:+PrintCompilation
```

Box conditions: `burn.sh <n> <s>` (busy CPU threads), `memburn.sh <n> <MB> <s>` (memory walkers),
`gpuburn.sh <s> <loop>` (`GpuBurn.java`, a heavy full-screen shader on the same GPU).

Reading the runs: `stats.py` (median, p10, p90), `phases.py` and `sub.py` (the split),
`series.py` / `pseries.py` (per bucket of frames), `fold.py` (by phase of the page's 336-frame
hit cycle), `wallcpu.py`, `c2cmp.py <before run.log> <after run.log> [filter]` (the frame each
method first reached C2 on each side), `curve_chart.py` (the picture on #268).
