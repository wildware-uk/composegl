"""wentup.py: the counts that rose, scene by scene (#267 review round 1)."""
import os, re
S = os.environ.get('PROBE267_DATA') or os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'raw')
def parse(path):
    out = {}; cur = None
    for raw in open(path):
        line = re.sub(r' \(max [0-9.]+\)', '', raw.rstrip('\n'))
        m = re.match(r'== (\S+): (\d+) frames', line)
        if m: cur = m.group(1); out[cur] = {}; continue
        if not cur: continue
        d = out[cur]
        m = re.match(r'program binds (\d+) \(switches (\d+)\)', line)
        if m: d['progsw'] = int(m.group(2))
        m = re.match(r'draw calls (\d+).*quads (\d+)', line)
        if m: d['draws'] = int(m.group(1)); d['quads'] = int(m.group(2))
        m = re.match(r'blend calls \d+ \(changes \d+\), enable/disable (\d+) \(changes (\d+)\)', line)
        if m: d['caps'] = int(m.group(2))
        m = re.match(r'scissor (\d+) \(changes (\d+)\)', line)
        if m: d['scissor'] = int(m.group(2))
        m = re.match(r'pictures drawn into (\d+)', line)
        if m: d['pics'] = int(m.group(1))
        m = re.match(r'vertex upload bytes (\d+)', line)
        if m: d['vb'] = int(m.group(1))
        m = re.match(r'draw calls by why they broke from the last: (.*)', line)
        if m:
            causes = dict((c.rsplit(' ', 1)[0], int(c.rsplit(' ', 1)[1])) for c in m.group(1).split(', ') if c)
            d['prog_only'] = causes.get('program', 0)
            d['prog_any'] = sum(v for k, v in causes.items() if 'program' in k.split('+'))
    return out
for kind, files in [('showcase', ('sc/count-before/report.txt', 'sc/count-after/report.txt')), ('megamerge', ('mm/count-before.txt', 'mm/count-after.txt'))]:
    b, a = parse(f'{S}/{files[0]}'), parse(f'{S}/{files[1]}')
    for scene in a:
        if scene not in b: continue
        x, y = b[scene], a[scene]
        print(f"{kind}-{scene:28} progsw {x['progsw']:>3}->{y['progsw']:<3} quads {x['quads']:>5}->{y['quads']:<5} vertexKB {x['vb']/1024:7.1f}->{y['vb']/1024:<7.1f} draws {x['draws']:>3}->{y['draws']:<3} cut-by-program-only {x['prog_only']}->{y['prog_only']} any-program {x['prog_any']}->{y['prog_any']} scissor-changes {x['scissor']}->{y['scissor']} enable-disable-changes {x['caps']}->{y['caps']} pictures {x['pics']}->{y['pics']}")
