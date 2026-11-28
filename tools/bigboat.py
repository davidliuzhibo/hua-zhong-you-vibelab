"""虹桥's big boat (画中游 0.5.2, device 2026-11-28: 「这艘大船，在我身边通过的时候，船身还有窗户，就都非常的模糊」) built for
real, as the 虹桥 itself: the Seed3D boat had one 4K picture spread over 16 m (about 75 px a metre in the game), its windows
a smear at two metres. Here every cabin bay is a picture of its own (Seedream, assets_lab/huazhong/hd_tex/final: two
lattice-window bays and a studded door, about 1100 px a metre), the hull's varnished planks and the roof's boards tile
at 2K, the square stern carries a painted tiger board; posts and eave boards are cut from the 虹桥 railing's worn red
lacquer. The light baked in (kit3d).

blender -b --factory-startup -P tools/bigboat.py -- <out_dir>      (qm_bigboat.glb, qm_bigboat_lo.glb)

Model frame as the old model and the game expect: length along x (bow +x), 16 m; width along z, 3.7 m; y up from the
keel (0) to the cabin roof (3.47 at its raised front part, where 虹桥's mast is hinged: core/Worlds MAST_FWD 3.5 m forward
of the middle, MAST_HINGE 3.65 m up). The cabin runs from x -5 to +5 (its front 2 m raised), as the old one.
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
L = 8.0          # half length
B = 1.85         # half beam at the middle
DECK = 1.15      # deck height at the middle
TRANSOM = 0.48   # the square stern's half width, as a share of B


def hd(name):
    return os.path.join(HD, name)


def half_beam(x):
    t = min(1.0, abs(x) / L)
    if x < 0:
        # A square stern: full to the end, the transom 0.9 m either side.
        return B * (1 - (1 - TRANSOM) * t ** 3)
    return max(0.12, B * (1 - t ** 4))


def sheer(x):
    """The gunwale's height: rising toward bow and stern."""
    t = abs(x) / L
    return 1.32 + 0.95 * t ** 3.2 + (0.12 * t ** 2 if x > 0 else 0)


def keel(x):
    """The bottom's height (rocker)."""
    t = abs(x) / L
    return 0.75 * t ** 4


def face(m, pts, out, uvs=None):
    a, b, d = Vector(pts[0]), Vector(pts[1]), Vector(pts[-1])
    if (b - a).cross(d - a).dot(Vector(out)) < 0:
        pts = pts[::-1]
        uvs = uvs[::-1] if uvs else None
    if uvs:
        K.poly(m, [tuple(p) for p in pts], uvs)
    else:
        K.quad(m, *pts) if len(pts) == 4 else K.tri(m, *pts)


# The worn red lacquer of the 虹桥 railing's post (its picture, 2368 x 1776: the post body x 45..272, y 440..1600).
def lac_uv(u0, u1, v0, v1):
    """A stretch of the lacquered post: u0..u1 across it (0..1), v0..v1 along it (0..1)."""
    x0, x1 = 45 + 227 * u0, 45 + 227 * u1
    y0, y1 = 1600 - 1160 * v0, 1600 - 1160 * v1
    return [(x0 / 2368, 1 - y0 / 1776), (x1 / 2368, 1 - y0 / 1776), (x1 / 2368, 1 - y1 / 1776), (x0 / 2368, 1 - y1 / 1776)]


def lac_box(cx, cy, cz, sx, sy, sz, along="y"):
    """A lacquered timber: its grain (the post's length in the picture) along [along]."""
    h = (sx / 2, sy / 2, sz / 2)
    c = (cx, cy, cz)
    for ax in range(3):
        for sgn in (-1, 1):
            n = [0, 0, 0]
            n[ax] = sgn
            others = [k for k in range(3) if k != ax]
            # The face's two in-plane axes: the grain axis as v when it lies in the face.
            ga = {"x": 0, "y": 1, "z": 2}[along]
            if ga in others:
                va = ga
                ua = [k for k in others if k != ga][0]
            else:
                ua, va = others
            pts = []
            for (su, sv) in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                p = list(c)
                p[ax] += sgn * h[ax]
                p[ua] += su * h[ua]
                p[va] += sv * h[va]
                pts.append(tuple(p))
            span = 2 * h[va]
            face("lac", pts, tuple(n), lac_uv(0, 1, 0, min(1.0, span / 1.2)))


def wall(m, x0, x1, y0, y1, z, out, uv=((0, 0), (1, 0), (1, 1), (0, 1))):
    """A picture on the cabin's side, x0..x1, y0..y1 at z, facing [out] (+z or -z); read from outside."""
    (a, b, c, d) = uv
    if out > 0:
        face(m, [(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)], (0, 0, 1), [a, b, c, d])
    else:
        face(m, [(x1, y0, z), (x0, y0, z), (x0, y1, z), (x1, y1, z)], (0, 0, -1), [a, b, c, d])


def end_wall(m, z0, z1, y0, y1, x, out):
    """A picture on an end wall (across the width), z0..z1, facing +x or -x; read from outside."""
    uv = [(0, 0), (1, 0), (1, 1), (0, 1)]
    if out > 0:
        face(m, [(x, y0, z1), (x, y0, z0), (x, y1, z0), (x, y1, z1)], (1, 0, 0), uv)
    else:
        face(m, [(x, y0, z0), (x, y0, z1), (x, y1, z1), (x, y1, z0)], (-1, 0, 0), uv)


def build(lo):
    K.reset()
    px = 1024 if lo else 2048
    nx = 24 if lo else 48
    nv = 5 if lo else 9
    K.mat("hull", hd("hull_tile.jpg"), tile=2.4, rgb=(0.92, 0.76, 0.64), px=px)
    K.mat("wale", hd("hull_tile.jpg"), tile=1.2, rgb=(0.58, 0.40, 0.31), px=px)
    K.mat("deck", hd("deck.jpg"), tile=3.2, rgb=(0.98, 0.86, 0.72), px=1024)
    K.mat("bay_a", hd("bay_a.jpg"), px=px)
    K.mat("bay_b", hd("bay_b.jpg"), px=px)
    K.mat("door", hd("door.jpg"), px=px)
    K.mat("tiger", hd("bow.jpg"), px=px)
    K.mat("roof", hd("roof.jpg"), tile=2.0, rgb=(0.86, 0.80, 0.74), px=1024)
    K.mat("lac", hd("rail_a.jpg"), px=1024)
    K.mat("dark", rgb=(0.16, 0.10, 0.07))
    if not lo:
        K.mat("rope", hd("rope.jpg"), tile=0.5, rgb=(0.78, 0.70, 0.58), px=1024)

    # ---- Hull: a lofted surface, keel to gunwale, both sides; planks run along it ------------------------------------
    xs = [-L + 2 * L * i / nx for i in range(nx + 1)]

    def hull_pt(x, v, side):
        """v: 0 at the keel, 1 at the gunwale: a round bilge (quarter ellipse) to 0.6, then the topsides, flaring a little."""
        hb = half_beam(x)
        y0, y1 = keel(x), sheer(x)
        yb = y0 + 0.5 * (y1 - y0)
        if v <= 0.6:
            th = v / 0.6 * math.pi / 2
            return (x, y0 + (yb - y0) * (1 - math.cos(th)), side * hb * 0.94 * math.sin(th))
        f = (v - 0.6) / 0.4
        return (x, yb + (y1 - yb) * f, side * hb * (0.94 + 0.06 * f))

    t = K.MATS["hull"]["tile"]
    for side in (-1, 1):
        for i in range(nx):
            for j in range(nv):
                p = [hull_pt(xs[i], j / nv, side), hull_pt(xs[i + 1], j / nv, side), hull_pt(xs[i + 1], (j + 1) / nv, side), hull_pt(xs[i], (j + 1) / nv, side)]
                # The picture's planks run across it: along the hull (u = x), stacked up the side.
                uv = [(q[0] / t, (q[1] + abs(q[2]) * 0.5) / t) for q in p]
                if side < 0:
                    p = p[::-1]
                    uv = uv[::-1]
                K.poly("hull", p, uv)
    # The stem: a narrow board at the bow.
    x = L
    face("wale", [(x, keel(x), -0.12), (x, keel(x), 0.12), (x, sheer(x), 0.12), (x, sheer(x), -0.12)], (1, 0, 0))
    # The square stern: the transom, the tiger board on it (its picture over the whole of it).
    x = -L
    ring = [hull_pt(x, j / nv, -1) for j in range(nv, -1, -1)] + [hull_pt(x, j / nv, 1) for j in range(1, nv + 1)]
    zmax = max(abs(p[2]) for p in ring)
    ylo, yhi = keel(x), sheer(x)
    face("tiger", [(x - 0.01, p[1], p[2]) for p in ring], (-1, 0, 0),
         [(0.5 - 0.5 * p[2] / zmax, (p[1] - ylo) / (yhi - ylo)) for p in ring])
    # Wales: two heavy rubbing strakes along each side, and the gunwale's cap rail.
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
    # The transom's frame: a cap across its top and its two edges.
    K.beam("wale", (-L - 0.04, sheer(-L) + 0.04, -zmax - 0.04), (-L - 0.04, sheer(-L) + 0.04, zmax + 0.04), 0.16, 0.1)
    # Deck: planks across the inside at DECK (the ends rise with the sheer a little).
    for i in range(nx):
        x0, x1 = xs[i], xs[i + 1]
        if x0 > L - 0.4:
            continue
        w0, w1 = half_beam(x0) * 0.96, half_beam(x1) * 0.96
        y0 = max(DECK, sheer(x0) - 0.35)
        y1 = max(DECK, sheer(x1) - 0.35)
        face("deck", [(x0, y0, -w0), (x1, y1, -w1), (x1, y1, w1), (x0, y0, w0)], (0, 1, 0))
        for side in (-1, 1):
            face("deck", [(x0, y0, side * w0), (x1, y1, side * w1), (x1, sheer(x1), side * w1), (x0, sheer(x0), side * w0)], (0, 0, -side))
    # The transom's inside, above the deck.
    yd = max(DECK, sheer(-L) - 0.35)
    hbs = half_beam(-L) * 0.96
    face("deck", [(-L + 0.02, yd, -hbs), (-L + 0.02, yd, hbs), (-L + 0.02, sheer(-L), hbs), (-L + 0.02, sheer(-L), -hbs)], (1, 0, 0))

    # ---- Cabin -------------------------------------------------------------------------------------------------------
    CX0, CX1, CXR = -5.0, 5.0, 3.0      # cabin from CX0 to CX1; raised from CXR
    HW = 1.52                           # half width
    Y0 = DECK
    EAVE, EAVE_R = 3.05, 3.25
    bays = [CX0 + k for k in range(int(CX1 - CX0) + 1)]          # 1 m bays
    for side in (-1, 1):
        z = side * HW
        for k in range(len(bays) - 1):
            xa, xb = bays[k], bays[k + 1]
            m = "bay_a" if k % 2 == 0 else "bay_b"
            wall(m, xa, xb, Y0, EAVE, z, side)
            if xa >= CXR:
                # The raised front part: a frieze board above (the bay picture's painted top band).
                wall("bay_a", xa, xb, EAVE, EAVE_R, z, side, uv=((0, 0.80), (1, 0.80), (1, 1.0), (0, 1.0)))
        # Posts between the bays, standing proud.
        for xk in bays:
            top = EAVE_R if xk >= CXR else EAVE
            lac_box(xk, (Y0 + top) / 2, z + side * 0.03, 0.12, top - Y0, 0.12)
        # The sill under the bays.
        lac_box(0, Y0 + 0.05, z + side * 0.04, CX1 - CX0, 0.1, 0.1, along="x")
    # End walls: a window either side of a door.
    for (x, out) in ((CX1, 1), (CX0, -1)):
        top = EAVE_R if out > 0 else EAVE
        dw = 0.55
        end_wall("bay_b", -HW, -dw, Y0, top, x, out)
        end_wall("door", -dw, dw, Y0, top, x, out)
        end_wall("bay_b", dw, HW, Y0, top, x, out)
        for zc in (-HW, -dw, dw, HW):
            lac_box(x + out * 0.03, (Y0 + top) / 2, zc, 0.12, top - Y0, 0.12)
    # Roofs: a shallow barrel of boards across, overhanging a little; lacquered eave boards and gable boards; a ridge.
    for (xa, xb, eave, rise) in ((CX0 - 0.2, CXR + 0.05, EAVE, 0.22), (CXR - 0.05, CX1 + 0.25, EAVE_R, 0.2)):
        nz = 6 if lo else 12
        hz = HW + 0.25
        zs = [-hz + 2 * hz * q / nz for q in range(nz + 1)]

        def ry(zz):
            return eave + rise * (1 - (zz / hz) ** 2)

        tr = K.MATS["roof"]["tile"]
        for q in range(nz):
            za, zb = zs[q], zs[q + 1]
            ua, ub = (za + 3) / tr, (zb + 3) / tr
            uv = [(ua, xa / tr), (ua, xb / tr), (ub, xb / tr), (ub, xa / tr)]
            face("roof", [(xa, ry(za), za), (xb, ry(za), za), (xb, ry(zb), zb), (xa, ry(zb), zb)], (0, 1, 0), uv)
            face("roof", [(xa, ry(za) - 0.05, za), (xb, ry(za) - 0.05, za), (xb, ry(zb) - 0.05, zb), (xa, ry(zb) - 0.05, zb)], (0, -1, 0), uv)
        for side in (-1, 1):
            lac_box((xa + xb) / 2, eave - 0.04, side * hz, xb - xa, 0.12, 0.05, along="x")
        for xg in ((xa, xb) if xa < 0 else (xb,)):
            lac_box(xg, eave + rise / 2, 0, 0.05, rise + 0.1, 2 * hz, along="z")
        lac_box((xa + xb) / 2, eave + rise + 0.03, 0, xb - xa, 0.08, 0.2, along="x")
    # The mast's tabernacle on the raised roof (core/Worlds MAST_FWD 3.5, MAST_HINGE 3.65): two cheeks and a pin.
    rt = EAVE_R + 0.2
    for zc in (-0.22, 0.22):
        lac_box(3.5, rt + 0.12, zc, 0.36, 0.3, 0.08)
    K.box("dark", 3.5, rt + 0.2, 0, 0.06, 0.06, 0.5)

    # ---- Bow and stern ------------------------------------------------------------------------------------------------
    # Bitts at the bow, the rudder at the stern (stock through the transom's middle, the blade below), its tiller; rope.
    for zc in (-0.45, 0.45):
        K.box("wale", 6.6, sheer(6.6) - 0.1, zc, 0.18, 0.5, 0.18)
    K.box("wale", 6.6, sheer(6.6) + 0.08, 0, 0.1, 0.1, 1.2)
    # (The stock runs up inside a trunk behind the transom: outside only the blade under the tiger shows, and the
    # tiller over the transom's top.)
    K.box("wale", -L - 0.2, 0.42, 0, 0.34, 0.84, 0.08)                   # rudder blade, under the tiger
    K.box("wale", -L - 0.1, 0.62, 0, 0.08, 0.4, 0.08)                    # the stock's foot, into the hull
    K.box("wale", -L + 0.55, sheer(-L) + 0.16, 0, 1.4, 0.08, 0.08)       # tiller
    if not lo:
        for (cx, cz, r) in ((6.0, 0.9, 0.3), (-6.4, -0.85, 0.27)):
            y = max(DECK, sheer(cx) - 0.35)
            K.lathe("rope", cx, y, cz, [(r * 0.8, 0), (r, 0.05), (r, 0.12), (r * 0.4, 0.14)], seg=16)


for lo in (False, True):
    build(lo)
    K.export(os.path.join(OUT, "qm_bigboat_lo.glb" if lo else "qm_bigboat.glb"), key=(-0.45, 0.6, 0.75), sky=(0.88, 0.9, 0.94),
             ground=(0.5, 0.55, 0.58), key_rgb=(1.0, 0.95, 0.86), key_k=0.42, gain=1.65, ao_dist=1.0, ao_floor=0.62)
