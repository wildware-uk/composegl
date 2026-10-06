"""waitfor.py <ref.png> <x0> <y0> <x1> <y1> <timeout s>: wait until my emulator copy shows what ref.png shows in that box."""
import io, os, subprocess, sys, time
from PIL import Image, ImageChops, ImageStat
ref, box, timeout = sys.argv[1], tuple(int(v) for v in sys.argv[2:6]), float(sys.argv[6])
want = Image.open(ref).convert('RGB').crop(box)
adb = [os.path.expanduser('~/Android/Sdk/platform-tools/adb'), '-s', 'emulator-5590', 'exec-out', 'screencap', '-p']
end = time.time() + timeout
while time.time() < end:
    shot = Image.open(io.BytesIO(subprocess.run(adb, capture_output=True).stdout)).convert('RGB').crop(box)
    diff = sum(ImageStat.Stat(ImageChops.difference(shot, want)).mean) / 3
    if diff < 14:
        print(f'matched {ref} ({diff:.1f})'); sys.exit(0)
    time.sleep(1)
print(f'TIMEOUT waiting for {ref} ({diff:.1f})'); sys.exit(1)
