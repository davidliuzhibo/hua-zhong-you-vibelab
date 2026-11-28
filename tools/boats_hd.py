"""虹桥's other near boats and the big boat's mast, built for real (画中游 0.5.2, device 2026-11-28: near objects within
10 m 「使用更加高清的模型」): the Seed3D models each had one picture spread over the whole boat. Pictures from
assets_lab/huazhong/hd_tex/final (Seedream): varnished and lacquered planks, woven bamboo for the canopy, a woven mat
sail, hemp rope, peeled logs; the light baked in (kit3d).

blender -b --factory-startup -P tools/boats_hd.py -- <out_dir>
    qm_mast(_lo).glb     the mast: foot at the origin (the hinge pin), 12 m up +y, the bow +z; the sail furled on its bow
                         side (lowered toward the stern it lies on top)
    qm_canopy(_lo).glb   the canopy boat moored by the bank: 7.0 long (bow +x), 2.4 wide, 2.44 to the canopy's top
    qm_rowboat(_lo).glb  the little boat that passes the player: 5.0 long, 1.8 to its upswept ends, a bamboo pole, a basket
"""
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "tools", "blender"))
import kit3d as K  # noqa: E402
from mathutils import Vector  # noqa: E402

OUT = sys.argv[sys.argv.index("--") + 1]
os.makedirs(OUT, exist_ok=True)
HD = os.path.join(K.LAB, "huazhong", "hd_tex", "final")


def hd(name):
    return os.path.join(HD, name)


def face(m, pts, out, uvs=None):
    a, b, d = Vector(pts[0]), Vector(pts[1]), Vector(pts[-1])
    if (b - a).cross(d - a).dot(Vector(out)) < 0:
        pts = pts[::-1]
        uvs = uvs[::-1] if uvs else None
    if uvs:
        K.poly(m, [tuple(p) for p in pts], uvs)
    else:
        K.quad(m, *pts) if len(pts) == 4 else K.tri(m, *pts)


def tube(m, pts, radii, seg, tile, along_u=False, caps=None):
    """A round tube through [pts] (game frame) with [radii] at each; uv: around x along (or along x around)."""
    pts = [Vector(p) for p in pts]
    rings = []
    acc = [0.0]
    for i in range(1, len(pts)):
        acc.append(acc[-1] + (pts[i] - pts[i - 1]).length)
    for i, p in enumerate(pts):
        d = (pts[min(i + 1, len(pts) - 1)] - pts[max(i - 1, 0)]).normalized()
        ref = Vector((0, 1, 0)) if abs(d.y) < 0.9 else Vector((1, 0, 0))
        ex = ref.cross(d).normalized()
        ey = d.cross(ex).normalized()
        rings.append([p + (ex * math.cos(2 * math.pi * k / seg) + ey * math.sin(2 * math.pi * k / seg)) * radii[i] for k in range(seg + 1)])
    for i in range(len(pts) - 1):
        for k in range(seg):
            ra, rb = radii[i], radii[i + 1]
            a0 = 2 * math.pi * k / seg * max(ra, rb) / tile
            a1 = 2 * math.pi * (k + 1) / seg * max(ra, rb) / tile
            l0, l1 = acc[i] / tile, acc[i + 1] / tile
            q = [rings[i][k + 1], rings[i][k], rings[i + 1][k], rings[i + 1][k + 1]]
            uv = [(a1, l0), (a0, l0), (a0, l1), (a1, l1)] if not along_u else [(l0, a1), (l0, a0), (l1, a0), (l1, a1)]
            K.poly(m, [tuple(v) for v in q], uv)
    if caps:
        for i, sign in ((0, -1), (len(pts) - 1, 1)):
            ring = rings[i][:-1]
            c = pts[i]
            if sign < 0:
                ring = ring[::-1]
            K.poly(caps, [tuple(v) for v in ring], [(0.5 + 0.44 * math.cos(2 * math.pi * k / seg), 0.5 + 0.44 * math.sin(2 * math.pi * k / seg)) for k in (range(seg)[::-1] if sign < 0 else range(seg))])


def ring_band(m, axis_pts, r, seg, width_tile=0.5):
    """A rope band round a tube between two points on its axis."""
    tube(m, axis_pts, [r] * len(axis_pts), seg, width_tile, along_u=False)


def lathe(m, cx, cy, cz, prof, seg, tile=1.0, inside=None, bottom=None):
    """A body of revolution about y at (cx, cy, cz): [prof] = [(radius, height), ...] bottom up; faces outward;
    [inside]: the material of an inner skin (an open basket); [bottom]: of a floor."""
    acc = [0.0]
    for j in range(1, len(prof)):
        acc.append(acc[-1] + math.hypot(prof[j][0] - prof[j - 1][0], prof[j][1] - prof[j - 1][1]))
    for j in range(len(prof) - 1):
        (r0, h0), (r1, h1) = prof[j], prof[j + 1]
        for i in range(seg):
            t0, t1 = 2 * math.pi * i / seg, 2 * math.pi * (i + 1) / seg
            p = [(cx + r0 * math.cos(t0), cy + h0, cz + r0 * math.sin(t0)), (cx + r0 * math.cos(t1), cy + h0, cz + r0 * math.sin(t1)),
                 (cx + r1 * math.cos(t1), cy + h1, cz + r1 * math.sin(t1)), (cx + r1 * math.cos(t0), cy + h1, cz + r1 * math.sin(t0))]
            rm = max(r0, r1, 0.01)
            uv = [(t0 * rm / tile, acc[j] / tile), (t1 * rm / tile, acc[j] / tile), (t1 * rm / tile, acc[j + 1] / tile), (t0 * rm / tile, acc[j + 1] / tile)]
            tm = (t0 + t1) / 2
            out = (math.cos(tm), 0, math.sin(tm))
            face(m, p, out, uv)
            if inside:
                q = [(cx + (v[0] - cx) * 0.93, v[1], cz + (v[2] - cz) * 0.93) for v in p]
                face(inside, q, (-out[0], 0, -out[2]), uv)
    if bottom:
        r0, h0 = prof[0]
        pts = [(cx + r0 * math.cos(2 * math.pi * i / seg), cy + h0 + 0.01, cz + r0 * math.sin(2 * math.pi * i / seg)) for i in range(seg)]
        K.poly(bottom, pts[::-1] if Vector(pts[1]).cross(Vector(pts[0])).y > 0 else pts, [(0.5 + 0.5 * math.cos(2 * math.pi * i / seg), 0.5 + 0.5 * math.sin(2 * math.pi * i / seg)) for i in range(seg)])


# ---- Hulls ------------------------------------------------------------------------------------------------------------
class Hull:
    def __init__(self, L, B, keel_rise, sheer0, sheer_rise, sheer_pow=3.0, beam_pow=3.0, bow_up=0.0):
        self.L, self.B = L, B
        self.keel_rise, self.sheer0, self.sheer_rise, self.sheer_pow, self.beam_pow, self.bow_up = keel_rise, sheer0, sheer_rise, sheer_pow, beam_pow, bow_up

    def half_beam(self, x):
        t = min(1.0, abs(x) / self.L)
        return max(0.06, self.B * (1 - t ** self.beam_pow))

    def sheer(self, x):
        t = abs(x) / self.L
        return self.sheer0 + self.sheer_rise * t ** self.sheer_pow + (self.bow_up * t ** 2 if x > 0 else 0)

    def keel(self, x):
        t = abs(x) / self.L
        return self.keel_rise * t ** 3

    def pt(self, x, v, side):
        hb = self.half_beam(x)
        y0, y1 = self.keel(x), self.sheer(x)
        yb = y0 + 0.5 * (y1 - y0)
        if v <= 0.6:
            th = v / 0.6 * math.pi / 2
            return (x, y0 + (yb - y0) * (1 - math.cos(th)), side * hb * 0.94 * math.sin(th))
        f = (v - 0.6) / 0.4
        return (x, yb + (y1 - yb) * f, side * hb * (0.94 + 0.06 * f))

    def build(self, m, nx, nv, tile, inside=None, plank_u=True):
        L = self.L
        xs = [-L + 2 * L * i / nx for i in range(nx + 1)]
        for side in (-1, 1):
            for i in range(nx):
                for j in range(nv):
                    p = [self.pt(xs[i], j / nv, side), self.pt(xs[i + 1], j / nv, side), self.pt(xs[i + 1], (j + 1) / nv, side), self.pt(xs[i], (j + 1) / nv, side)]
                    s = [(q[1] + abs(q[2]) * 0.5) / tile for q in p]
                    uv = [(q[0] / tile, s[k]) for k, q in enumerate(p)] if plank_u else [(s[k], q[0] / tile) for k, q in enumerate(p)]
                    if side < 0:
                        p = p[::-1]
                        uv = uv[::-1]
                    K.poly(m, p, uv)
                    if inside:
                        # The inside skin (seen over the gunwale), a little in.
                        pin = [(q[0], q[1], q[2] * 0.96) for q in p]
                        K.poly(inside, pin[::-1], uv[::-1])
        # Stem and stern posts closing the ends.
        for sx in (-1, 1):
            x = sx * L
            w = self.half_beam(x) + 0.03
            face(m, [(x, self.keel(x), -w), (x, self.keel(x), w), (x, self.sheer(x) + 0.02, w), (x, self.sheer(x) + 0.02, -w)], (sx, 0, 0))
            for zs in (-1, 1):
                face(m, [(x - sx * 0.0, self.keel(x), zs * w), (x - sx * 0.12, self.keel(x - sx * 0.12), zs * w),
                         (x - sx * 0.12, self.sheer(x - sx * 0.12) + 0.02, zs * w), (x, self.sheer(x) + 0.02, zs * w)], (0, 0, zs))
        return xs


def mast(lo):
    K.reset()
    seg = 8 if lo else 14
    px = 512 if lo else 1024
    K.mat("pole", hd("log.jpg"), tile=1.2, rgb=(0.70, 0.58, 0.47), smooth=True, px=px)
    K.mat("end", hd("endgrain.jpg"), rgb=(0.7, 0.6, 0.5), px=512)
    K.mat("sail", hd("sail.jpg"), tile=1.0, rgb=(0.86, 0.78, 0.64), smooth=True, px=px)
    K.mat("lac", hd("rail_a.jpg"), px=px)
    K.mat("iron", rgb=(0.12, 0.10, 0.09))
    if not lo:
        K.mat("rope", hd("rope.jpg"), tile=0.5, rgb=(0.78, 0.70, 0.58), px=512)
    # The pole, tapering, from the pin's block to the top.
    n = 8
    ys = [0.15 + (11.85 - 0.15) * i / n for i in range(n + 1)]
    tube("pole", [(0, y, 0) for y in ys], [0.15 - 0.06 * (y / 12.0) for y in ys], seg, 1.2, caps="end")
    # The heel block where the pin goes through (between the boat's tabernacle cheeks), lacquered.
    K.box("iron", 0, 0.0, 0, 0.5, 0.05, 0.05)
    K.box("lac", 0, 0.18, 0, 0.3, 0.5, 0.3)
    # Iron hoops up the pole.
    for y in (0.6, 2.1, 3.6, 5.1, 6.6, 8.1, 9.6):
        r = 0.15 - 0.06 * (y / 12.0) + 0.012
        tube("iron", [(0, y, 0), (0, y + 0.06, 0)], [r, r], seg, 1.0)
    # The masthead: a cap and a sheave block, a coil of the halyard round the pole under it.
    K.box("lac", 0, 11.95, 0, 0.24, 0.14, 0.24)
    K.box("lac", 0, 11.6, 0.14, 0.12, 0.34, 0.12)
    if not lo:
        tube("rope", [(0, 10.9, 0), (0, 11.25, 0)], [0.135, 0.135], seg, 0.5)
        # The halyard down the bow side to the foot.
        tube("rope", [(0, 11.45, 0.2), (0, 6.0, 0.2), (0, 0.9, 0.17)], [0.018, 0.018, 0.018], 6, 0.3)
    # The sail, furled into a bundle on the bow side, lashed every metre or so.
    zs = 0.36
    sy0, sy1 = 2.2, 9.4
    m = 10
    pts = [(0, sy0 + (sy1 - sy0) * i / m, zs) for i in range(m + 1)]
    radii = [0.07 + 0.15 * math.sin(math.pi * (0.12 + 0.76 * i / m)) for i in range(m + 1)]
    tube("sail", pts, radii, seg, 1.0, along_u=True)
    for p, r in zip(pts, radii):
        if p[1] in (pts[0][1], pts[-1][1]):
            continue
        if lo:
            continue
        tube("rope", [(0, p[1] - 0.05, zs), (0, p[1] + 0.05, zs)], [r + 0.015, r + 0.015], seg, 0.5)
    # The yard (the sail's top spar) and the boom, across the boat, at the bundle's ends.
    tube("pole", [(-0.9, sy1 + 0.1, zs), (0.9, sy1 + 0.1, zs)], [0.06, 0.06], seg, 1.2, caps="end")
    tube("pole", [(-0.8, sy0 - 0.1, zs), (0.8, sy0 - 0.1, zs)], [0.055, 0.055], seg, 1.2, caps="end")


def canopy(lo):
    K.reset()
    seg = 8 if lo else 12
    px = 512 if lo else 1024
    K.mat("hull", hd("hull_tile.jpg"), tile=2.0, rgb=(0.86, 0.72, 0.56), px=px)
    K.mat("inside", hd("deck.jpg"), tile=2.6, rgb=(1.05, 0.88, 0.66), px=px)
    K.mat("wale", hd("hull_tile.jpg"), tile=1.0, rgb=(0.50, 0.36, 0.28), px=px)
    K.mat("weave", hd("sail.jpg"), tile=0.9, rgb=(1.25, 1.02, 0.62), px=px)
    K.mat("weave_in", hd("sail.jpg"), tile=0.9, rgb=(0.80, 0.66, 0.42), px=px)
    K.mat("pole", hd("log.jpg"), tile=1.0, rgb=(0.72, 0.60, 0.48), smooth=True, px=px)
    K.mat("end", hd("endgrain.jpg"), rgb=(0.7, 0.6, 0.5), px=256)
    if not lo:
        K.mat("rope", hd("rope.jpg"), tile=0.5, rgb=(0.78, 0.70, 0.58), px=512)
    h = Hull(L=3.5, B=1.12, keel_rise=0.35, sheer0=0.72, sheer_rise=0.42, sheer_pow=2.6, beam_pow=2.6, bow_up=0.06)
    xs = h.build("hull", 16 if lo else 32, 4 if lo else 8, 2.0, inside="inside")
    # Gunwale caps and a rubbing strake.
    for side in (-1, 1):
        for i in range(len(xs) - 1):
            x0, x1 = xs[i], xs[i + 1]
            a = Vector(h.pt(x0, 1.0, side)) + Vector((0, 0.03, 0))
            b = Vector(h.pt(x1, 1.0, side)) + Vector((0, 0.03, 0))
            K.beam("wale", tuple(a), tuple(b), 0.1, 0.06)
            a = Vector(h.pt(x0, 0.75, side)) + Vector((0, 0, side * 0.03))
            b = Vector(h.pt(x1, 0.75, side)) + Vector((0, 0, side * 0.03))
            K.beam("wale", tuple(a), tuple(b), 0.06, 0.08, skip=("-z", "+z"))
    # Floorboards and thwarts.
    fy = 0.32
    face("inside", [(-2.2, fy, -0.55), (2.2, fy, -0.55), (2.2, fy, 0.55), (-2.2, fy, 0.55)], (0, 1, 0))
    for x in (-2.5, -1.6, 1.6, 2.4):
        w = h.half_beam(x) * 0.96
        K.box("inside", x, h.sheer(x) - 0.16, 0, 0.26, 0.06, 2 * w)
    # The canopy: woven bamboo from x -1.5 to 1.4: straight sides standing on the gunwales, a round vault over them,
    # on three bamboo hoops.
    cx0, cx1 = -1.5, 1.4
    r = 1.0
    foot = max(h.sheer(cx0), h.sheer(cx1)) + 0.02
    shoulder = 1.3
    rise = 2.42 - shoulder
    na = 8 if lo else 16
    # The section, from one foot over the top to the other: (z, y), its length along for the picture.
    sec = [(-r, foot)] + [(-r * math.cos(math.pi * k / na), shoulder + rise * math.sin(math.pi * k / na)) for k in range(na + 1)] + [(r, foot)]
    acc = [0.0]
    for k in range(1, len(sec)):
        acc.append(acc[-1] + math.hypot(sec[k][0] - sec[k - 1][0], sec[k][1] - sec[k - 1][1]))
    for k in range(len(sec) - 1):
        (za, ya), (zb, yb) = sec[k], sec[k + 1]
        s0, s1 = acc[k] / 1.1, acc[k + 1] / 1.1
        uv = [(cx0 / 1.1, s0), (cx1 / 1.1, s0), (cx1 / 1.1, s1), (cx0 / 1.1, s1)]
        n = (0, -(zb - za), (yb - ya))          # outward (the section runs anticlockwise seen from +x)
        mz, my = (za + zb) / 2, (ya + yb) / 2
        if n[1] * (my - shoulder * 0.9) + n[2] * mz < 0:
            n = (0, -n[1], -n[2])
        face("weave", [(cx0, ya, za), (cx1, ya, za), (cx1, yb, zb), (cx0, yb, zb)], n, uv)
        ins = 0.97
        face("weave_in", [(cx0, ya, za * ins), (cx1, ya, za * ins), (cx1, yb * 0.99 + 0.01 * foot, zb * ins), (cx0, yb * 0.99 + 0.01 * foot, zb * ins)],
             (0, -n[1], -n[2]), uv)
    for x in (cx0 + 0.04, (cx0 + cx1) / 2, cx1 - 0.04):
        pts = [(x, y + (0.02 if y > foot + 0.01 else 0), z * 1.025) for (z, y) in sec]
        tube("pole", pts, [0.03] * len(pts), 6, 1.0)
    # The ends of the vault: a lath round each open end; its feet on the gunwales, a lath along each side.
    for side in (-1, 1):
        K.beam("pole", (cx0, foot, side * r * 1.02), (cx1, foot, side * r * 1.02), 0.06, 0.05)
    # The scull (橹) at the stern on its pivot, its blade trailing; a mooring line coiled at the bow.
    tube("pole", [(-3.1, h.sheer(-3.1) + 0.2, 0.2), (-3.75, 0.3, 0.5)], [0.045, 0.04], 8, 1.0, caps="end")
    K.obox("pole", (-3.92, 0.17, 0.55), Vector((0.75, -0.65, 0.12)).normalized(), Vector((0, 0, 1)), Vector((0.65, 0.75, 0)).normalized(), 0.25, 0.012, 0.09)
    tube("pole", [(-3.0, h.sheer(-3.0), 0.2), (-2.0, h.sheer(-2.0) + 0.55, 0.2)], [0.04, 0.035], 8, 1.0, caps="end")
    if not lo:
        lathe("rope", 2.7, h.sheer(2.7) - 0.15, 0.0, [(0.12, 0), (0.17, 0.04), (0.17, 0.09), (0.07, 0.1)], 14, tile=0.25)


def rowboat(lo):
    K.reset()
    px = 512 if lo else 2048
    K.mat("hull", hd("roof.jpg"), tile=2.0, rgb=(0.82, 0.62, 0.55), px=px)
    K.mat("inside", hd("roof.jpg"), tile=2.0, rgb=(0.86, 0.62, 0.55), px=512 if lo else 1024)
    K.mat("floor", hd("hull_tile.jpg"), tile=1.6, rgb=(1.12, 0.92, 0.64), px=512 if lo else 1024)
    K.mat("wale", hd("hull_tile.jpg"), tile=1.2, rgb=(1.12, 0.92, 0.64), px=512 if lo else 1024)
    K.mat("bamboo", hd("log.jpg"), tile=0.8, rgb=(0.58, 0.82, 0.34), smooth=True, px=512)
    K.mat("weave", hd("sail.jpg"), tile=0.6, rgb=(0.95, 0.84, 0.66), px=512)
    K.mat("weave_in", hd("sail.jpg"), tile=0.6, rgb=(0.62, 0.55, 0.44), px=512)
    K.mat("end", hd("endgrain.jpg"), rgb=(0.55, 0.62, 0.40), px=256)
    h = Hull(L=2.5, B=0.86, keel_rise=0.55, sheer0=0.66, sheer_rise=1.1, sheer_pow=4.0, beam_pow=2.2, bow_up=0.0)
    # The hull's planks: the picture's planks stand up it (along v), so they run along the hull with u up the side.
    xs = h.build("hull", 16 if lo else 36, 4 if lo else 8, 2.0, inside="inside", plank_u=False)
    # Gunwale caps of the railing's lacquered timber, sweeping up to the ends.
    for side in (-1, 1):
        for i in range(len(xs) - 1):
            x0, x1 = xs[i], xs[i + 1]
            a = Vector(h.pt(x0, 1.0, side)) + Vector((0, 0.025, 0))
            b = Vector(h.pt(x1, 1.0, side)) + Vector((0, 0.025, 0))
            K.beam("wale", tuple(a), tuple(b), 0.08, 0.05, skip=("-z", "+z") if 0 < i < len(xs) - 2 else ())
    # Posts at the upswept ends.
    for sx in (-1, 1):
        x = sx * (h.L - 0.03)
        K.box("wale", x, h.sheer(x) - 0.1, 0, 0.1, 0.4, 0.1)
    # Thwarts and floorboards.
    face("floor", [(-1.6, 0.26, -0.45), (1.6, 0.26, -0.45), (1.6, 0.26, 0.45), (-1.6, 0.26, 0.45)], (0, 1, 0))
    for x in (-1.3, -0.1, 1.1):
        w = h.half_beam(x) * 0.95
        K.box("floor", x, h.sheer(x) - 0.12, 0, 0.24, 0.05, 2 * w)
    # The bamboo pole lying across the boat (on the thwarts), with its nodes, and a woven basket.
    seg = 8 if lo else 12
    a, b = Vector((-2.0, 0.62, -0.6)), Vector((1.9, 0.95, 1.0))
    nn = 10
    for k in range(nn):
        p0 = a + (b - a) * (k / nn)
        p1 = a + (b - a) * ((k + 1) / nn)
        tube("bamboo", [tuple(p0), tuple(p1)], [0.036, 0.036], seg, 0.8)
        if not lo and k > 0:
            tube("bamboo", [tuple(p0 - (b - a).normalized() * 0.018), tuple(p0 + (b - a).normalized() * 0.018)], [0.043, 0.043], seg, 0.8)
    for e in (a, b):
        tube("bamboo", [tuple(e), tuple(e + (e - (a + b) / 2).normalized() * 0.002)], [0.036, 0.036], seg, 0.8, caps="end")
    lathe("weave", 0.55, 0.27, -0.15, [(0.16, 0), (0.22, 0.08), (0.25, 0.2), (0.23, 0.32), (0.2, 0.36)], 8 if lo else 16, tile=0.6,
          inside="weave_in", bottom="weave_in")
    lathe("weave", 0.55, 0.27, -0.15, [(0.2, 0.36), (0.215, 0.375), (0.2, 0.39), (0.185, 0.375), (0.2, 0.36)], 8 if lo else 16, tile=0.1)


BUILDS = [("qm_mast", mast, dict(key=(-0.4, 0.6, 0.7), gain=1.65, ao_dist=0.6, ao_floor=0.7)),
          ("qm_canopy", canopy, dict(key=(-0.45, 0.6, 0.75), gain=1.7, ao_dist=0.9, ao_floor=0.6)),
          ("qm_rowboat", rowboat, dict(key=(-0.45, 0.6, 0.75), gain=1.7, ao_dist=0.7, ao_floor=0.62))]
ONLY = sys.argv[sys.argv.index("--") + 2:] if len(sys.argv) > sys.argv.index("--") + 2 else None
for name, fn, kw in BUILDS:
    if ONLY and name not in ONLY:
        continue
    for lo in (False, True):
        fn(lo)
        K.export(os.path.join(OUT, f"{name}_lo.glb" if lo else f"{name}.glb"), sky=(0.88, 0.9, 0.94), ground=(0.5, 0.55, 0.58),
                 key_rgb=(1.0, 0.95, 0.86), key_k=0.42, **kw)
