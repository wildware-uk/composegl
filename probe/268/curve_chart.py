"""curve_chart.py <out.png> <bucket> <before dirs,...> <after dirs,...>: the warm-up curve, old vs today.
Each point is the median frame of one bucket of frames, pooled over that side's runs."""
import sys, csv, glob, os
from PIL import Image, ImageDraw, ImageFont
out, b = sys.argv[1], int(sys.argv[2])
def series(dirs):
    runs = [[int(r['cpu_ns'])/1e6 for r in csv.DictReader(open(glob.glob(os.path.join(d, '*.csv'))[0]))] for d in dirs.split(',')]
    n = min(len(r) for r in runs); pts = []
    for i in range(0, n, b):
        v = sorted(x for r in runs for x in r[i:i+b]); pts.append((i + b/2, v[len(v)//2]))
    return pts
old, new = series(sys.argv[3]), series(sys.argv[4])
SURFACE, INK, INK2, MUTED, GRID, AXIS = '#fcfcfb', '#0b0b0b', '#52514e', '#898781', '#e1e0d9', '#c3c2b7'
OLD, NEW = '#eb6834', '#2a78d6'
W, H, s = 1400, 760, 2
img = Image.new('RGB', (W*s, H*s), SURFACE); d = ImageDraw.Draw(img)
F = lambda size, bold=False: ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf' % ('-Bold' if bold else ''), size*s)
L, R, T, B = 110, 1220, 120, 660
xmax = max(p[0] for p in old + new) + b/2
ymax = max(p[1] for p in old + new); step = 0.1 if ymax < 0.8 else 0.2
ymax = (int(ymax / step) + 1) * step
X = lambda x: (L + (R - L) * x / xmax) * s
Y = lambda y: (B - (B - T) * y / ymax) * s
# The window #268 measured: frames 300-900.
d.rectangle([X(300), Y(ymax), X(900), Y(0)], fill='#f1f0ec')
d.text((X(600), Y(ymax) + 8*s), "#268's window\n(frames 300-900)", font=F(15), fill=INK2, anchor='ma', align='center')
y = 0.0
while y <= ymax + 1e-9:
    d.line([X(0), Y(y), X(xmax), Y(y)], fill=GRID if y > 0 else AXIS, width=s)
    d.text((L*s - 12*s, Y(y)), f"{y:.1f}", font=F(15), fill=MUTED, anchor='rm'); y += step
for f in range(0, int(xmax) + 1, 500):
    d.text((X(f), B*s + 12*s), f"{f}", font=F(15), fill=MUTED, anchor='ma')
d.text(((L+R)/2*s, B*s + 42*s), 'frames since the JVM started', font=F(16), fill=INK2, anchor='ma')
d.text((L*s, 40*s), 'Moving home page, cold start: CPU per frame', font=F(24, True), fill=INK, anchor='la')
d.text((L*s, 76*s), f'ms per frame, median of each {b}-frame step, pooled over 3 fresh JVMs per side (quiet box)', font=F(16), fill=INK2, anchor='la')
for pts, col, name in ((old, OLD, 'before #234'), (new, NEW, 'today')):
    d.line([(X(x), Y(v)) for x, v in pts], fill=col, width=3*s, joint='curve')
    for x, v in pts: d.ellipse([X(x)-4*s, Y(v)-4*s, X(x)+4*s, Y(v)+4*s], fill=col, outline=SURFACE, width=2*s)
    lx, lv = pts[-1]
    d.text((X(lx) + 14*s, Y(lv)), name, font=F(17, True), fill=INK, anchor='lm')
# Legend, top right.
lx = R*s - 290*s
for i, (col, name) in enumerate(((OLD, 'before #234 (83b51719)'), (NEW, 'today (8c79d49d)'))):
    yy = 40*s + i*26*s
    d.line([lx, yy, lx + 26*s, yy], fill=col, width=3*s)
    d.text((lx + 36*s, yy), name, font=F(15), fill=INK2, anchor='lm')
img.save(out)
print(out, img.size)
