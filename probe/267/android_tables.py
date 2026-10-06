"""android_tables.py: Mega Merge on the emulator, per phase and side, from measure.sh's runs (#267)."""
import glob, os, re, statistics as st
D = os.environ.get('PROBE267_ANDROID') or os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'raw', 'android')
def windows(run):
    out = []
    for line in open(f'{run}/probe.txt'):
        m = re.search(r'^\s*([\d.]+).*frames=(\d+) fps=([\d.]+) cpu\[mean ([\d.]+) median ([\d.]+) p10 ([\d.]+) p90 ([\d.]+)\] ui\[mean ([\d.]+) median ([\d.]+)', line)
        if not m: continue
        t, frames, fps = float(m.group(1)), int(m.group(2)), float(m.group(3))
        out.append({'start': t - frames / fps, 'end': t, 'fps': fps, 'cpu_mean': float(m.group(4)), 'cpu_med': float(m.group(5)),
                    'cpu_p90': float(m.group(7)), 'ui_mean': float(m.group(8)), 'ui_med': float(m.group(9))})
    return out
def phases(run):
    marks = {}
    for line in open(f'{run}/phases.txt'):
        t, name = line.split(); marks[name] = float(t)
    return {p: (marks[p + '-start'], marks[p + '-end']) for p in ['draft-open', 'board-still'] if p + '-start' in marks}
res = {}
for run in sorted(glob.glob(f'{D}/run-*-*')):
    if not os.path.isdir(run): continue
    side = os.path.basename(run).split('-')[1]
    ws = windows(run)
    for ph, (a, b) in phases(run).items():
        inside = [w for w in ws if w['start'] >= a - 0.2 and w['end'] <= b + 0.2]
        res.setdefault((ph, side), []).extend(inside)
        print(os.path.basename(run), ph, len(inside), 'windows', ' '.join(f"{w['fps']:.0f}fps/{w['cpu_med']:.2f}/{w['ui_med']:.2f}" for w in inside))
print()
print('| Screen | fps | GL-thread CPU per frame, median, ms | of which ComposeGL (`UiRenderer.render`), ms |')
print('|---|---|---|---|')
for ph, name in [('draft-open', 'Card draft, open'), ('board-still', 'Board, still')]:
    row = []
    for key in ['fps', 'cpu_med', 'ui_med']:
        b = st.median([w[key] for w in res.get((ph, 'before'), [])]) if res.get((ph, 'before')) else None
        a = st.median([w[key] for w in res.get((ph, 'after'), [])]) if res.get((ph, 'after')) else None
        if b is None or a is None: row.append('-'); continue
        f = '{:.0f}' if key == 'fps' else '{:.2f}'
        row.append(f'{f.format(b)} → {f.format(a)} ({(a - b) / b * 100:+.0f}%)')
    print(f'| {name} | ' + ' | '.join(row) + ' |')
