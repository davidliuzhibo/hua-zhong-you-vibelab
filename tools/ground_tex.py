"""Seamless ground textures for the walk-in worlds (v0.3): colour and normal map from one Seedream picture.

uv run --with pillow --with numpy python -I tools/ground_tex.py <in.jpeg> <out_dir> <name> [sat] [gain] [size]

Tiling: the picture overlaps itself by 12 % across and down, and the overlap is cut along the path where the
two copies differ least (image quilting), so a seam follows mortar lines or grain instead of ghosting through
stones. Colour: saturation and brightness scaled ([sat], [gain]). Normal: from the luminance's slopes (stone
edges and pebbles stand out under the world's light). Writes <name>.jpg and <name>_n.png at [size] px.
"""
import sys
import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

src, out, name = sys.argv[1:4]
sat = float(sys.argv[4]) if len(sys.argv) > 4 else 1.0
gain = float(sys.argv[5]) if len(sys.argv) > 5 else 1.0
size = int(sys.argv[6]) if len(sys.argv) > 6 else 2048

im = Image.open(src).convert("RGB")
im = ImageEnhance.Color(im).enhance(sat)
im = ImageEnhance.Brightness(im).enhance(gain)
a = np.asarray(im, dtype=np.float32)


def cut_path(err):
    """Minimum-error top-to-bottom path through err (h, w): column index per row."""
    h, w = err.shape
    cost = err.copy()
    for y in range(1, h):
        prev = cost[y - 1]
        left = np.r_[np.inf, prev[:-1]]
        right = np.r_[prev[1:], np.inf]
        cost[y] += np.minimum(np.minimum(left, prev), right)
    path = np.zeros(h, dtype=int)
    path[-1] = int(np.argmin(cost[-1]))
    for y in range(h - 2, -1, -1):
        x = path[y + 1]
        lo, hi = max(0, x - 1), min(w, x + 2)
        path[y] = lo + int(np.argmin(cost[y, lo:hi]))
    return path


def wrap_x(a):
    """Seamless across: the last ow columns are folded onto the first ow along the best cut."""
    h, w, _ = a.shape
    ow = int(w * 0.12)
    left, right = a[:, :ow], a[:, w - ow:]
    err = ((left - right) ** 2).sum(axis=2)
    err = (err + np.roll(err, 1, 0) + np.roll(err, -1, 0) + np.roll(err, 1, 1) + np.roll(err, -1, 1)) / 5  # steadier path
    path = cut_path(err)
    res = a[:, : w - ow].copy()
    xs = np.arange(ow)[None, :]
    take_right = xs < path[:, None]  # left of the cut: the copy that continues the right edge
    # A 3-px feather along the cut.
    d = (xs - path[:, None]).astype(np.float32)
    f = np.clip((d + 1.5) / 3.0, 0, 1)[..., None]
    res[:, :ow] = right * (1 - f) + left * f
    return res


a = wrap_x(a)
a = wrap_x(a.transpose(1, 0, 2)).transpose(1, 0, 2)
col = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).resize((size, size), Image.LANCZOS)
col.save(f"{out}/{name}.jpg", quality=90)

# Normal map from luminance (wrapping slopes, so it tiles too).
lum = np.asarray(col.convert("L").filter(ImageFilter.GaussianBlur(1.2)), dtype=np.float32) / 255.0
dx = (np.roll(lum, -1, axis=1) - np.roll(lum, 1, axis=1)) * 2.0
dy = (np.roll(lum, -1, axis=0) - np.roll(lum, 1, axis=0)) * 2.0
strength = 2.5
n = np.dstack([-dx * strength, dy * strength, np.ones_like(lum)])
n /= np.linalg.norm(n, axis=2, keepdims=True)
Image.fromarray(((n * 0.5 + 0.5) * 255).astype(np.uint8)).resize((size // 2, size // 2), Image.LANCZOS).save(f"{out}/{name}_n.png", optimize=True)
print(name, col.size)
