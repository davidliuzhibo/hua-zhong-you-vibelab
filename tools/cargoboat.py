"""汴河漕运's grain boat unloading at our bank (画中游 0.5.2, near-field HD: 7 m from the player, the thing to find), built
for real as 虹桥's big boat: the Seed3D boat had one picture over 18 m. Varnished hull planks, a plank cabin with a
woven-mat barrel roof on bamboo hoops, heaps of grain sacks fore and aft (each sack its own shape, burlap with its seam),
a tall mast of peeled log with its yard, shrouds and stays; rudder and tiller. Pictures: assets_lab/huazhong/hd_tex/final.

blender -b --factory-startup -P tools/cargoboat.py -- <out_dir>      (qm_cargo.glb, qm_cargo_lo.glb)

Model frame as the old model: length along x (bow +x), 18 m; width along z (3.95 m); y up from the keel; the mast's top
13.5 m up.
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
HD = os.path.join(K.LAB, "huazhong", "hd_tex", "final")
L = 9.0
B = 1.95
DECK = 0.9      # the boatman stands on it at 0.9 (core/Worlds 汴河漕运)


def hd(name):
    return os.path.join(HD, name)


def half_beam(x):
    t = min(1.0, abs(x) / L)
    if x < 0:
        return B * (1 - 0.55 * t ** 3)
    return max(0.12, B * (1 - t ** 3.6))


def sheer(x):
    t = abs(x) / L
    return 1.25 + 0.85 * t ** 3.2 + (0.15 * t ** 2 if x > 0 else 0)


def keel(x):
    return 0.7 * (abs(x) / L) ** 4


def face(m, pts, out, uvs=None):
    a, b, d = Vector(pts[0]), Vector(pts[1]), Vector(pts[-1])
    if (b - a).cross(d - a).dot(Vector(out)) < 0:
        pts = pts[::-1]
        uvs = uvs[::-1] if uvs else None
    if uvs:
        K.poly(m, [tuple(p) for p in pts], uvs)
    else:
        K.quad(m, *pts) if len(pts) == 4 else K.tri(m, *pts)


def hull_pt(x, v, side):
    hb = half_beam(x)
    y0, y1 = keel(x), sheer(x)
    yb = y0 + 0.5 * (y1 - y0)
    if v <= 0.6:
        th = v / 0.6 * math.pi / 2
        return (x, y0 + (yb - y0) * (1 - math.cos(th)), side * hb * 0.94 * math.sin(th))
    f = (v - 0.6) / 0.4
    return (x, yb + (y1 - yb) * f, side * hb * (0.94 + 0.06 * f))


def tube(m, pts, r, seg, tile=1.0, caps=None):
    pts = [Vector(p) for p in pts]
    acc = [0.0]
    for i in range(1, len(pts)):
        acc.append(acc[-1] + (pts[i] - pts[i - 1]).length)
    rings = []
    for i, p in enumerate(pts):
        d = (pts[min(i + 1, len(pts) - 1)] - pts[max(i - 1, 0)]).normalized()
        ref = Vector((0, 1, 0)) if abs(d.y) < 0.9 else Vector((1, 0, 0))
        ex = ref.cross(d).normalized()
        ey = d.cross(ex).normalized()
        rr = r[i] if isinstance(r, (list, tuple)) else r
        rings.append([p + (ex * math.cos(2 * math.pi * k / seg) + ey * math.sin(2 * math.pi * k / seg)) * rr for k in range(seg + 1)])
    for i in range(len(pts) - 1):
        for k in range(seg):
            q = [rings[i][k + 1], rings[i][k], rings[i + 1][k], rings[i + 1][k + 1]]
            u0, u1 = k / seg, (k + 1) / seg
            K.poly(m, [tuple(v) for v in q], [(u1, acc[i] / tile), (u0, acc[i] / tile), (u0, acc[i + 1] / tile), (u1, acc[i + 1] / tile)])
    if caps:
        for i, s in ((0, -1), (len(pts) - 1, 1)):
            ring = rings[i][:-1] if s > 0 else rings[i][:-1][::-1]
            K.poly(caps, [tuple(v) for v in ring], [(0.5 + 0.44 * math.cos(2 * math.pi * k / seg), 0.5 + 0.44 * math.sin(2 * math.pi * k / seg)) for k in (range(seg) if s > 0 else range(seg)[::-1])])


def sack(m, c, size, yaw, nu, nv, rnd):
    """A grain sack: a rounded pillow (superellipsoid), [size] = full x, y, z; burlap round it with the seam along its top."""
    a = math.radians(yaw)
    ca, sa = math.cos(a), math.sin(a)
    e1, e2 = 0.62, 0.75

    def sp(w, e):
        return math.copysign(abs(w) ** e, w)

    def P(th, ph):
        # th: -pi..pi round (about the sack's long axis x), ph: -pi/2..pi/2 along it.
        cx = sp(math.sin(ph), e1)
        r = sp(math.cos(ph), e1)
        cy = r * sp(math.sin(th), e2)
        cz = r * sp(math.cos(th), e2)
        x, y, z = cx * size[0] / 2, cy * size[1] / 2, cz * size[2] / 2
        # A little sag on top and a bulge at the bottom.
        if y > 0:
            y *= 1 - 0.12 * (1 - abs(cx))
        else:
            y *= 1.05
        return (c[0] + x * ca + z * sa, c[1] + y, c[2] - x * sa + z * ca)

    for i in range(nu):
        for j in range(nv):
            t0, t1 = -math.pi + 2 * math.pi * i / nu, -math.pi + 2 * math.pi * (i + 1) / nu
            p0, p1 = -math.pi / 2 + math.pi * j / nv, -math.pi / 2 + math.pi * (j + 1) / nv
            q = [P(t0, p0), P(t1, p0), P(t1, p1), P(t0, p1)]
            uv = [(i / nu * 2.6, j / nv * 1.8), ((i + 1) / nu * 2.6, j / nv * 1.8), ((i + 1) / nu * 2.6, (j + 1) / nv * 1.8), (i / nu * 2.6, (j + 1) / nv * 1.8)]
            mid = Vector(P((t0 + t1) / 2, (p0 + p1) / 2)) - Vector(c)
            face(m, q, tuple(mid), uv)


def build(lo):
    K.reset()
    rnd = random.Random(7)
    px = 1024 if lo else 2048
    nx = 24 if lo else 48
    nv = 5 if lo else 9
    K.mat("hull", hd("hull_tile.jpg"), tile=2.4, rgb=(0.80, 0.62, 0.52), px=px)
    K.mat("wale", hd("hull_tile.jpg"), tile=1.2, rgb=(0.50, 0.34, 0.27), px=px)
    K.mat("deck", hd("deck.jpg"), tile=3.2, rgb=(0.95, 0.84, 0.70), px=1024)
    K.mat("wall", hd("deck.jpg"), tile=2.2, rgb=(0.86, 0.72, 0.58), px=1024)
    K.mat("mat", hd("sail.jpg"), tile=1.0, rgb=(0.95, 0.86, 0.66), px=1024)
    K.mat("sack", hd("burlap.jpg"), tile=1.0, rgb=(1.0, 0.95, 0.86), px=1024 if lo else 2048)
    K.mat("sack2", hd("burlap.jpg"), tile=1.0, rgb=(0.86, 0.76, 0.62), px=1024 if lo else 2048)
    K.mat("bay_a", hd("bay_a.jpg"), rgb=(0.80, 0.66, 0.56), px=1024)
    K.mat("bay_b", hd("bay_b.jpg"), rgb=(0.80, 0.66, 0.56), px=1024)
    K.mat("pole", hd("log.jpg"), tile=1.2, rgb=(0.70, 0.58, 0.47), smooth=True, px=1024)
    K.mat("end", hd("endgrain.jpg"), rgb=(0.7, 0.6, 0.5), px=512)
    K.mat("door", hd("door.jpg"), rgb=(0.78, 0.66, 0.58), px=1024)
    K.mat("rope", hd("rope.jpg"), tile=0.5, rgb=(0.72, 0.64, 0.52), px=512)
    K.mat("dark", rgb=(0.16, 0.10, 0.07))

    # ---- Hull ----------------------------------------------------------------------------------------------------------
    xs = [-L + 2 * L * i / nx for i in range(nx + 1)]
    t = K.MATS["hull"]["tile"]
    for side in (-1, 1):
        for i in range(nx):
            for j in range(nv):
                p = [hull_pt(xs[i], j / nv, side), hull_pt(xs[i + 1], j / nv, side), hull_pt(xs[i + 1], (j + 1) / nv, side), hull_pt(xs[i], (j + 1) / nv, side)]
                uv = [(q[0] / t, (q[1] + abs(q[2]) * 0.5) / t) for q in p]
                if side < 0:
                    p = p[::-1]
                    uv = uv[::-1]
                K.poly("hull", p, uv)
    x = L
    face("wale", [(x, keel(x), -0.12), (x, keel(x), 0.12), (x, sheer(x), 0.12), (x, sheer(x), -0.12)], (1, 0, 0))
    x = -L
    ring = [hull_pt(x, j / nv, -1) for j in range(nv, -1, -1)] + [hull_pt(x, j / nv, 1) for j in range(1, nv + 1)]
    face("hull", [(x - 0.01, p[1], p[2]) for p in ring], (-1, 0, 0), [(p[2] / t, p[1] / t) for p in ring])
    for side in (-1, 1):
        for i in range(nx):
            x0, x1 = xs[i], xs[i + 1]
            for frac, hh in ((0.62, 0.12), (0.82, 0.1)):
                a = Vector(hull_pt(x0, frac, side)) + Vector((0, 0, side * 0.04))
                b = Vector(hull_pt(x1, frac, side)) + Vector((0, 0, side * 0.04))
                K.beam("wale", tuple(a), tuple(b), 0.1, hh, skip=("-z", "+z") if 0 < i < nx - 1 else ())
            a = Vector(hull_pt(x0, 1.0, side)) + Vector((0, 0.04, 0))
            b = Vector(hull_pt(x1, 1.0, side)) + Vector((0, 0.04, 0))
            K.beam("wale", tuple(a), tuple(b), 0.16, 0.08, skip=("-z", "+z") if 0 < i < nx - 1 else ())
    for i in range(nx):
        x0, x1 = xs[i], xs[i + 1]
        if x0 > L - 0.4:
            continue
        w0, w1 = half_beam(x0) * 0.96, half_beam(x1) * 0.96
        y0, y1 = max(DECK, sheer(x0) - 0.35), max(DECK, sheer(x1) - 0.35)
        face("deck", [(x0, y0, -w0), (x1, y1, -w1), (x1, y1, w1), (x0, y0, w0)], (0, 1, 0))
        for side in (-1, 1):
            face("deck", [(x0, y0, side * w0), (x1, y1, side * w1), (x1, sheer(x1), side * w1), (x0, sheer(x0), side * w0)], (0, 0, -side))

    # ---- Cabin: plank walls, a door each end, a woven-mat barrel roof on bamboo hoops --------------------------------------
    CX0, CX1, HW, EAVE = -2.6, 1.6, 1.45, 2.65
    tw = K.MATS["wall"]["tile"]
    nb = 4
    bw = (CX1 - CX0) / nb
    for side in (-1, 1):
        z = side * HW
        for k in range(nb):
            xa, xb = CX0 + bw * k, CX0 + bw * (k + 1)
            m = "bay_a" if k % 2 == 0 else "bay_b"
            if side > 0:
                face(m, [(xa, DECK, z), (xb, DECK, z), (xb, EAVE, z), (xa, EAVE, z)], (0, 0, 1), [(0, 0), (1, 0), (1, 1), (0, 1)])
            else:
                face(m, [(xb, DECK, z), (xa, DECK, z), (xa, EAVE, z), (xb, EAVE, z)], (0, 0, -1), [(0, 0), (1, 0), (1, 1), (0, 1)])
        for xk in [CX0 + bw * k for k in range(nb + 1)]:
            K.box("pole", xk, (DECK + EAVE) / 2, z + side * 0.04, 0.11, EAVE - DECK, 0.11)
        K.beam("wale", (CX0 - 0.05, EAVE + 0.04, z + side * 0.05), (CX1 + 0.05, EAVE + 0.04, z + side * 0.05), 0.1, 0.12)
    for (x, out) in ((CX1, 1), (CX0, -1)):
        pts = [(x, DECK, -HW), (x, DECK, HW), (x, EAVE, HW), (x, EAVE, -HW)]
        face("wall", pts, (out, 0, 0), [(p[1] / tw, p[2] / tw) for p in pts])
        dw = 0.42
        dp = [(x + out * 0.02, DECK, -dw), (x + out * 0.02, DECK, dw), (x + out * 0.02, EAVE - 0.15, dw), (x + out * 0.02, EAVE - 0.15, -dw)]
        uv = [(0, 0), (1, 0), (1, 1), (0, 1)] if out < 0 else [(1, 0), (0, 0), (0, 1), (1, 1)]
        face("door", dp, (out, 0, 0), uv)
    hz, rise = HW + 0.2, 0.55
    nz = 6 if lo else 14

    def ry(zz):
        return EAVE + rise * math.sqrt(max(0.0, 1 - (zz / hz) ** 2))

    zs = [-hz + 2 * hz * q / nz for q in range(nz + 1)]
    acc = [0.0]
    for q in range(1, nz + 1):
        acc.append(acc[-1] + math.hypot(zs[q] - zs[q - 1], ry(zs[q]) - ry(zs[q - 1])))
    rx0, rx1 = CX0 - 0.25, CX1 + 0.25
    for q in range(nz):
        za, zb = zs[q], zs[q + 1]
        uv = [(rx0, acc[q]), (rx1, acc[q]), (rx1, acc[q + 1]), (rx0, acc[q + 1])]
        face("mat", [(rx0, ry(za), za), (rx1, ry(za), za), (rx1, ry(zb), zb), (rx0, ry(zb), zb)], (0, 1, 0), uv)
        face("mat", [(rx0, ry(za) - 0.03, za), (rx1, ry(za) - 0.03, za), (rx1, ry(zb) - 0.03, zb), (rx0, ry(zb) - 0.03, zb)], (0, -1, 0), uv)
    for xh in [rx0 + 0.05, (rx0 + rx1) / 2, rx1 - 0.05]:
        tube("pole", [(xh, ry(zz) + 0.02, zz * 1.01) for zz in zs], 0.035, 6)
    for side in (-1, 1):
        K.beam("pole", (rx0, ry(side * hz) - 0.03, side * hz), (rx1, ry(side * hz) - 0.03, side * hz), 0.08, 0.08)

    # ---- Grain sacks heaped fore and aft -----------------------------------------------------------------------------------
    nu, nvv = (6, 4) if lo else (10, 6)
    for (xa, xb) in ((2.5, 6.9), (-7.3, -3.7)):   # the boatman stands at -3.0, before the cabin
        mid = (xa + xb) / 2
        for layer in range(5):
            y = DECK + 0.02 + layer * 0.27
            reach = (xb - xa) / 2 - 0.55 * layer
            x = mid - reach
            while x <= mid + reach - 0.8:
                hb = half_beam(x) * 0.88 - 0.42 * layer
                z = -hb + 0.3
                while z <= hb - 0.28:
                    if rnd.random() < 0.94:
                        sz = (0.92 + rnd.uniform(-0.08, 0.08), 0.36 + rnd.uniform(-0.04, 0.04), 0.58 + rnd.uniform(-0.05, 0.05))
                        m = "sack" if rnd.random() < 0.6 else "sack2"
                        sack(m, (x + 0.46 + rnd.uniform(-0.08, 0.08), y + sz[1] / 2, z + rnd.uniform(-0.06, 0.06)), sz,
                             rnd.uniform(-15, 15) + (90 if rnd.random() < 0.25 else 0), nu, nvv, rnd)
                    z += 0.6
                x += 0.98

    # ---- Mast, yard, shrouds and stays --------------------------------------------------------------------------------------
    MX = 2.05
    top = 13.4
    ys = [DECK + (top - DECK) * k / 8 for k in range(9)]
    tube("pole", [(MX, y, 0) for y in ys], [0.17 - 0.08 * (y / top) for y in ys], 8 if lo else 12, 1.2, caps="end")
    tube("pole", [(MX + 0.15, 11.2, -1.6), (MX + 0.15, 11.2, 1.6)], 0.06, 8, 1.2, caps="end")
    tube("rope", [(MX, 10.6, 0), (MX, 10.95, 0)], 0.15, 10, 0.5)
    lines = [((MX, top - 0.3, 0), (L - 0.2, sheer(L - 0.2) + 0.1, 0)), ((MX, top - 0.3, 0), (-L + 0.3, sheer(-L) + 0.1, 0))]
    for side in (-1, 1):
        for dx in (-0.6, 0.6):
            lines.append(((MX, top - 1.0, 0), (MX + dx, sheer(MX + dx) + 0.05, side * half_beam(MX + dx))))
    if not lo:
        lines.append(((MX + 0.15, 11.2, -1.6), (MX - 2.5, sheer(MX - 2.5) + 0.1, -half_beam(MX - 2.5))))
        lines.append(((MX + 0.15, 11.2, 1.6), (MX - 2.5, sheer(MX - 2.5) + 0.1, half_beam(MX - 2.5))))
    for a, b in lines:
        tube("rope", [a, b], 0.018, 5, 0.3)

    # ---- Bow and stern -----------------------------------------------------------------------------------------------------
    for zc in (-0.5, 0.5):
        K.box("wale", 7.6, sheer(7.6) - 0.1, zc, 0.18, 0.5, 0.18)
    K.box("wale", -L - 0.2, 0.42, 0, 0.34, 0.84, 0.08)
    K.box("wale", -L + 0.55, sheer(-L) + 0.16, 0, 1.4, 0.08, 0.08)
    if not lo:
        for (cx, cz, r) in ((7.0, 0.0, 0.32), (-7.9, 0.6, 0.27)):
            y = max(DECK, sheer(cx) - 0.35)
            K.lathe("rope", cx, y, cz, [(r * 0.8, 0), (r, 0.05), (r, 0.12), (r * 0.4, 0.14)], seg=16)


for lo in (False, True):
    build(lo)
    K.export(os.path.join(OUT, "qm_cargo_lo.glb" if lo else "qm_cargo.glb"), key=(-0.45, 0.6, 0.75), sky=(0.88, 0.9, 0.94),
             ground=(0.5, 0.55, 0.58), key_rgb=(1.0, 0.95, 0.86), key_k=0.42, gain=1.65, ao_dist=1.0, ao_floor=0.62)
