"""pixdiff.py <before dir> <after dir>: per scene, how many pixels differ and by how much (the largest
difference in any one channel, of 255). The folders are ProbeMain's count-run screenshots."""
import os, sys
from PIL import Image, ImageChops
b_dir, a_dir = sys.argv[1], sys.argv[2]
for name in sorted(os.listdir(b_dir)):
    if not name.endswith('.png') or not os.path.exists(os.path.join(a_dir, name)): continue
    a = Image.open(os.path.join(b_dir, name)).convert('RGB')
    b = Image.open(os.path.join(a_dir, name)).convert('RGB')
    r, g, bl = ImageChops.difference(a, b).split()
    worst = ImageChops.lighter(ImageChops.lighter(r, g), bl)
    h = worst.histogram()
    big = worst.point(lambda v: 255 if v > 1 else 0).getbbox()
    print(f'{name:36} differ {sum(h[1:]):6d}  by more than 1: {sum(h[2:]):6d}  largest {max(i for i, v in enumerate(h) if v):3d}  where >1: {big}')
