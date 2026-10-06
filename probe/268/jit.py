import sys, re
# jit.py <run.log> [filter]: compile events between the warm-up mark and the last frame
lines = open(sys.argv[1]).read().splitlines()
flt = sys.argv[2] if len(sys.argv) > 2 else ''
marks = {int(m.group(1)): int(m.group(2)) for m in (re.match(r'PROBE-FRAME (\d+) uptime (\d+)', l) for l in lines) if m}
f = sorted(marks); t0, t1, t2 = marks[f[0]], marks[f[1]], marks[f[2]]
ev = re.compile(r'^\s*(\d+)\s+(\d+)\s+([%sbn!\s]{5})\s*(\d)?\s+(\S+)\s*(.*)$')
c4_before = c4_during = nonent = 0; late = []; deopt = []
for l in lines:
    m = ev.match(l)
    if not m: continue
    t, tier, name, rest = int(m.group(1)), m.group(4), m.group(5), m.group(6)
    if flt and flt not in name: continue
    if 'made not entrant' in rest:
        if t1 <= t <= t2: nonent += 1; deopt.append((t, tier, name))
        continue
    if tier == '4':
        if t < t1: c4_before += 1
        elif t <= t2: c4_during += 1; late.append((t, name, rest))
print(f"frames 0/300/end at {t0}/{t1}/{t2} ms; tier-4 compiles before 300: {c4_before}, during 300-end: {c4_during}; made not entrant during: {nonent}")
for t, n, r in late[:int(sys.argv[3]) if len(sys.argv) > 3 else 40]: print('  C2', t, n, r)
for t, tier, n in deopt[:30]: print('  DEOPT', t, tier, n)
