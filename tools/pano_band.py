"""A sharper horizon band for a walk-in world's panorama (v0.3).

python3 tools/pano_band.py <pano.jpg> <work_dir> <out_dir> <name>   (run from the repository root; needs ARK_API_KEY)

The panorama (4096x2048) is soft in the headset where the eye looks most: around the horizon. Rows 544..1504
(+-42 degrees) are cut into three overlapping 2048x960 tiles (wrapping round), each redrawn by Seedream at
twice the size with the composition kept, then: tones put back from the original (only detail is taken from
the redraw, so colours cannot drift), stitched along the least visible seams, and faded into the plain
enlargement at the top and bottom edges so the band meets the sky sphere without a line. Writes
<out_dir>/band_<name>_a.jpg and _b.jpg (each 4096x1920: the first and second half of the circle).
"""
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageFilter

pano, work, out, name = sys.argv[1:5]
Y0, Y1 = 544, 1504
TW, STRIDE = 2048, 1536
PROMPT = ("把这张图高清放大重绘：严格保持原图的构图、每一个物体的位置和形状、透视、色彩和画风完全不变，"
          "只增加清晰锐利的细节（屋瓦、窗格、船只、树木枝叶、人物、山石纹理、水波），去掉模糊，输出高清版本")
os.makedirs(work, exist_ok=True)
src = Image.open(pano).convert("RGB")
W = src.width
band = np.asarray(src, dtype=np.uint8)[Y0:Y1]
H = band.shape[0]
wide = np.concatenate([band, band[:, :TW]], axis=1)  # for the tile that wraps

xs = list(range(0, W, STRIDE))
if "--vps-compose-only" not in sys.argv[5:]:
    procs = []
    for k, x in enumerate(xs):
        tp = f"{work}/{name}_t{k}.jpg"
        up = f"{work}/{name}_u{k}.jpeg"
        Image.fromarray(wide[:, x:x + TW]).save(tp, quality=95)
        if not os.path.exists(up):
            procs.append(subprocess.Popen([sys.executable, "tools/ark/seedream.py", up, f"{TW * 2}x{H * 2}", PROMPT, tp], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    for p in procs:
        p.wait()

    # The VPS queues local processing separately, after the remote redraws return.
    guard = os.path.expanduser("~/.local/bin/vps-heavy-run")
    if os.path.isfile(guard) and os.access(guard, os.X_OK):
        os.execv(guard, [guard, "render", "--", sys.executable, *sys.orig_argv[1:], "--vps-compose-only"])

base_big = np.asarray(Image.fromarray(wide).resize((wide.shape[1] * 2, H * 2), Image.BICUBIC))
del src, band, wide

def blur(a, r):
    return np.asarray(Image.fromarray(a).filter(ImageFilter.GaussianBlur(r)))

def corrected_tile(k, x):
    up = f"{work}/{name}_u{k}.jpeg"
    ref = base_big[:, 2 * x:2 * (x + TW)]
    if not os.path.exists(up):
        print(name, "tile", k, "missing: plain enlargement")
        return ref
    with Image.open(up) as redraw:
        u = np.asarray(redraw.convert("RGB").resize((TW * 2, H * 2), Image.LANCZOS))
    # All three inputs are 8-bit pixels. Their signed difference and sum fit in int16.
    t = u.astype(np.int16)
    t -= blur(u, 12)
    t += blur(ref, 12)
    np.clip(t, 0, 255, out=t)
    return t.astype(np.uint8)

def cut_path(err):
    h, w = err.shape
    cost = err
    for y in range(1, h):
        prev = cost[y - 1]
        cost[y] += np.minimum(np.minimum(np.r_[np.inf, prev[:-1]], prev), np.r_[prev[1:], np.inf])
    path = np.zeros(h, dtype=int)
    path[-1] = int(np.argmin(cost[-1]))
    for y in range(h - 2, -1, -1):
        x = path[y + 1]; lo, hi = max(0, x - 1), min(w, x + 2)
        path[y] = lo + int(np.argmin(cost[y, lo:hi]))
    return path

def blend(dst, left, right, path):
    cols = np.arange(dst.shape[1])[None, :]
    for y in range(0, dst.shape[0], 64):
        rows = slice(y, y + 64)
        f = np.clip((cols - path[rows, None] + 2) / 4.0, 0, 1)[..., None]
        dst[rows] = left[rows] * (1 - f) + right[rows] * f

BW = 2 * W
res = np.zeros((2 * H, max(BW, 2 * (xs[-1] + TW)), 3), dtype=np.float32)
res[:, 0:2 * TW] = corrected_tile(0, 0)
right = 2 * TW
for k in range(1, len(xs)):
    x = 2 * xs[k]
    ov = right - x
    tile = corrected_tile(k, xs[k])
    a = res[:, x:right]; b = tile[:, :ov]
    err = ((a - b) ** 2).sum(axis=2)
    err = (err + np.roll(err, 1, 0) + np.roll(err, -1, 0)) / 3
    path = cut_path(err)
    blend(a, a, b, path)
    res[:, right:x + 2 * TW] = tile[:, ov:]
    right = x + 2 * TW
    del tile, a, b, err
# Fold the wrap-around back onto the start, along a seam too.
ov = right - BW
a = res[:, :ov]; b = res[:, BW:right]
err = ((a - b) ** 2).sum(axis=2)
path = cut_path(err)
blend(a, b, a, path)
del err, a, b
res = res[:, :BW]
# Fade into the plain enlargement at the top and bottom (the sky sphere shows the original there).
plain = base_big[:, :BW]
fade = 96
wv = np.ones(2 * H, dtype=np.float32)
wv[:fade] = np.linspace(0, 1, fade); wv[-fade:] = np.linspace(1, 0, fade)
img = np.empty((2 * H, BW, 3), dtype=np.uint8)
for y in range(0, 2 * H, 64):
    rows = slice(y, y + 64)
    weight = wv[rows, None, None]
    part = res[rows] * weight + plain[rows] * (1 - weight)
    img[rows] = np.clip(part, 0, 255)
Image.fromarray(img[:, :W]).save(f"{out}/band_{name}_a.jpg", quality=88)
Image.fromarray(img[:, W:]).save(f"{out}/band_{name}_b.jpg", quality=88)
print(name, "band", img.shape)
