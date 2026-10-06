import sys, re, bisect
# c2cmp.py <before run.log> <after run.log> [filter] [n]: the frame each method first reached C2 on each side, the biggest delays first
def load(path):
    lines = open(path).read().splitlines()
    marks = sorted((int(m.group(2)), int(m.group(1))) for m in (re.match(r'PROBE-FRAME (\d+) uptime (\d+)', l) for l in lines) if m)
    ts = [t for t, _ in marks]
    def frame(t):
        i = bisect.bisect_right(ts, t)
        if i == 0: return 0
        if i >= len(ts): return marks[-1][1] + 1
        (t0, f0), (t1, f1) = marks[i-1], marks[i]
        return f0 + (f1 - f0) * (t - t0) / max(1, t1 - t0)
    ev = re.compile(r'^\s*(\d+)\s+(\d+)\s+([%sbn!\s]{5})\s*(\d)?\s+(\S+)\s*(.*)$')
    c2, c1 = {}, {}
    for l in lines:
        m = ev.match(l)
        if not m or 'made not entrant' in m.group(6) or 'made zombie' in m.group(6): continue
        if '%' in m.group(3): continue
        name, tier = m.group(5), m.group(4)
        if tier == '4': c2.setdefault(name, frame(int(m.group(1))))
        elif tier in ('3', '2', '1'): c1.setdefault(name, frame(int(m.group(1))))
    return c2, c1, marks[-1][1]
b2, b1, bend = load(sys.argv[1]); a2, a1, aend = load(sys.argv[2])
flt = sys.argv[3] if len(sys.argv) > 3 else ''
n = int(sys.argv[4]) if len(sys.argv) > 4 else 40
both = [(a2[k] - b2[k], k) for k in a2 if k in b2 and flt in k]
print(f"C2 by end: before {len(b2)}, after {len(a2)}; in both: {len(both)}")
print("-- reached C2 later on after (frames: before -> after)")
for d, k in sorted(both, reverse=True)[:n]: print(f"  {b2[k]:7.0f} -> {a2[k]:7.0f}  {k}")
only_b = sorted((b2[k], k) for k in b2 if k not in a2 and flt in k and k in a1)
print(f"-- C2 on before, called but never C2 on after: {len(only_b)}")
for f, k in only_b[:n]: print(f"  {f:7.0f}  {k}")
only_a = sorted((a2[k], k) for k in a2 if k not in b2 and flt in k)
print(f"-- C2 only on after (new or renamed code): {len(only_a)}")
for f, k in only_a[:n]: print(f"  {f:7.0f}  {k}")
