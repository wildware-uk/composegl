import sys, csv, glob, os
# fold.py <warm> <period> <bin> <dir>...: median cpu by frame phase within a period, per run
warm, period, b = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
cols = []
for d in sys.argv[4:]:
    f = glob.glob(os.path.join(d, '*.csv'))[0]
    cpu = [int(r['cpu_ns'])/1e6 for r in csv.DictReader(open(f))]
    bins = {}
    for i, c in enumerate(cpu):
        bins.setdefault(((warm + i) % period) // b, []).append(c)
    cols.append((os.path.basename(d), {k: sorted(v)[len(v)//2] for k, v in bins.items()}))
print('phase ' + ' '.join(f"{n[-12:]:>12s}" for n, _ in cols))
for k in range(period // b):
    print(f"{k*b:5d} " + ' '.join(f"{m.get(k, 0):12.3f}" for _, m in cols))
