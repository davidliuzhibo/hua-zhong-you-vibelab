"""The willows and pines (画中游 0.5.1, device 2026-11-27: 近景的草木要做得相当之精细): built leaf by leaf instead of
the Seed3D trees whose crowns were soft green lumps. Real geometry (no alpha cards: the headset's alpha clip is not
relied on), the light and the crowns' own shade baked in (kit3d). Same size and placement as the old models (origin
at the foot, front +z): t_willow 9 m tall, ~8.7 m across; ql_pine 12 m tall, ~13.7 m across.

blender -b --factory-startup -P tools/trees.py -- <out_dir>   (t_willow.glb, t_willow_lo.glb, ql_pine.glb, ql_pine_lo.glb)

垂柳: a leaning, gnarled trunk splitting into four limbs that arch out; from the limbs' ends and along them, strands
of leaves hang 2-4 m, each a chain of narrow leaves (two triangles each) swaying out a little. 松 (as 千里江山图): a
twisting red-brown trunk, branches reaching out level, on each a flat pad of needles in tufts radiating from its middle.
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


def tube(m, pts, radii, seg=8):
    """A tapering tube through [pts] (game frame) with [radii]; bark texture along it."""
    t = K.MATS[m]["tile"]
    rings = []
    for i, p in enumerate(pts):
        p = Vector(p)
        d = (Vector(pts[min(i + 1, len(pts) - 1)]) - Vector(pts[max(i - 1, 0)])).normalized()
        ref = Vector((0, 1, 0)) if abs(d.y) < 0.9 else Vector((1, 0, 0))
        ex = ref.cross(d).normalized()
        ey = d.cross(ex).normalized()
        rings.append([p + (ex * math.cos(2 * math.pi * k / seg) + ey * math.sin(2 * math.pi * k / seg)) * radii[i] for k in range(seg + 1)])
    acc = 0.0
    for i in range(len(pts) - 1):
        l = (Vector(pts[i + 1]) - Vector(pts[i])).length
        for k in range(seg):
            a, b = rings[i][k], rings[i][k + 1]
            c, d = rings[i + 1][k + 1], rings[i + 1][k]
            u0, u1 = k / seg * 2 * math.pi * radii[i] / t, (k + 1) / seg * 2 * math.pi * radii[i] / t
            K.poly(m, [tuple(b), tuple(a), tuple(d), tuple(c)], [(u1, acc / t), (u0, acc / t), (u0, (acc + l) / t), (u1, (acc + l) / t)])
        acc += l


def bezier(a, b, c, n):
    a, b, c = Vector(a), Vector(b), Vector(c)
    return [tuple((1 - t) ** 2 * a + 2 * (1 - t) * t * b + t * t * c) for t in (i / n for i in range(n + 1))]


def leaf(m, p, d, side, length, width):
    """A narrow leaf from p along d (unit), its blade spread toward side (unit): a diamond of two triangles."""
    p, d, side = Vector(p), Vector(d), Vector(side)
    tip = p + d * length
    mid = p + d * length * 0.45
    l1 = mid + side * width
    l2 = mid - side * width
    K.tri(m, tuple(p), tuple(l1), tuple(tip))
    K.tri(m, tuple(p), tuple(tip), tuple(l2))


def willow(lo):
    rnd = random.Random(11)
    K.reset()
    K.mat("bark", K.tex("rough_wood"), tile=0.8, rgb=(0.62, 0.52, 0.42), smooth=True, px=1024)
    K.mat("leaf", rgb=(0.42, 0.60, 0.22))
    K.mat("leaf2", rgb=(0.55, 0.68, 0.28))
    K.mat("stem", rgb=(0.40, 0.34, 0.20))
    seg = 6 if lo else 9
    # Trunk: leaning a little, gnarled.
    trunk = [(0.0, 0.0, 0.0), (0.15, 1.0, 0.05), (0.05, 2.0, -0.1), (0.3, 2.9, 0.05), (0.35, 3.4, 0.1)]
    tube("bark", trunk, [0.42, 0.33, 0.3, 0.26, 0.24], seg)
    top = Vector(trunk[-1])
    limbs = []
    for k in range(4):
        a = math.radians(k * 90 + rnd.uniform(-25, 25))
        out = Vector((math.sin(a), 0, math.cos(a)))
        mid = top + out * 1.6 + Vector((0, 1.6, 0))
        end = top + out * 3.2 + Vector((0, 2.3 + rnd.uniform(-0.3, 0.6), 0))
        pts = bezier(top, mid, end, 6)
        tube("bark", pts, [0.22 - 0.025 * i for i in range(7)], seg)
        limbs.append(pts)
        # Two side branches off each limb.
        for j in (2, 4):
            b0 = Vector(pts[j])
            side = Vector((math.cos(a + rnd.choice((-1, 1)) * 0.9), 0, math.sin(a) * 0)).normalized() if False else Vector((math.sin(a + rnd.uniform(-1.2, 1.2)), 0, math.cos(a + rnd.uniform(-1.2, 1.2)))).normalized()
            e = b0 + side * rnd.uniform(1.4, 2.2) + Vector((0, rnd.uniform(0.6, 1.3), 0))
            bp = bezier(b0, (b0 + e) / 2 + Vector((0, 0.5, 0)), e, 4)
            tube("bark", bp, [0.09, 0.08, 0.06, 0.05, 0.04], max(5, seg - 3))
            limbs.append(bp)
    # Strands hanging from points along the limbs' outer halves (an umbrella of curtains).
    n_strands = 70 if lo else 190
    anchors = []
    for pts in limbs:
        for i in range(len(pts) // 2, len(pts)):
            anchors.append(Vector(pts[i]))
    for s in range(n_strands):
        base = rnd.choice(anchors) + Vector((rnd.uniform(-0.7, 0.7), rnd.uniform(-0.2, 0.3), rnd.uniform(-0.7, 0.7)))
        # Out from the trunk a little as it falls.
        outv = Vector((base.x, 0, base.z))
        outv = outv.normalized() if outv.length > 0.01 else Vector((1, 0, 0))
        length = rnd.uniform(2.2, min(4.6, base.y - 0.6))
        n = 6 if lo else 12
        pts = []
        for i in range(n + 1):
            f = i / n
            p = base + outv * (0.6 * f * (1 - 0.4 * f)) + Vector((0, -length * f, 0)) + Vector((0, 0.35 * math.sin(f * math.pi), 0))
            pts.append(p)
        # The thin stem.
        for i in range(n):
            K.beam("stem", tuple(pts[i]), tuple(pts[i + 1]), 0.012, 0.012) if not lo and i % 2 == 0 else None
        # Leaves along it, alternate sides, pointing down and out.
        mname = rnd.choice(["leaf", "leaf", "leaf2"])
        step = 2 if lo else 1
        for i in range(0, n, step):
            p = pts[i]
            d = (pts[i + 1] - pts[i]).normalized()
            side = d.cross(Vector((0, 1, 0)))
            side = side.normalized() if side.length > 0.01 else Vector((1, 0, 0))
            for sgn in (-1, 1):
                ld = (d * 0.6 + side * sgn * 0.55 + Vector((rnd.uniform(-0.2, 0.2), 0, rnd.uniform(-0.2, 0.2)))).normalized()
                leaf(mname, p, ld, ld.cross(Vector((0, 1, 0))).normalized() if abs(ld.y) < 0.95 else side, rnd.uniform(0.12, 0.2) * (1.4 if lo else 1), 0.022 * (1.4 if lo else 1))
    # A few leaves on the limbs themselves.
    for pts in limbs:
        for p in pts[2:]:
            for _ in range(3 if lo else 8):
                d = Vector((rnd.uniform(-1, 1), rnd.uniform(-1, 0.2), rnd.uniform(-1, 1))).normalized()
                leaf("leaf", tuple(Vector(p) + Vector((rnd.uniform(-0.3, 0.3), rnd.uniform(-0.2, 0.2), rnd.uniform(-0.3, 0.3)))), d, d.cross(Vector((0, 1, 0))).normalized(), 0.16, 0.025)


def pine(lo):
    rnd = random.Random(23)
    K.reset()
    K.mat("bark", K.tex("rough_wood"), tile=0.9, rgb=(0.78, 0.50, 0.36), smooth=True, px=1024)
    K.mat("needle", rgb=(0.20, 0.42, 0.30))
    K.mat("needle2", rgb=(0.30, 0.55, 0.36))
    seg = 6 if lo else 10
    # A twisting trunk leaning one way, then back.
    trunk = [(0.0, 0.0, 0.0), (0.4, 1.8, 0.1), (0.1, 3.6, -0.2), (-0.6, 5.4, 0.0), (-0.4, 7.2, 0.3), (0.5, 9.0, 0.2), (0.8, 10.4, 0.0)]
    tube("bark", trunk, [0.5, 0.42, 0.36, 0.3, 0.24, 0.17, 0.1], seg)
    pads = []
    # Branches reaching out level from the trunk's upper part, each ending in a pad (and a smaller pad half way).
    for k, (i, ang, reach) in enumerate(((2, 30, 4.8), (3, 200, 5.6), (3, 330, 3.5), (4, 110, 4.2), (4, 250, 3.2), (5, 20, 3.0), (5, 170, 2.6), (6, 290, 1.6))):
        b0 = Vector(trunk[i])
        a = math.radians(ang)
        out = Vector((math.sin(a), 0, math.cos(a)))
        e = b0 + out * reach + Vector((0, rnd.uniform(-0.2, 0.5), 0))
        bp = bezier(b0, b0 + out * reach * 0.5 + Vector((0, 0.4, 0)), e, 5)
        tube("bark", bp, [0.16 - 0.02 * j for j in range(6)], max(5, seg - 4))
        pads.append((e, 1.4 + reach * 0.22))
        pads.append((Vector(bp[2]) + Vector((0, 0.2, 0)), 0.9 + reach * 0.12))
    pads.append((Vector(trunk[-1]) + Vector((0, 0.3, 0)), 1.5))
    for (c, r) in pads:
        # A flat pad: tufts of needles over a disc, each tuft a fan of thin needles radiating out and up a little.
        n_tufts = int((10 if lo else 26) * r)
        for t in range(n_tufts):
            a = rnd.uniform(0, 2 * math.pi)
            rr = r * math.sqrt(rnd.random())
            p = c + Vector((math.cos(a) * rr, rnd.uniform(-0.12, 0.18) * (1 - rr / r) + 0.05, math.sin(a) * rr))
            mname = "needle2" if p.y > c.y + 0.05 else "needle"
            n_need = 7 if lo else 14
            for q in range(n_need):
                b = 2 * math.pi * q / n_need + rnd.uniform(-0.2, 0.2)
                d = Vector((math.cos(b), rnd.uniform(0.05, 0.35), math.sin(b))).normalized()
                L = rnd.uniform(0.18, 0.3) * (1.5 if lo else 1)
                tip = p + d * L
                w = d.cross(Vector((0, 1, 0)))
                w = w.normalized() * 0.012 * (1.5 if lo else 1) if w.length > 0.01 else Vector((0.012, 0, 0))
                K.tri(mname, tuple(p - w), tuple(p + w), tuple(tip))


for name, fn in (("t_willow", willow), ("ql_pine", pine)):
    for lo in (False, True):
        fn(lo)
        path = os.path.join(OUT, f"{name}_lo.glb" if lo else f"{name}.glb")
        K.export(path, key=(-0.45, 0.75, 0.6), sky=(0.86, 0.90, 0.94), ground=(0.46, 0.44, 0.38), key_rgb=(1.0, 0.95, 0.85),
                 key_k=0.38, gain=1.35, ao_dist=2.2, ao_floor=0.45)
