import sys, csv, glob, os
# pseries.py <col> <bucket> <dir>...: mean of a column per bucket of frames, one column per run
col, b = sys.argv[1], int(sys.argv[2]); cols = []
for d in sys.argv[3:]:
    f = glob.glob(os.path.join(d, '*.csv'))[0]
    v = [int(r[col])/1e6 for r in csv.DictReader(open(f))]
    cols.append((os.path.basename(d), [sum(v[i:i+b])/len(v[i:i+b]) for i in range(0, len(v), b)]))
print('bucket ' + ' '.join(f"{n[-12:]:>12s}" for n, _ in cols))
for i in range(max(len(m) for _, m in cols)):
    print(f"{i*b:6d} " + ' '.join(f"{m[i]:12.3f}" if i < len(m) else ' '*12 for _, m in cols))
