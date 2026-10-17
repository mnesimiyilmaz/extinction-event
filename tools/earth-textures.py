# Cuts the earthCrop region out of NASA Earth Observatory maps (public domain) into
# app/src/main/assets/earth/day.jpg (sRGB colour) and aux.jpg (R night lights, G clouds, B water).
# Usage: node tools/earth-crop.mjs && python3 -I tools/earth-textures.py <dir with the NASA files>
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

Image.MAX_IMAGE_PIXELS = 300_000_000  # the 21600x10800 Blue Marble
ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1])
DAY = SRC / 'world.topo.bathy.200412.3x21600x10800.jpg'
NIGHT = SRC / 'BlackMarble_2016_3km.jpg'
CLOUDS = SRC / 'cloud_combined_8192.tif'
MAX_SIZE = 4096
MAX_PX_PER_DEG = 60  # the Blue Marble's native resolution

lon0, lon1, lat0, lat1 = json.loads((ROOT / 'app/src/main/assets/config/tuning.json').read_text())['earthCrop']
px = min(MAX_PX_PER_DEG, MAX_SIZE / max(lon1 - lon0, lat1 - lat0))
size = (round((lon1 - lon0) * px), round((lat1 - lat0) * px))


def cut(path, mode):
    with Image.open(path) as im:
        im = im.convert(mode)
        w, h = im.size
        box = ((lon0 + 180) / 360 * w, (90 - lat1) / 180 * h, (lon1 + 180) / 360 * w, (90 - lat0) / 180 * h)
        return im.resize(size, Image.LANCZOS, box=box)


day = cut(DAY, 'RGB')
rgb = np.asarray(day).astype(np.int16)
r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
water = ((b > r + 20) & (b + 10 > g)).astype(np.uint8) * 255
water = Image.fromarray(water).filter(ImageFilter.GaussianBlur(1))
aux = Image.merge('RGB', (cut(NIGHT, 'L'), cut(CLOUDS, 'L'), water))
# 45 px/deg is still above the finest source (night lights, ~37.5 px/deg; clouds ~23); half
# resolution visibly softened the Nile delta lights. Saves ~17 MB of GPU memory per engine.
aux = aux.resize((size[0] * 3 // 4, size[1] * 3 // 4), Image.LANCZOS)

out = ROOT / 'app/src/main/assets/earth'
out.mkdir(parents=True, exist_ok=True)
day.save(out / 'day.jpg', quality=90, subsampling=0)
aux.save(out / 'aux.jpg', quality=92, subsampling=0)  # no chroma subsampling: channels are data
print(f'crop {lon0}..{lon1}E {lat0}..{lat1}N -> {size[0]}x{size[1]} ({px:.1f} px/deg)')
