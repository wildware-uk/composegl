"""android_tables.py: Mega Merge on the emulator, one row per pass, from measure.sh's runs (#267).

Two readings per pass and phase:
- the probe's (one line per 300 frames): the frame rate, the GL thread's CPU per frame, and
  ComposeGL's `UiRenderer.render` within it, taking the windows that lie wholly inside the phase;
- the emulator's own `app_time_stats` (one line a second, the game's process only): the game's
  time per frame and how many frames each second drew.
"""
import os, re, statistics as st
D = os.environ.get('PROBE267_ANDROID') or os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'raw', 'android')
# The hand each pass dealt, read off its draft.png: a glowing (uncommon) card costs more to draw.
HANDS = {'run-before-1': 'plain', 'run-after-2': 'plain', 'run-before-2': 'one glowing (uncommon)', 'run-after-1': 'one glowing (uncommon)'}

def phases(run):
    marks = {}
    for line in open(f'{run}/phases.txt'):
        t, name = line.split(); marks[name] = float(t)
    return {p: (marks[p + '-start'], marks[p + '-end']) for p in ['draft-open', 'board-still']}

def probe(run, a, b):
    rows = []; pid = None
    for line in open(f'{run}/probe.txt'):
        m = re.search(r'^\s*([\d.]+)\s+(\d+)\s+\d+ I FrameProbe267: frames=(\d+) fps=([\d.]+) cpu\[mean [\d.]+ median ([\d.]+) .*?ui\[mean [\d.]+ median ([\d.]+)', line)
        if not m: continue
        pid = m.group(2)
        end = float(m.group(1)); start = end - int(m.group(3)) / float(m.group(4))
        if start >= a - 0.2 and end <= b + 0.2:
            rows.append((float(m.group(4)), float(m.group(5)), float(m.group(6))))
    return pid, {'fps': st.median(r[0] for r in rows), 'cpu': st.median(r[1] for r in rows), 'ui': st.median(r[2] for r in rows)}

def frametimes(run, pid, a, b):
    rows = []
    for line in open(f'{run}/frametimes.txt'):
        m = re.search(r'^\s*([\d.]+)\s+(\d+)\s+\d+ D EGL_emulation: app_time_stats: avg=([\d.]+)ms .*count=(\d+)', line)
        if m and m.group(2) == pid and a <= float(m.group(1)) <= b:
            rows.append((float(m.group(3)), int(m.group(4))))
    return {'app': st.median(r[0] for r in rows), 'lowest': min(r[1] for r in rows), 'under58': sum(1 for r in rows if r[1] < 58), 'seconds': len(rows)}

print('| Pass | Cards dealt | Open draft: frames a second, median (lowest second) | seconds under 58 | game\'s time per frame (`app_time_stats`), ms | GL-thread CPU per frame, ms | of which ComposeGL, ms | Board: GL-thread CPU, ms | of which ComposeGL, ms |')
print('|---|---|---|---|---|---|---|---|---|')
for run in ['run-before-1', 'run-after-2', 'run-before-2', 'run-after-1']:
    path = os.path.join(D, run)
    ph = phases(path)
    pid, draft = probe(path, *ph['draft-open'])
    _, board = probe(path, *ph['board-still'])
    ft = frametimes(path, pid, *ph['draft-open'])
    name = run.replace('run-', '').replace('-', ' ')
    print(f"| {name} | {HANDS[run]} | {draft['fps']:.0f} ({ft['lowest']}) | {ft['under58']} of {ft['seconds']} | {ft['app']:.2f} | {draft['cpu']:.2f} | {draft['ui']:.2f} | {board['cpu']:.2f} | {board['ui']:.2f} |")
