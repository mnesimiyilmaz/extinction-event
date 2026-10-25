# Compares two directories of PNGs with the same names (tools/scene-dump.sh or device-shot.sh
# output): per image, the mean difference and the share of pixels off by more than 8/255.
# Usage: python3 -I tools/compare-shots.py <before-dir> <after-dir>
import sys
from pathlib import Path

import numpy as np
from PIL import Image

a_dir, b_dir = Path(sys.argv[1]), Path(sys.argv[2])
for a_path in sorted(a_dir.glob('*.png')):
    b_path = b_dir / a_path.name
    if not b_path.exists():
        print(f'{a_path.name:24} missing in {b_dir}')
        continue
    a = np.asarray(Image.open(a_path).convert('RGB'), dtype=np.int16)
    b = np.asarray(Image.open(b_path).convert('RGB'), dtype=np.int16)
    d = np.abs(a - b).max(axis=2)
    print(f'{a_path.name:24} mean {d.mean():5.2f}/255  off>8: {(d > 8).mean() * 100:6.3f}%  max {d.max()}')
