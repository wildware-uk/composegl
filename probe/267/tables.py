#!/usr/bin/env python3
"""tables.py: #267's before/after tables from the probe reports in this scratch folder."""
import csv, json, os, re, sys
# The readings: PROBE267_DATA, or data/raw beside this script (the layout the branch commits: sc/, mm/, mali/).
S = os.environ.get('PROBE267_DATA') or os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'raw')
RATE = 2 * 950e6  # Mali-G57 MC2: two cores at 950 MHz, as #242 estimated
PICTURE_KINDS = {'picture or glyph', 'layer picture', 'premultiplied picture'}

def parse_count(path):
    out = {}; cur = None
    if not os.path.exists(path): return out
    for raw in open(path):
        line = re.sub(r' \(max [0-9.]+\)', '', raw.rstrip('\n'))
        m = re.match(r'== (\S+): (\d+) frames', line)
        if m: cur = m.group(1); out[cur] = {'kinds': {}, 'pk': {}}; continue
        if cur is None: continue
        d = out[cur]
        for k, p in {'calls': r'^GL calls (\d+)', 'draws': r'^draw calls (\d+)', 'offdraws': r'^draw calls \d+ \(into pictures (\d+)',
                     'pics': r'^pictures drawn into (\d+)', 'picarea': r'^pictures drawn into \d+, their area (\d+)',
                     'fbmade': r'^framebuffers made (\d+)', 'vb': r'^vertex upload bytes (\d+)'}.items():
            m = re.search(p, line)
            if m and k not in d: d[k] = int(m.group(1))
        m = re.match(r'pixels covered: screen (\d+), pictures (\d+), masked (\d+); screen is (\d+)', line)
        if m: d['host'], d['pic'], d['masked'], d['screen'] = map(int, m.groups())
        m = re.match(r'  by program (\S+?)/(.+?): (\d+) px', line)
        if m: d['pk'][(m.group(1), m.group(2))] = int(m.group(3)); continue
        m = re.match(r'  (.+?): (\d+) px in', line)
        if m: d['kinds'][m.group(1)] = int(m.group(2))
    return out

def q(v, p):
    v = sorted(v)
    return v[int((len(v) - 1) * p)]

def run_stats(rows):
    cpu = [r[0] / 1e6 for r in rows]; kb = [r[1] / 1024 for r in rows]
    return {'cpu_med': q(cpu, 0.5), 'cpu_p10': q(cpu, 0.1), 'cpu_mean': sum(cpu) / len(cpu), 'kb': sum(kb) / len(kb), 'frames': len(rows)}

def parse_time_dir(d):
    """Showcase and snake: one CSV per scene, warm-up already dropped."""
    out = {}
    if not os.path.isdir(d): return out
    for f in os.listdir(d):
        if f.endswith('.csv'):
            rows = [(int(r['cpu_ns']), int(r['alloc_bytes'])) for r in csv.DictReader(open(os.path.join(d, f)))]
            if rows: out[f[:-4]] = run_stats(rows)
    return out

def parse_time_mm(path):
    """Mega Merge: one CSV of every frame with its phase; the first quarter of each phase is dropped."""
    out = {}
    if not os.path.exists(path): return out
    by = {}
    for r in csv.DictReader(open(path)):
        if r['phase']:
            ui = (int(r['ui_cpu_ns']), int(r['ui_alloc_bytes'])) if r.get('ui_cpu_ns') else None
            by.setdefault(r['phase'], []).append(((int(r['cpu_ns']), int(r['alloc_bytes'])), ui))
    for ph, rows in by.items():
        rows = rows[len(rows) // 4:]
        st = run_stats([r[0] for r in rows])
        if rows and rows[0][1] is not None:
            ui = run_stats([r[1] for r in rows])
            st['ui_cpu_med'] = ui['cpu_med']; st['ui_cpu_mean'] = ui['cpu_mean']; st['ui_kb'] = ui['kb']
        out['megamerge-' + ph] = st
    return out

def cycles_for(mali, core, tag, kind):
    masked = kind.endswith('+mask')
    base = kind[:-5] if masked else kind
    if tag == 'effect':
        return max(v['longest'] for v in mali['effects'][core].values()) if 'effects' in mali else 3.5
    prog = mali[tag][core]
    if base in PICTURE_KINDS:
        c = prog['whole']['shortest']
        if masked:
            c += prog['paths']['glyph+mask']['longest'] - prog['paths']['glyph']['longest']
        return c
    return prog['paths'][kind]['longest']

def gpu_ms(d, mali, core='Mali-G57'):
    total = 0.0
    for (tag, kind), px in d['pk'].items():
        total += px * cycles_for(mali, core, tag, kind)
    return total / RATE * 1000

def main():
    sides = ['before', 'after']
    mali = {s: json.load(open(f'{S}/mali/{s}.json')) for s in sides}
    count = {s: {**parse_count(f'{S}/sc/count-{s}/report.txt'), **{'megamerge-' + k: v for k, v in parse_count(f'{S}/mm/count-{s}.txt').items()}} for s in sides}
    times = {s: [] for s in sides}
    for s in sides:
        for r in range(1, 10):
            # Scroll scenes come from their own reruns (review round 1: a held finger on the page).
            t = {k: v for k, v in parse_time_dir(f'{S}/sc/time-{s}-{r}').items() if 'scroll' not in k}
            t.update(parse_time_dir(f'{S}/sc/scroll-{s}-{r}'))
            t.update(parse_time_mm(f'{S}/mm/time-{s}-{r}.txt.csv'))
            if t: times[s].append(t)
    scenes = [k for k in count['after'] if k in count['before']]
    json.dump({'count': {s: {k: {kk: vv for kk, vv in v.items() if kk != 'pk'} | {'pk': {f'{a}/{b}': c for (a, b), c in v['pk'].items()}} for k, v in count[s].items()} for s in sides},
               'times': times}, open(f'{S}/tables.json', 'w'), indent=1)
    def tm(s, scene, key):
        return [t[scene][key] for t in times[s] if scene in t and key in t[scene]]
    def med(v):
        import statistics
        return statistics.median(v) if v else None
    def fmt_pair(b, a, f='{:.2f}'):
        if b is None or a is None: return '-'
        ch = (a - b) / b * 100 if b else 0
        return f'{f.format(b)} → {f.format(a)} ({ch:+.0f}%)'
    summary = {}
    for scene in scenes:
        b, a = count['before'][scene], count['after'][scene]
        summary[scene] = {
            'cpu': [med(tm('before', scene, 'cpu_med')), med(tm('after', scene, 'cpu_med'))],
            'cpu_runs': [tm('before', scene, 'cpu_med'), tm('after', scene, 'cpu_med')],
            'cpu_p10': [med(tm('before', scene, 'cpu_p10')), med(tm('after', scene, 'cpu_p10'))],
            'ui_runs': [tm('before', scene, 'ui_cpu_med'), tm('after', scene, 'ui_cpu_med')],
            'kb': [med(tm('before', scene, 'kb')), med(tm('after', scene, 'kb'))],
            'ui_cpu': [med(tm('before', scene, 'ui_cpu_med')) if tm('before', scene, 'ui_cpu_med') else None, med(tm('after', scene, 'ui_cpu_med')) if tm('after', scene, 'ui_cpu_med') else None] if scene.startswith('megamerge') else None,
            'ui_kb': [med(tm('before', scene, 'ui_kb')) if tm('before', scene, 'ui_kb') else None, med(tm('after', scene, 'ui_kb')) if tm('after', scene, 'ui_kb') else None] if scene.startswith('megamerge') else None,
            'calls': [b['calls'], a['calls']], 'draws': [b['draws'], a['draws']],
            'pics': [b['pics'], a['pics']], 'picarea': [b['picarea'], a['picarea']],
            'px': [(b['host'] + b['pic']) / b['screen'], (a['host'] + a['pic']) / a['screen']],
            'gpu57': [gpu_ms(b, mali['before']), gpu_ms(a, mali['after'])],
            'gpu52': [gpu_ms(b, mali['before'], 'Mali-G52'), gpu_ms(a, mali['after'], 'Mali-G52')],
        }
    json.dump(summary, open(f'{S}/summary.json', 'w'), indent=1)
    print('| Screen | CPU ms/frame | Garbage KB/frame | Draw calls | GL calls | Offscreen pictures (px) | Pixels painted (× screen) | Mali-G57 est. ms |')
    print('|---|---|---|---|---|---|---|---|')
    for scene in scenes:
        b, a = count['before'][scene], count['after'][scene]
        cpu = fmt_pair(med(tm('before', scene, 'cpu_med')), med(tm('after', scene, 'cpu_med')))
        gar = fmt_pair(med(tm('before', scene, 'kb')), med(tm('after', scene, 'kb')), '{:.1f}')
        px = lambda d: (d['host'] + d['pic']) / d['screen']
        print(f"| {scene} | {cpu} | {gar} | {b['draws']} → {a['draws']} | {b['calls']:,} → {a['calls']:,} | {b['pics']} ({b['picarea']/1e6:.2f} M) → {a['pics']} ({a['picarea']/1e6:.2f} M) | {px(b):.2f} → {px(a):.2f} | {fmt_pair(gpu_ms(b, mali['before']), gpu_ms(a, mali['after']), '{:.1f}')} |")
    print()
    print('CPU per run: median frame (10th percentile frame), ms; garbage KB per frame')
    for scene in scenes:
        f = lambda side: ' '.join(f"{t[scene]['cpu_med']:.3f}({t[scene]['cpu_p10']:.3f})" for t in times[side] if scene in t)
        g = lambda side: ' '.join(f"{t[scene]['kb']:.1f}" for t in times[side] if scene in t)
        print(f"  {scene:32} before {f('before')} | after {f('after')} | KB {g('before')} | {g('after')}")
    print()
    print('Mega Merge, the toolkit alone (UiRenderer.render), runs that carry it: median-frame CPU ms / mean CPU ms / KB')
    for scene in scenes:
        if not scene.startswith('megamerge'): continue
        f = lambda side: ' '.join(f"{t[scene]['ui_cpu_med']:.3f}/{t[scene]['ui_cpu_mean']:.3f}/{t[scene]['ui_kb']:.1f}" for t in times[side] if scene in t and 'ui_cpu_med' in t[scene])
        print(f"  {scene:32} before {f('before')} | after {f('after')}")
    print()
    print('Mali-G52 est. ms:')
    for scene in scenes:
        print(f"  {scene:32} {gpu_ms(count['before'][scene], mali['before'], 'Mali-G52'):.1f} → {gpu_ms(count['after'][scene], mali['after'], 'Mali-G52'):.1f}")

main()
