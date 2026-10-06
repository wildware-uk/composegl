"""report_tables.py: the markdown tables for #267's comment, straight from summary.json."""
import json, os
# The readings: PROBE267_DATA, or data/raw beside this script (the layout the branch commits: sc/, mm/, mali/).
S = os.environ.get('PROBE267_DATA') or os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'raw')
d = json.load(open(f'{S}/summary.json'))
def pct(b, a):
    return f'{(a - b) / b * 100:+.0f}%' if b else ''
def pair(b, a, f):
    return f'{f(b)} → {f(a)} ({pct(b, a)})'
ms = lambda v: f'{v:.2f}'
ms1 = lambda v: f'{v:.1f}'
kb = lambda v: f'{v:.0f}' if v >= 10 else f'{v:.1f}'
num = lambda v: f'{v:,.0f}'
scr = lambda v: f'{v:.2f}'
def rng(runs):
    return f'{min(runs):.2f}-{max(runs):.2f}'
def table(rows, mm=False):
    out = []
    if mm:
        out.append('| Screen | Game-thread CPU, ms (6 runs) | ComposeGL\'s own, ms (runs 4-6; game thread in the same runs) | Garbage per frame, KB, mean (ComposeGL\'s part) | Draw calls | GL calls | Offscreen pictures (their px) | Pixels painted (screens) | Phone GPU est., ms |')
        out.append('|---|---|---|---|---|---|---|---|---|')
    else:
        out.append('| Screen | CPU, ms | Garbage, KB | Draw calls | GL calls | Offscreen pictures (their px) | Pixels painted (screens) | Phone GPU est., ms |')
        out.append('|---|---|---|---|---|---|---|---|')
    for key, name in rows:
        r = d[key]
        pics = f"{r['pics'][0]} ({r['picarea'][0] / 1e6:.2f} M) → {r['pics'][1]} ({r['picarea'][1] / 1e6:.2f} M)"
        common = f"{r['draws'][0]} → {r['draws'][1]} | {pair(*r['calls'], num)} | {pics} | {pair(*r['px'], scr)} | {pair(*r['gpu57'], ms1)} |"
        if mm:
            import statistics
            same = [statistics.median(runs[3:6]) for runs in r['cpu_runs']]
            ui = (pair(*r['ui_cpu'], ms) + f"; game thread {same[0]:.2f} → {same[1]:.2f}") if r['ui_cpu'] and None not in r['ui_cpu'] else '-'
            g = f"{kb(r['kb'][0])} → {kb(r['kb'][1])} ({pct(*r['kb'])}); ComposeGL {kb(r['ui_kb'][0])} → {kb(r['ui_kb'][1])}"
            cpu = pair(*r['cpu'], ms)
            if key == 'megamerge-board-still':
                # Its runs swing too far for a percentage to mean anything: give the spread.
                b_runs, a_runs = r['cpu_runs']
                cpu = f"runs {min(b_runs):.1f}-{max(b_runs):.1f} → {min(a_runs):.1f}-{max(a_runs):.1f} (too noisy for a %)"
                ui = f"runs {min(r['ui_runs'][0]):.2f}-{max(r['ui_runs'][0]):.2f} → {min(r['ui_runs'][1]):.2f}-{max(r['ui_runs'][1]):.2f}"
            out.append(f"| {name} | {cpu} | {ui} | {g} | {common}")
        else:
            out.append(f"| {name} | {pair(*r['cpu'], ms)} | {pair(*r['kb'], kb)} | {common}")
    return '\n'.join(out)
MM = [('megamerge-board-still', 'Board, still'), ('megamerge-draft-dealing', 'Card draft, cards dealing'), ('megamerge-draft-open', 'Card draft, open')]
SC = [('showcase-home-animated', 'Home, moving'), ('showcase-widgets-still', 'Widgets, still'), ('showcase-widgets-scroll', 'Widgets, finger scrolling'),
      ('showcase-game-animated', 'Game widgets, typewriter running'), ('showcase-animation-animated', 'Animation page, moving'),
      ('showcase-effects-still', 'Effects, still'), ('showcase-surfaces-still', 'Surfaces, still'), ('showcase-gear-scroll', 'Gear, finger scrolling'),
      ('showcase-text-still', 'Text, still'), ('showcase-hud-still', 'HUD, still'),
      ('snake-menu', 'Snake, menu'), ('snake-playing', 'Snake, playing')]
REST = [('showcase-home-still', 'Home, still'), ('showcase-game-still', 'Game widgets, still'), ('showcase-gear-still', 'Gear, still'),
        ('showcase-effects-animated', 'Effects, moving'), ('showcase-surfaces-animated', 'Surfaces, moving')]
print('### Mega Merge\n'); print(table(MM, mm=True)); print()
print('### Showcase and snake\n'); print(table(SC)); print()
print('<details><summary>The other showcase scenes</summary>\n'); print(table(REST)); print('\n</details>\n')
print('<details><summary>CPU in every run, the fastest tenth of frames, and the Mali-G52 estimate</summary>\n')
print('Each run\'s median frame in ms, in the order the runs went (sides alternating). The fastest tenth is the median, across runs, of each run\'s 10th-percentile frame.\n')
print('| Screen | Before, each run | After, each run | Fastest tenth of frames, ms | Mali-G52 est., ms |'); print('|---|---|---|---|---|')
for key, name in MM + SC + REST:
    r = d[key]
    print(f"| {name} | {' '.join(f'{v:.2f}' for v in r['cpu_runs'][0])} | {' '.join(f'{v:.2f}' for v in r['cpu_runs'][1])} | {pair(*r['cpu_p10'], ms)} | {r['gpu52'][0]:.1f} → {r['gpu52'][1]:.1f} |")
print('\n</details>')
