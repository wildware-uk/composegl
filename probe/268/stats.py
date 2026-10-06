import sys, csv, statistics, glob, os
# stats.py <tag-glob>...: median, p10, p90 of cpu per frame for each run
for pat in sys.argv[1:]:
    for d in sorted(glob.glob(pat)):
        f = glob.glob(os.path.join(d, '*.csv'))
        if not f: print(d, 'no csv'); continue
        cpu = [int(r['cpu_ns'])/1e6 for r in csv.DictReader(open(f[0]))]
        s = sorted(cpu)
        q = lambda p: s[int((len(s)-1)*p)]
        print(f"{os.path.basename(d):30s} n={len(cpu)} median {q(.5):.3f} p10 {q(.1):.3f} p90 {q(.9):.3f} mean {statistics.mean(cpu):.3f}")
