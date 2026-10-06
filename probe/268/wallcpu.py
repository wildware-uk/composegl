import sys, csv, glob, os
# wallcpu.py <dir>...: median cpu, median wall, and median wall/cpu per run
for d in sys.argv[1:]:
    f = glob.glob(os.path.join(d, '*.csv'))
    if not f: continue
    rows = list(csv.DictReader(open(f[0])))
    cpu = sorted(int(r['cpu_ns'])/1e6 for r in rows); wall = sorted(int(r['wall_ns'])/1e6 for r in rows)
    ratio = sorted(int(r['wall_ns'])/max(1, int(r['cpu_ns'])) for r in rows)
    m = lambda s: s[len(s)//2]
    print(f"{os.path.basename(d):18s} cpu {m(cpu):.3f} wall {m(wall):.3f} wall/cpu {m(ratio):.2f} p90 ratio {ratio[int(len(ratio)*.9)]:.2f}")
