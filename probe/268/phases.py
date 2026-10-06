import sys, csv, glob, os
# phases.py <dir>...: per run, mean ms per phase (recompose, layout, focus, draw, rest) and total
print(f"{'run':22s} {'total':>6s} {'recomp':>6s} {'layout':>6s} {'focus':>6s} {'draw':>6s} {'rest':>6s}  med-total")
for d in sys.argv[1:]:
    f = glob.glob(os.path.join(d, '*.csv'))
    if not f: continue
    rows = list(csv.DictReader(open(f[0])))
    if 'draw_ns' not in rows[0]: continue
    n = len(rows); k = lambda c: sum(int(r[c]) for r in rows) / n / 1e6
    tot = k('cpu_ns'); parts = [k('recompose_ns'), k('layout_ns'), k('focus_ns'), k('draw_ns')]
    med = sorted(int(r['cpu_ns']) for r in rows)[n // 2] / 1e6
    print(f"{os.path.basename(d):22s} {tot:6.3f} " + ' '.join(f"{p:6.3f}" for p in parts) + f" {tot - sum(parts):6.3f}  {med:.3f}")
