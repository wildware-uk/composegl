import sys, csv, glob, os
# sub.py <dir>...: recompose split into clocks, apply, sendFrame (recomposition), drain, tail - mean ms
cols = ['recompose_ns', 'clocks_ns', 'apply_ns', 'sendframe_ns', 'drain_ns', 'tail_ns', 'layout_ns', 'draw_ns']
print(f"{'run':16s} " + ' '.join(f"{c[:-3]:>9s}" for c in cols))
for d in sys.argv[1:]:
    f = glob.glob(os.path.join(d, '*.csv'))
    if not f: continue
    rows = list(csv.DictReader(open(f[0])))
    if 'tail_ns' not in rows[0]: continue
    print(f"{os.path.basename(d):16s} " + ' '.join(f"{sum(int(r[c]) for r in rows)/len(rows)/1e6:9.3f}" for c in cols))
