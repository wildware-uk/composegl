## Before and after: yes, ComposeGL is much faster (solo-dev-1)

**Plain answer.** Yes. On the same screens, today's master does a fraction of the work the commit before #234 did. One reading got slower: the moving home page early after a cold start, filed as #268. A few counts went up by design; they are listed under "Did anything go up?".

- **Mega Merge's card draft** (its busiest screen):
  - Estimated phone GPU time fell **70%** (11.1 → 3.3 ms on a mid-range Mali). It paints half the pixels, a fifth of the offscreen-picture area, and sends 83% fewer GL calls.
  - The game thread's CPU per frame on this desktop fell **74%** (7.4 → 1.9 ms).
  - ComposeGL's own part of it fell **92%** (6.5 → 0.5 ms). That part is `UiRenderer.render`, including the graphics driver's time for the GL calls it makes.
  - **On Android** (an emulator, release builds), the open draft's GL-thread CPU per frame fell **29-43%**, and ComposeGL's part of it 40-48%. The range comes from the random deal:
    - With plain cards: 11.0 → 7.8 ms. On both sides the median second drew 60-61 frames. The old build's worst second drew 54; the new build's, 60.
    - With a glowing card dealt: 15.4 → 8.8 ms. The old build dropped frames: 4 of 30 seconds drew under 58, the worst only 44. The new build had one second under 58, at 57.
- **Showcase and snake:**
  - GL calls per frame are **70-90% lower** on showcase pages, and 49-76% lower on snake.
  - Garbage per frame is **74-99% lower**. Still showcase pages went from 38-142 KB a frame to 2 KB or less; the effects page is the exception at 5 KB.
  - Estimated phone GPU time is **57-74% lower**.
  - CPU time per frame is **clearly lower on 12 of 17 screens**: 12-63% on the median frame.
    - "Clearly" means the after run was lower in at least 4 of the 5 back-to-back pairs.
    - On the other five, the median frame shows no clear change: the moving home and game pages, the still surfaces page, and snake's menu and play. Their fastest tenth of frames (the reading this shared machine disturbs least) is still 31-63% lower.
  - **The one slower reading** is on the moving home page, early after a cold start of the desktop JVM.
    - Frames 300-900 cost 27% more on the median frame: 0.48 → 0.61 ms, over 6 alternating runs that do not overlap.
    - That is 5-15 s of a game drawn at 60 fps; the probe draws as fast as it can. Frames 900-3,000 were not measured, so when the slowdown ends is not known.
    - Once warm, the same page is 49% cheaper: 0.38 → 0.19 ms, over 1,200 frames measured after a 3,000-frame warm-up.
    - Today's code on that page takes longer to reach full JIT speed. Filed as #268.

**What still costs the most:**
1. **Pixels on the GPU.**
   - The open draft still paints 2.1 screens a frame. 72% of its estimated GPU time is letters, pictures and box middles, already on the cheapest shader path.
   - Every showcase page also paints a full-screen box (the `screen` style, `#0B0E13`) over a clear of the same colour. That is about one screen of the 1.6-2.2 painted, roughly 1.4 ms of the 2.3-3.3 ms estimate.
2. **Mega Merge's own code.**
   - On the open draft, the game thread spends 1.4 of its 1.9 ms outside `UiRenderer.render`.
   - The game makes about 213 KB of garbage a frame; ComposeGL makes 12 KB.
3. **ComposeGL's garbage while things move.**
   - While the draft's cards deal it averages 75 KB a frame. The median frame makes 32 KB; one frame makes about 5 MB, on both sides.
   - Moving or scrolling showcase pages make 0.3-16 KB a frame, the most being the gear page while it scrolls (16 KB, down from 63).

![Today as a share of before](https://raw.githubusercontent.com/wildware-uk/composegl/issue-267-before-after-measurement/probe/267/before-after.png)

### How it was measured

- **Sides.**
  - Before: `83b51719`, master just before #234.
  - After: master `8c79d49d`.
  - Both run the same probe code, on two branches pushed for this (see Reproducing).
  - The before side has one other change: a `hostTargetChanged()` that does nothing. #238 added that method and today's Mega Merge calls it. The old canvas asks the driver for the window's framebuffer every frame anyway, so there is nothing for the method to do there.
- **Screens.**
  - **Mega Merge:** its desktop build, master `a53baa481`, the same commit on both sides. Window 1080x2400 (the scenario sets it), touch density 2.75. Phases: the board held still for 5 s, the run-start draft while the cards deal for 3 s, and the open draft for 5 s.
  - **Showcase:** laid out 400 design units wide at 1080x2400, as the web showcase lays out a phone. Scenes are still, moving (reduced motion off), or scrolling. In a scrolling scene, a finger held on the page's left margin drags the page up and back down, 6 units a frame, and never lets go, so the page itself scrolls on every measured frame.
  - **Snake:** 2400x1080, on the menu and while playing. The probe steers so the snake stays alive.
- **Counts:** draw calls, GL calls, offscreen pictures and the pixels painted.
  - Counted with #242's GL counter (`ProbeGl`) wrapped round the renderer's GL. Each figure is the median over the counted frames.
  - Showcase and snake count the same 600 frames that are timed: 300 frames in, after warm-up.
  - The counter now reads both vertex layouts (31 floats before, 22 packed slots after), tells the three shape programs apart, and remembers the layout of each vertex array.
  - Showcase and snake ran on OpenGL ES 3, exactly as #242 did. Mega Merge ran on its own desktop GL.
  - "Pixels painted" counts ComposeGL's draws only. The game's own LibGDX drawing (the board and its fruit) is not counted, as in #242.
- **CPU and garbage:** a second run with the counter off.
  - Per frame, the thread's CPU time and the bytes it allocated, read from `ThreadMXBean`.
  - On the real GPU (desktop GL on an RTX 2070 SUPER). Under Xvfb, an ES context only comes from Mesa's software renderer, which would bury ComposeGL's time under the CPU painting pixels.
  - The JVM ran with escape analysis off, so garbage counts match Android's. #241 found desktop and phone within 0-15% that way.
  - Showcase and snake: input and render, timed per frame. 300 warm-up frames, then 600 measured. 5 runs per side, alternating before and after.
  - The two scroll rows come from their own reruns (review round 1). Each was a fresh JVM holding only those two scenes, with widgets first after the usual 300-frame warm-up. That is colder than the other rows, which ran after earlier scenes had warmed the JIT; #268 is about exactly this. The direction is the same.
  - Mega Merge: everything the game thread did from one frame to the next. 6 runs per side, alternating, with the first quarter of each phase dropped. In runs 4-6, `UiRenderer.render` was also timed on its own: ComposeGL's share, graphics driver included. (#241's "toolkit code" column left the driver out, so the two are not the same measure.)
  - Each CPU figure is the median, across runs, of each run's median frame. Each garbage figure is the mean per frame; it is the same in every run.
  - The one exception is Mega Merge's board.
    - Before, its frames come in two kinds: ComposeGL's part is either under about 1.6 ms or 4-7 ms, and the mix changes from run to run. So the median flips between the two.
    - After, they do not split: ComposeGL's part is 0.19-1.60 ms in every frame of runs 4-6.
    - So the board's CPU is given as the mean per frame. That still varies between runs (3.0-6.4 ms before), but every new-build run's mean (1.0-2.9 ms) is below every old-build run's.
- **Sanity check against #242.** #242 measured nearby commits, not these two: ComposeGL `979a68da`, which already had #234, #236 and #238, and Mega Merge `fc704697`.
  - The before side's showcase and snake GPU estimates match #242's (home 5.3 ms, effects 9.1 ms, snake menu 6.9 ms).
  - The draft matches too: 59 draw calls while dealing and 92 open, with 4.88 and 4.21 screens of pixels against #242's 4.87 and 4.2.
  - The board does not quite match: 39 draw calls, 1,762 GL calls and 0.44 screens here, against #242's 36, 1,655 and 0.35. It is a different game commit, and #242's ComposeGL already trimmed rounded clips in the shader (#236).
- **Phone GPU (an estimate, not a device timing):**
  - Pixels painted, per shader program and path, times the cycles Arm's Mali Offline Compiler 8.7 gives for each side's exact shader text. Then ÷ (2 cores × 950 MHz) for a Mali-G57 MC2, as #242 did.
  - Letters and pictures cost the whole program's shortest path. Every other path costs a copy of the program with its switches fixed, which is a lower bound.
  - Before: one program, 46 registers (half occupancy), letters 1.5 cycles, a plain box 2.1.
  - After: the light program at 30 registers (full occupancy), letters 1.0 cycle.
- **Caveats.**
  - Desktop CPU times are far smaller than a phone's; the ratios are the point.
  - This box is shared (load 5-14 during the runs), so one run's CPU can be 2× another's. That is why there are several alternating runs and medians.
- **Pictures:**
  - Compared by `pixdiff.py` (the largest difference in any one channel, of 255).
  - On every showcase page, 84-1,120 pixels differ by 1 level, which cannot be seen. One pixel on the widgets page differs by 2. Colours now travel as bytes (#253).
  - The effects page also differs along its rounded-clip shapes: 3,404 pixels by more than 1, at most 34, all inside those shapes' box. Rounded clips are now trimmed in the shader (#236).
  - Snake differs only where the food lands, which is random. The menu shows the board behind it, so it differs there too.
  - Mega Merge's draft differs only in anti-aliasing along the cards' edges and letters. The cards are now drawn through a transform rather than into a picture and back (#235, #239).

Mega Merge is private, so its pictures are on the dashboard card rather than here.

### Mega Merge

| Screen | Game-thread CPU, ms (6 runs) | ComposeGL's own, ms (runs 4-6; game thread in the same runs) | Garbage per frame, KB, mean (ComposeGL's part) | Draw calls | GL calls | Offscreen pictures (their px) | Pixels painted (screens) | Phone GPU est., ms |
|---|---|---|---|---|---|---|---|---|
| Board, still | mean 4.18 → 2.25 (-46%); runs 3.0-6.4 → 1.0-2.9 | mean 2.86 → 0.61 (-79%); runs 2.0-4.0 → 0.4-0.8 | 74 → 45 (-39%); ComposeGL 32 → 6.6 | 39 → 29 | 1,762 → 197 (-89%) | 5 (2.10 M) → 0 (0.00 M) | 0.44 → 0.26 (-42%) | 1.3 → 0.8 (-39%) |
| Card draft, cards dealing | 8.28 → 3.35 (-59%) | 6.54 → 1.50 (-77%); game thread 8.34 → 3.26 | 331 → 290 (-12%); ComposeGL 112 → 75 | 59 → 48 | 2,738 → 540 (-80%) | 6 (1.31 M) → 2 (0.49 M) | 4.88 → 2.26 (-54%) | 13.5 → 3.6 (-74%) |
| Card draft, open | 7.38 → 1.90 (-74%) | 6.48 → 0.54 (-92%); game thread 7.22 → 1.92 | 299 → 225 (-25%); ComposeGL 78 → 12 | 92 → 77 | 4,278 → 738 (-83%) | 8 (0.89 M) → 4 (0.16 M) | 4.21 → 2.10 (-50%) | 11.1 → 3.3 (-70%) |

### Showcase and snake

| Screen | CPU, ms | Garbage, KB | Draw calls | GL calls | Offscreen pictures (their px) | Pixels painted (screens) | Phone GPU est., ms |
|---|---|---|---|---|---|---|---|
| Home, moving | 0.32 → 0.30 (-5%); no clear change (lower in 3 of 5 pairs) | 54 → 4.2 (-92%) | 3 → 3 | 135 → 41 (-70%) | 0 (0.00 M) → 0 (0.00 M) | 1.80 → 1.59 (-12%) | 5.3 → 2.3 (-57%) |
| Widgets, still | 0.36 → 0.18 (-49%) | 64 → 0.3 (-99%) | 6 → 3 | 249 → 41 (-84%) | 0 (0.00 M) → 0 (0.00 M) | 2.64 → 1.95 (-26%) | 8.1 → 2.9 (-65%) |
| Widgets, finger scrolling | 0.56 → 0.23 (-58%) | 79 → 5.9 (-93%) | 7 → 5 | 283 → 49 (-83%) | 0 (0.00 M) → 0 (0.00 M) | 2.88 → 2.08 (-28%) | 8.9 → 3.0 (-66%) |
| Game widgets, typewriter running | 0.33 → 0.30 (-10%); no clear change (lower in 3 of 5 pairs) | 144 → 4.7 (-97%) | 16 → 15 | 581 → 77 (-87%) | 0 (0.00 M) → 0 (0.00 M) | 2.75 → 2.04 (-26%) | 8.4 → 3.0 (-64%) |
| Animation page, moving | 0.36 → 0.13 (-63%) | 54 → 1.9 (-97%) | 12 → 5 | 458 → 49 (-89%) | 1 (0.31 M) → 0 (0.00 M) | 3.04 → 2.07 (-32%) | 9.4 → 3.0 (-68%) |
| Effects, still | 0.49 → 0.22 (-55%) | 67 → 5.1 (-92%) | 33 → 20 | 1,224 → 172 (-86%) | 5 (0.44 M) → 2 (0.09 M) | 2.93 → 2.08 (-29%) | 9.1 → 3.1 (-66%) |
| Surfaces, still | 0.23 → 0.11 (-53%); no clear change (lower in 3 of 5 pairs) | 38 → 0.3 (-99%) | 29 → 19 | 1,019 → 105 (-90%) | 0 (0.00 M) → 0 (0.00 M) | 2.68 → 1.97 (-26%) | 8.5 → 3.3 (-62%) |
| Gear, finger scrolling | 0.55 → 0.39 (-30%) | 63 → 16 (-74%) | 5 → 5 | 207 → 49 (-76%) | 0 (0.00 M) → 0 (0.00 M) | 2.92 → 2.07 (-29%) | 9.0 → 3.0 (-67%) |
| Text, still | 0.24 → 0.16 (-33%) | 50 → 1.3 (-97%) | 12 → 5 | 449 → 49 (-89%) | 0 (0.00 M) → 0 (0.00 M) | 2.53 → 1.88 (-26%) | 7.7 → 2.7 (-65%) |
| HUD, still | 0.43 → 0.26 (-40%) | 128 → 2.0 (-98%) | 6 → 5 | 245 → 49 (-80%) | 0 (0.00 M) → 0 (0.00 M) | 2.97 → 2.25 (-24%) | 9.1 → 3.2 (-65%) |
| Snake, menu | 0.15 → 0.09 (-39%); no clear change (lower in 3 of 5 pairs) | 14 → 1.9 (-86%) | 5 → 5 | 204 → 48 (-76%) | 0 (0.00 M) → 0 (0.00 M) | 2.22 → 1.58 (-29%) | 6.9 → 2.3 (-67%) |
| Snake, playing | 0.06 → 0.06 (-12%); no clear change (lower in 3 of 5 pairs) | 7.7 → 1.9 (-75%) | 1 → 1 | 61 → 31 (-49%) | 0 (0.00 M) → 0 (0.00 M) | 1.17 → 0.63 (-46%) | 3.8 → 1.0 (-74%) |

<details><summary>The other showcase scenes</summary>

| Screen | CPU, ms | Garbage, KB | Draw calls | GL calls | Offscreen pictures (their px) | Pixels painted (screens) | Phone GPU est., ms |
|---|---|---|---|---|---|---|---|
| Home, still | 0.32 → 0.28 (-12%) | 51 → 0.3 (-99%) | 3 → 3 | 135 → 41 (-70%) | 0 (0.00 M) → 0 (0.00 M) | 1.80 → 1.59 (-12%) | 5.3 → 2.3 (-57%) |
| Game widgets, still | 0.35 → 0.27 (-23%) | 142 → 0.7 (-99%) | 16 → 15 | 581 → 77 (-87%) | 0 (0.00 M) → 0 (0.00 M) | 2.75 → 2.04 (-26%) | 8.4 → 3.0 (-64%) |
| Gear, still | 0.32 → 0.21 (-34%) | 52 → 0.7 (-99%) | 4 → 3 | 173 → 41 (-76%) | 0 (0.00 M) → 0 (0.00 M) | 2.72 → 1.97 (-28%) | 8.4 → 2.8 (-66%) |
| Effects, moving | 0.36 → 0.18 (-51%) | 68 → 5.1 (-92%) | 33 → 20 | 1,224 → 172 (-86%) | 5 (0.44 M) → 2 (0.09 M) | 2.93 → 2.08 (-29%) | 9.1 → 3.1 (-66%) |
| Surfaces, moving | 0.27 → 0.12 (-54%) | 38 → 0.3 (-99%) | 29 → 19 | 1,019 → 105 (-90%) | 0 (0.00 M) → 0 (0.00 M) | 2.68 → 1.97 (-26%) | 8.5 → 3.3 (-62%) |

</details>

<details><summary>CPU in every run, the fastest tenth of frames, and the Mali-G52 estimate</summary>

Each run's median frame in ms, in the order the runs went (sides alternating). The fastest tenth is the median, across runs, of each run's 10th-percentile frame.

| Screen | Before, each run | After, each run | Fastest tenth of frames, ms | Mali-G52 est., ms |
|---|---|---|---|---|
| Board, still | 2.02 5.04 2.75 1.92 5.51 6.89 | 2.36 2.66 0.89 1.15 2.07 3.23 | 1.45 → 1.22 (-16%) | 1.3 → 0.8 |
| Card draft, cards dealing | 8.22 7.17 8.53 8.06 8.38 8.34 | 3.45 4.92 2.82 2.29 3.26 3.70 | 6.89 → 2.31 (-66%) | 13.5 → 4.2 |
| Card draft, open | 6.54 7.55 7.92 7.18 7.22 10.05 | 1.88 2.22 0.99 1.73 1.92 2.04 | 6.56 → 1.07 (-84%) | 11.1 → 3.9 |
| Home, moving | 0.30 0.39 0.32 0.31 0.40 | 0.31 0.30 0.21 0.29 0.86 | 0.21 → 0.14 (-34%) | 5.3 → 2.8 |
| Widgets, still | 0.36 0.30 0.36 0.46 0.33 | 0.15 0.32 0.18 0.18 0.31 | 0.28 → 0.09 (-67%) | 8.1 → 3.5 |
| Widgets, finger scrolling | 0.50 0.59 0.56 0.70 0.52 | 0.20 0.23 0.24 0.46 0.21 | 0.43 → 0.15 (-66%) | 8.9 → 3.7 |
| Game widgets, typewriter running | 0.33 0.60 0.29 0.30 0.62 | 0.27 0.82 0.16 0.30 0.46 | 0.30 → 0.17 (-44%) | 8.4 → 3.6 |
| Animation page, moving | 0.26 0.45 0.31 0.36 0.51 | 0.13 0.45 0.10 0.12 0.15 | 0.24 → 0.09 (-64%) | 9.4 → 3.7 |
| Effects, still | 0.43 0.54 0.58 0.44 0.49 | 0.25 0.22 0.15 0.16 0.45 | 0.35 → 0.13 (-64%) | 9.4 → 3.8 |
| Surfaces, still | 0.24 0.21 0.23 0.50 0.23 | 0.09 0.25 0.07 0.11 0.37 | 0.20 → 0.07 (-63%) | 8.7 → 4.0 |
| Gear, finger scrolling | 0.40 0.66 0.51 0.72 0.55 | 0.39 0.34 0.47 0.41 0.24 | 0.37 → 0.24 (-34%) | 9.0 → 3.7 |
| Text, still | 0.23 0.28 0.32 0.22 0.24 | 0.12 0.16 0.09 0.20 0.46 | 0.21 → 0.11 (-50%) | 7.7 → 3.3 |
| HUD, still | 0.46 0.35 0.43 0.39 0.44 | 0.31 0.21 0.26 0.24 0.31 | 0.35 → 0.13 (-62%) | 9.1 → 3.9 |
| Snake, menu | 0.24 0.09 0.09 0.19 0.15 | 0.09 0.22 0.10 0.08 0.08 | 0.08 → 0.05 (-39%) | 6.9 → 2.8 |
| Snake, playing | 0.08 0.06 0.05 0.05 0.32 | 0.05 0.15 0.04 0.09 0.06 | 0.04 → 0.03 (-31%) | 3.8 → 1.1 |
| Home, still | 0.32 0.32 0.41 0.34 0.29 | 0.20 0.28 0.31 0.14 0.36 | 0.27 → 0.13 (-50%) | 5.3 → 2.8 |
| Game widgets, still | 0.35 0.27 0.43 0.28 0.47 | 0.30 0.14 0.20 0.29 0.27 | 0.27 → 0.12 (-56%) | 8.4 → 3.6 |
| Gear, still | 0.32 0.33 0.48 0.27 0.32 | 0.22 0.21 0.09 0.20 0.33 | 0.23 → 0.10 (-58%) | 8.4 → 3.5 |
| Effects, moving | 0.36 0.32 0.31 0.46 0.60 | 0.32 0.41 0.18 0.11 0.15 | 0.31 → 0.11 (-63%) | 9.4 → 3.8 |
| Surfaces, moving | 0.27 0.20 0.20 0.43 0.32 | 0.12 0.21 0.13 0.10 0.10 | 0.20 → 0.07 (-66%) | 8.7 → 4.0 |

</details>

How to read the tables:
- **Pixels painted** counts every pixel each ComposeGL draw covers, on the screen and into pictures, in screens of 1080x2400.
- **Offscreen pictures** is how many framebuffers were drawn into in a frame, and their size in pixels.
- **CPU, showcase and snake:** ComposeGL's frame, which is input and render.
- **CPU, Mega Merge:** the whole game thread, with ComposeGL's own `UiRenderer.render` beside it (graphics driver included).

### Mega Merge on Android (an emulator)

- **Device:** my own read-only copy of `qa_pixel`, on its own port; the shared instance was not touched. It is a Pixel 6 screen (1080x2400), Android 15 x86_64, 4 cores, with GLES passed through the emulator to the RTX 2070.
- **Builds:** release builds with R8, signed with the debug key. Game master `a53baa481` against each side's library.
- **Each pass:**
  1. The game's data is cleared and the game launched.
  2. The warning and the tutorial are passed.
  3. The run-start draft is dealt. A fresh profile owns no Seed-tree node, so this goes through the game's own `debugArmRunStartDraft`.
  4. The draft is held open for 30 s, then skipped.
  5. Four fruit are dropped, and the board is held for 30 s.
- **Runs:** 2 passes per side, alternating.
- **Readings:**
  - A listener round the game logs, every 300 frames, the GL thread's CPU per frame and ComposeGL's `UiRenderer.render` within it. Each CPU figure is a pass's median over the 300-frame windows that lie wholly inside a phase, 5 per phase.
  - The emulator's own `app_time_stats`, from the game's process only, give the game's time per frame and the frames drawn in each second of the phase. They come from about 30 one-second readings per phase.

| Pass | Cards dealt | Open draft: frames a second, median (lowest second) | seconds under 58 | game's time per frame (`app_time_stats`), ms | GL-thread CPU per frame, ms | of which ComposeGL, ms | Board: GL-thread CPU, ms | of which ComposeGL, ms |
|---|---|---|---|---|---|---|---|---|
| before 1 | plain | 60 (54) | 1 of 30 | 12.11 | 11.00 | 8.26 | 7.24 | 3.88 |
| after 2 | plain | 60 (60) | 0 of 30 | 8.63 | 7.80 | 4.95 | 5.50 | 1.85 |
| before 2 | one glowing (uncommon) | 59 (44) | 4 of 30 | 16.52 | 15.40 | 11.86 | 7.37 | 3.92 |
| after 1 | one glowing (uncommon) | 60 (57) | 1 of 30 | 9.93 | 8.79 | 6.18 | 6.33 | 1.99 |

- **The deal matters.** The cards are dealt at random, and a glowing uncommon card costs more to draw. So each pass is set against the pass on the other side that dealt the same kind of hand.
- **Board:** 7.2-7.4 → 5.5-6.3 ms on the GL thread. ComposeGL's part went 3.9 → 1.9-2.0 ms. The median second drew 60-61 frames on both sides; the old build's plain pass had one second at 56.
- **Frame rate:** the game asks for 60 fps.
  - With a plain hand, the median second drew 60-61 frames on both sides. The old build had one second at 54; the new build had none under 60. The gain shows mostly as time left over in each 16.7 ms frame: 12.1 → 8.6 ms.
  - With a glowing card dealt, the old build ran out of time. Its frames took 16.5 ms against a 16.7 ms budget, it drew under 58 frames in 4 of the 30 seconds, and its worst second drew 44.
  - The new build took 9.9 ms a frame and drew 57 or more every second.
- **Why ComposeGL's share is large here:** its part includes every GL call it makes, and the emulator makes each call expensive by shipping it to the host GPU (#241 saw the same). So ComposeGL's share is much larger here than on the desktop.
- **#241's 40 fps** for this draft on the same emulator came from other commits of both the game (`1ae4a71e6`) and ComposeGL (`b4d3a7fd`), so it is not comparable with these.
- **Not measured on Android:** the draft while the cards deal. It lasts 3 s, shorter than one 300-frame reading.

### Where the speed came from

- **GL calls, 70-90% fewer:**
  - #244: the device sends only the state that changed, and the batch cuts only on a real change.
  - #238: the window's framebuffer is asked for once a frame, not once per canvas.
- **Offscreen pictures:**
  - #235 and #239: a still scale, with or without a glow inside, is drawn through a transform. This removed the board's full-screen 1080x1920 caption picture, which was redrawn every frame, and most of the draft's card pictures.
  - #236: a rounded clip is trimmed in the shader.
  - #243: a picture that changes size reuses a pooled one.
- **GPU time per pixel:**
  - #245: letters, pictures and plain boxes use a light program, at full occupancy on Mali. A letter costs 1.0 cycle instead of 1.5.
  - #246: a box's flat middle goes through the picture path, and an outline's clear middle is not drawn at all.
  - Pixels painted fell 12-46% on the showcase and snake, mostly through #246. On Mega Merge they fell 42-54%, mostly through fewer offscreen pictures.
- **CPU:**
  - #234: a flush hands its vertices to GL in one copy, not one float at a time.
  - #247: layout measures only what changed.
  - #250: a scroll step re-lays out the scroll area rather than recomposing it.
  - `f4e9de04`: a frame where nothing changed skips layout.
  - #254: content off the screen is skipped.
  - #253: colours are packed into bytes, so a vertex is 22 slots, not 31.
  - The GL-call cuts above.
- **Garbage:**
  - #248: hot loops walk their lists by index.
  - #251: focus is refreshed only when the tree changed.
  - #252: the canvas reuses its frame state.
  - #249 and #259: an idle scroll area sleeps.
  - #241's typewriter fix.
  - #257, #258, #262 and #263: still screens make nothing.

### Did anything go up?

One reading got slower, and it is filed as #268: the moving home page right after a cold start (the last row below). Apart from that, no screen's CPU, garbage, GL calls, draw calls, pixels or GPU estimate went up. Some GL counts did go up, each with the changes, and none costs anything that shows:

| What rose | Where | Before → after | Why | What it costs |
|---|---|---|---|---|
| Shader program switches | surfaces page; Mega Merge board; open draft; effects page | 2 → 18; 2 → 16; 14 → 39; 7 → 12 | #245 split the one shape program into three, so that letters, pictures and plain boxes run at full occupancy. Lit surfaces, inner shades and colour runs now switch to the full program. | On the surfaces page, 14 draw calls exist only because of a program change. Even so, draw calls fell overall (29 → 19 there, 92 → 77 on the open draft), and GL calls fell 83-90%. CPU fell clearly on the moving surfaces page, the effects page, the board (by its mean) and the open draft. On the still surfaces page, the median frame shows no clear change. |
| Quads per frame | snake menu; snake playing; draft while dealing | 216 → 255; 114 → 135; 636 → 771 | #246 draws a box as its flat middle plus its edge, so one box can be several quads. | Vertex bytes still fell on all three (105 → 88 KB, 55 → 46 KB, 308 → 265 KB), because a vertex is 88 bytes instead of 124. Pixels and the GPU estimate fell too. |
| Scissor changes, and GL enable/disable switches | Mega Merge board; draft while dealing; open draft; effects page | Scissor: 0 → 6; 0 → 11; 6 → 19; 2 → 6. Enable/disable: 0 → 10; 0 → 10; 12 → 26 on the three Mega Merge screens. On the effects page they fell, 22 → 12. | Scissor changes rose on four of the five screens that lost offscreen pictures (5 → 0, 6 → 2, 8 → 4, 5 → 2). That points to clips now being set on the screen with the scissor, where before they were the edge of a picture. The fifth is the animation page: it lost its one picture, and its scissor changes fell 6 → 4. The extra enable/disable switches are most likely the scissor test going on and off around those clips; that is not checked. | They are inside the GL-call totals, which fell 80-89% on those screens, and draw calls fell too. |
| CPU per frame early after a cold start | showcase home, moving; frames 300-900 of a fresh desktop JVM | median 0.48 → 0.61 ms (+27%); slowest tenth 1.02 → 1.31 ms | Today's code on this page takes longer to reach full JIT speed. Which code is not known yet: a JFR profile changed the result and caught too few samples. | Frames 300-900 of a fresh JVM, which is 5-15 s at 60 fps. Frames 900-3,000 were not measured, so when it ends is not known. Once warm, the page is 49% cheaper (0.38 → 0.19 ms, 1,200 frames after a 3,000-frame warm-up). Filed as #268. Android compiles ahead of time, so a phone may not see it. |

### Reproducing

- **Probe branches.** Profiling only, never for master, both pushed:
  - `issue-267-before-after-measurement`: the after side, on master `8c79d49d`.
  - `issue-267-before-probe`: the before side, on `83b51719`.
  - Each holds:
    - `ProbeGl.kt`: the GL counter.
    - `GdxProbe.kt`: `-Dcomposegl.probe=<file>` counts and `-Dcomposegl.timing=<file>` times any LibGDX game.
    - `RenderProbe.kt`: the hook round `UiRenderer.render`.
    - `ProbeMain.kt`: `./gradlew :composegl-demo-snake:probe` under `xvfb-run`. Set `COMPOSEGL_PROBE_MODE=count|time` and `COMPOSEGL_PROBE_CONTEXT=es3|desktop`.
- **`probe/267/` on the after branch:**
  - The run scripts. Set `PROBE267_DATA` to a scratch folder.
  - `mali.py`.
  - The table, chart and what-rose scripts. They read `probe/267/data/raw`, or `PROBE267_DATA`.
  - Every raw reading: every per-frame CSV, the count reports, and the Mali results.
- **Mega Merge** is private, so its side is described rather than posted. In a throwaway worktree of its master `a53baa481`:
  - A drawn `probeHold(phase, seconds)` scenario step. It is a `wait` that is never blind and that sets `composegl.probe.phase`.
  - A `probe_267` scenario: #242's, plus `setWindowSize(1080, 2400)`.
  - The run task passes `-Pprobe`, `-Ptiming` and `-PprobeJvmArgs` through to the JVM. It ran with `-Pportrait -PtouchDensity=2.75`, against each side's library published to its own Maven folder.
  - For Android:
    - A listener round the game's own logs, per 300 frames, the GL thread's CPU and ComposeGL's share of it.
    - A trigger file arms the run-start draft (the game's own `debugArmRunStartDraft`).
    - The release build is signed with the debug key.
