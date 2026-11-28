"""孙羊正店's 彩楼欢门 (画中游 0.5.1, device 2026-11-27: the tavern's festive gateway, a heap of sharp crumpled sheets
up close in the Seed3D tavern): a bamboo scaffold gateway with real ribbons, to stand in front of the tavern, its
own colours on a trellis of poles, the light baked in (kit3d).

blender -b --factory-startup -P tools/cailou.py -- <out_dir>    (qm_cailou_t.glb and qm_cailou_t_lo.glb)

Model frame: origin at the gateway's foot centre, front +z, width along x (5.6 m), 7.2 m tall, 1.4 m deep. As in
the painting: four tall bamboo posts in two pairs, rails and diagonals tied with rope, a stepped top of poles in a
fan with little banners, swags of red, green and yellow cloth hanging between the posts in deep curves, streamers
hanging straight down at the posts, a signboard over the opening.
"""
import math
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "tools", "blender"))
import kit3d as K  # noqa: E402
from mathutils import Vector  # noqa: E402

OUT = sys.argv[sys.argv.index("--") + 1]
os.makedirs(OUT, exist_ok=True)
W, D, H = 5.6, 1.4, 7.2


def pole(m, a, b, r, seg):
    a, b = Vector(a), Vector(b)
    d = b - a
    L = d.length
    if L < 1e-6:
        return
    ez = d / L
    ref = Vector((0, 1, 0)) if abs(ez.y) < 0.9 else Vector((1, 0, 0))
    ex = ref.cross(ez).normalized()
    ey = ez.cross(ex).normalized()
    t = K.MATS[m]["tile"]
    for i in range(seg):
        a0, a1 = 2 * math.pi * i / seg, 2 * math.pi * (i + 1) / seg
        p0 = a + (ex * math.cos(a0) + ey * math.sin(a0)) * r
        p1 = a + (ex * math.cos(a1) + ey * math.sin(a1)) * r
        q0 = b + (ex * math.cos(a0) + ey * math.sin(a0)) * r
        q1 = b + (ex * math.cos(a1) + ey * math.sin(a1)) * r
        K.poly(m, [tuple(p1), tuple(p0), tuple(q0), tuple(q1)], [(a1 * r / t, 0), (a0 * r / t, 0), (a0 * r / t, L / t), (a1 * r / t, L / t)])
    # Nodes of the bamboo: a slightly thicker ring every 0.45 m.
    if m == "bamboo":
        n = int(L / 0.45)
        for k in range(1, n):
            c = a + d * (k / n)
            for i in range(seg):
                a0, a1 = 2 * math.pi * i / seg, 2 * math.pi * (i + 1) / seg
                p0 = c - ez * 0.02 + (ex * math.cos(a0) + ey * math.sin(a0)) * r * 1.12
                p1 = c - ez * 0.02 + (ex * math.cos(a1) + ey * math.sin(a1)) * r * 1.12
                q0 = c + ez * 0.02 + (ex * math.cos(a0) + ey * math.sin(a0)) * r * 1.12
                q1 = c + ez * 0.02 + (ex * math.cos(a1) + ey * math.sin(a1)) * r * 1.12
                K.quad("node", tuple(p1), tuple(p0), tuple(q0), tuple(q1))


def swag(m, a, b, sag, width, n, twist=0.0):
    """A strip of cloth hanging from a to b in a curve, [width] tall, both sides (two quads per step)."""
    a, b = Vector(a), Vector(b)
    pts = []
    for i in range(n + 1):
        t = i / n
        p = a.lerp(b, t) + Vector((0, -sag * 4 * t * (1 - t), 0))
        pts.append(p)
    for i in range(n):
        t0, t1 = i / n, (i + 1) / n
        # The cloth's width runs down and a little out, folded (a ripple in and out).
        f0 = 0.06 * math.sin(t0 * math.pi * 6 + twist)
        f1 = 0.06 * math.sin(t1 * math.pi * 6 + twist)
        a0, a1 = pts[i], pts[i + 1]
        b0 = a0 + Vector((0, -width, f0 + 0.05))
        b1 = a1 + Vector((0, -width, f1 + 0.05))
        uv = [(t0 * 4, 1), (t1 * 4, 1), (t1 * 4, 0), (t0 * 4, 0)]
        K.poly(m, [tuple(a0), tuple(a1), tuple(b1), tuple(b0)], uv)
        K.poly(m, [tuple(b0), tuple(b1), tuple(a1), tuple(a0)], uv)


def streamer(m, top, length, width, wave=0.06, n=10, phase=0.0):
    top = Vector(top)
    for i in range(n):
        t0, t1 = i / n, (i + 1) / n
        y0, y1 = -length * t0, -length * t1
        x0, x1 = wave * math.sin(t0 * 7 + phase), wave * math.sin(t1 * 7 + phase)
        w0, w1 = width * (1 - 0.3 * t0), width * (1 - 0.3 * t1)
        a = top + Vector((x0 - w0 / 2, y0, 0.0))
        b = top + Vector((x0 + w0 / 2, y0, 0.0))
        c = top + Vector((x1 + w1 / 2, y1, 0.02 * math.sin(t1 * 5 + phase)))
        d = top + Vector((x1 - w1 / 2, y1, 0.02 * math.sin(t1 * 5 + phase)))
        K.poly(m, [tuple(d), tuple(c), tuple(b), tuple(a)], [(0, t1), (1, t1), (1, t0), (0, t0)])
        K.poly(m, [tuple(a), tuple(b), tuple(c), tuple(d)], [(0, t0), (1, t0), (1, t1), (0, t1)])


def build(lo):
    rnd = random.Random(31)
    K.reset()
    seg = 6 if lo else 10
    n = 8 if lo else 18
    K.mat("bamboo", K.tex("fine_grained_wood"), tile=0.6, rgb=(1.7, 1.45, 0.9), px=1024)
    K.mat("node", rgb=(0.55, 0.46, 0.26))
    K.mat("rope", rgb=(0.62, 0.52, 0.36))
    K.mat("red", rgb=(0.78, 0.12, 0.10))
    K.mat("green", rgb=(0.12, 0.48, 0.30))
    K.mat("yellow", rgb=(0.92, 0.70, 0.18))
    K.mat("blue", rgb=(0.20, 0.36, 0.66))
    K.mat("board", K.tex("old_planks_02"), tile=1.0, rgb=(0.95, 0.80, 0.6), px=1024)
    K.mat("gold", rgb=(0.85, 0.66, 0.22))
    xs = (-W / 2, -W / 2 + 0.9, W / 2 - 0.9, W / 2)
    zs = (-D / 2, D / 2)
    # Posts.
    for x in xs:
        for z in zs:
            hpost = H - 0.6 if abs(x) < W / 2 else H - 1.6
            pole("bamboo", (x, 0, z), (x, hpost, z), 0.07, seg)
    # Rails across and along, at four heights; diagonals on the outer bays.
    for y in (0.6, 2.4, 3.9, 5.1):
        for z in zs:
            pole("bamboo", (-W / 2 - 0.15, y, z), (W / 2 + 0.15, y, z), 0.05, seg)
        for x in xs:
            pole("bamboo", (x, y, -D / 2 - 0.12), (x, y, D / 2 + 0.12), 0.045, seg)
    for sx in (-1, 1):
        for z in zs:
            pole("bamboo", (sx * W / 2, 0.6, z), (sx * (W / 2 - 0.9), 2.4, z), 0.04, seg)
            pole("bamboo", (sx * W / 2, 2.4, z), (sx * (W / 2 - 0.9), 3.9, z), 0.04, seg)
    # The stepped top: a fan of poles over the middle, rising to a peak, a ridge pole, and two side peaks.
    for z in zs:
        for (x0, y0, x1, y1) in ((-W / 2 + 0.9, 5.1, 0.0, H), (W / 2 - 0.9, 5.1, 0.0, H), (-W / 2, 4.6, -W / 2 + 0.9, 5.9), (W / 2, 4.6, W / 2 - 0.9, 5.9)):
            pole("bamboo", (x0, y0, z), (x1, y1, z), 0.045, seg)
        for k in range(1, 5):
            x = (W / 2 - 0.9) * k / 5
            for sx in (-1, 1):
                pole("bamboo", (sx * x, 5.1, z), (sx * x, 5.1 + (H - 5.1) * (1 - k / 5), z), 0.035, seg)
    pole("bamboo", (0, H, -D / 2 - 0.1), (0, H, D / 2 + 0.1), 0.05, seg)
    # Rope lashings at the joints (little wraps).
    if not lo:
        for x in xs:
            for z in zs:
                for y in (0.6, 2.4, 3.9, 5.1):
                    pole("rope", (x, y - 0.06, z), (x, y + 0.06, z), 0.085, 8)
    # Swags between the posts across the front and back, at two heights; deep curves of red over green.
    for z, ph in ((D / 2 + 0.08, 0.0), (-D / 2 - 0.08, 1.3)):
        for (xa, xb) in ((-W / 2, -W / 2 + 0.9), (-W / 2 + 0.9, W / 2 - 0.9), (W / 2 - 0.9, W / 2)):
            wide = xb - xa > 2
            swag("red", (xa, 5.05, z), (xb, 5.05, z), 0.75 if wide else 0.35, 0.42 if wide else 0.3, n, ph)
            swag("green", (xa, 4.55, z + 0.03), (xb, 4.55, z + 0.03), 0.55 if wide else 0.25, 0.3, n, ph + 1)
            if wide:
                swag("yellow", (xa, 3.85, z + 0.05), (xb, 3.85, z + 0.05), 0.35, 0.22, n, ph + 2)
        # Streamers straight down at the posts, and short banners along the top fan.
        for x in xs:
            for k, mname in enumerate(("red", "blue", "yellow", "green")):
                streamer(mname, (x + (k - 1.5) * 0.09, 5.0 - k * 0.05, z + 0.04), rnd.uniform(1.6, 2.8), 0.12, n=6 if lo else 12, phase=rnd.uniform(0, 6))
        for k in range(-4, 5):
            x = k * 0.55
            y = 5.1 + (H - 5.1) * (1 - abs(x) / (W / 2 - 0.9)) + 0.05
            if y > 5.15:
                streamer(rnd.choice(["red", "green", "yellow", "blue"]), (x, y + 0.45, z + 0.03), 0.45, 0.22, wave=0.03, n=3 if lo else 5, phase=k)
    # The signboard over the opening, framed in gold.
    K.box("board", 0, 3.25, D / 2 + 0.1, 2.0, 0.6, 0.06)
    K.box("gold", 0, 3.25, D / 2 + 0.075, 2.12, 0.72, 0.03)


for lo in (False, True):
    build(lo)
    path = os.path.join(OUT, "qm_cailou_t_lo.glb" if lo else "qm_cailou_t.glb")
    K.export(path, key=(-0.45, 0.75, 0.6), sky=(0.88, 0.90, 0.93), ground=(0.5, 0.47, 0.42), key_rgb=(1.0, 0.95, 0.86),
             key_k=0.4, gain=1.3, ao_dist=1.0, ao_floor=0.55)
