import sys, csv, glob, os
# series.py <bucket> <dir>...: median cpu per bucket of frames, one column per run
b = int(sys.argv[1]); cols = []
for d in sys.argv[2:]:
    f = glob.glob(os.path.join(d, '*.csv'))[0]
    cpu = [int(r['cpu_ns'])/1e6 for r in csv.DictReader(open(f))]
    meds = []
    for i in range(0, len(cpu), b):
        s = sorted(cpu[i:i+b]); meds.append(s[len(s)//2])
    cols.append((os.path.basename(d), meds))
print('bucket ' + ' '.join(f"{n[-12:]:>12s}" for n, _ in cols))
for i in range(max(len(m) for _, m in cols)):
    print(f"{i*b:6d} " + ' '.join(f"{m[i]:12.3f}" if i < len(m) else ' '*12 for _, m in cols))
