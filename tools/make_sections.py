"""Cuts the sections of the two scrolls out of the public-domain scans (see docs/素材来源.md).

uv run -q --with pillow python -I tools/make_sections.py <src dir> <assets dir> [<preview dir>]

<src dir> holds qingming.jpg (Wikimedia "Alongtheriver QingMing.jpg", 38414x1800) and qianli.jpg
(Wikimedia "Wang Ximeng. A Thousand Li of Rivers and Mountains. (Complete...).jpg", 39974x1600).
Writes <assets>/sections/<id>.jpg (4096 wide) for every section in SECTIONS, the two overview strips
<assets>/sections/strip_qm.jpg and strip_ql.jpg, and with a preview dir, 1600-wide previews with a
grid every 0.1 (u across, v down) to read the spots from.
"""
import os
import sys

from PIL import Image, ImageDraw

Image.MAX_IMAGE_PIXELS = None
src, out = sys.argv[1], sys.argv[2]
prev = sys.argv[3] if len(sys.argv) > 3 else None
os.makedirs(f"{out}/sections", exist_ok=True)
if prev:
    os.makedirs(prev, exist_ok=True)

# id: (file, x0, x1, y0, y1) — must match core/Scrolls.kt.
SECTIONS = {
    "qm1": ("qingming", 31200, 37000, 0, 1800),
    "qm2": ("qingming", 25200, 31200, 0, 1800),
    "qm3": ("qingming", 17400, 25200, 0, 1800),
    "qm4": ("qingming", 11800, 17400, 0, 1800),
    "qm5": ("qingming", 5200, 11800, 0, 1800),
    "qm6": ("qingming", 1300, 5200, 0, 1800),
    "ql1": ("qianli", 31500, 39300, 280, 1600),
    "ql2": ("qianli", 24500, 32300, 280, 1600),
    "ql3": ("qianli", 12000, 19800, 280, 1600),
}
only = os.environ.get("ONLY")
images = {}
for sid, (f, x0, x1, y0, y1) in SECTIONS.items():
    if only and sid not in only.split(","):
        continue
    if f not in images:
        images[f] = Image.open(f"{src}/{f}.jpg").convert("RGB")
    im = images[f].crop((x0, y0, x1, y1))
    w = 4096
    h = round(im.size[1] * w / im.size[0])
    im.resize((w, h), Image.LANCZOS).save(f"{out}/sections/{sid}.jpg", quality=86)
    print(sid, w, h)
    if prev:
        p = im.resize((1600, round(im.size[1] * 1600 / im.size[0])), Image.LANCZOS)
        d = ImageDraw.Draw(p)
        for k in range(1, 10):
            x = k * p.size[0] / 10
            y = k * p.size[1] / 10
            d.line([(x, 0), (x, p.size[1])], fill=(255, 0, 0), width=1)
            d.line([(0, y), (p.size[0], y)], fill=(255, 0, 0), width=1)
            d.text((x + 2, 2), f"{k / 10:.1f}", fill=(255, 0, 0))
            d.text((2, y + 2), f"{k / 10:.1f}", fill=(255, 0, 0))
        p.save(f"{prev}/{sid}.jpg", quality=80)
if not only:
    # The overview strips on the home wall: each whole painting (its mounting cut off), 4096 wide.
    for f, x0, x1, name in (("qingming", 1300, 37000, "strip_qm"), ("qianli", 4600, 39300, "strip_ql")):
        if f not in images:
            images[f] = Image.open(f"{src}/{f}.jpg").convert("RGB")
        im = images[f].crop((x0, 0, x1, images[f].size[1]))
        im.resize((4096, round(im.size[1] * 4096 / im.size[0])), Image.LANCZOS).save(f"{out}/sections/{name}.jpg", quality=86)
        print(name)
