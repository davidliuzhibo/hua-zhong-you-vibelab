"""虹桥 (画中游 0.5.1, device 2026-11-27: 「虹桥这一段是我们整张画里面最核心的部分」): the rainbow bridge of 清明上河图 built
as the real thing, a woven timber arch (编木拱), replacing the Seed3D model whose beams were soft lumps.

0.5.2 (device 2026-11-28: 「虹桥本身应该也算近景，也需要做高清晰版本」): the near-detail version. Pictures made for it
(Seedream, assets_lab/huazhong/hd_tex/final): peeled logs and their end grain, weathered deck boards, blue-grey stone
blocks, hemp rope lashings at the joints; the railings are two painted designs of a red-lacquer balustrade (carved cloud
and lotus panels), alternating bay by bay, built piece by piece (posts with lotus-bud finials, handrail, struts, panel,
lower rail, plinth) with each piece's picture cut from the design, so the carving is real detail and the gaps between
the rails stay open.

blender -b --factory-startup -P tools/hongqiao.py -- <out_dir>     (writes qm_bridge.glb and qm_bridge_lo.glb)

Model frame (as the old model and the world expect): the span along z (-13..13), the width along x, y up, the
deck's top y = 6 (1 - (z/12)^2) for |z| <= 12 (the walkers on the bridge, core/Worlds 虹桥: arc 6.0 over x -12..12,
tread exactly on it). The river is 13 m wide under it (world x -6.5..6.5 = model z): the stone abutments' faces stand
at |z| = 6.4, the approaches over the banks are stone ramps under the deck.

The arch: two systems of straight logs, side by side and alternating across the width (7 + 6 ribs):
- A: three logs, foot (|z| 6.4) -> shoulder (|z| 3.2) -> a level crown log;
- B: four logs, foot -> knee (|z| 4.7) -> apex (z 0), the two middle logs meeting at the crown;
at every joint a cross log right across the width, its ends standing out on both sides (the bridge's look in the
painting), lashed to the ribs with rope. On the arch short posts carry the stringers and the deck planks; at the four
corners tall poles with a cross-piece and a wind-bird (相风乌).
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

H = 6.0          # deck top at the crown
HALF = 12.0      # the deck reaches the ground at |z| = 12
FACE = 6.4       # abutment faces
HW = 4.0         # half the deck's width
RAIL_X = 3.92


def hd(name):
    return os.path.join(HD, name)


def deck_y(z):
    z = min(abs(z), HALF)
    return H * (1 - (z / HALF) ** 2)


def deck_slope(z):
    zz = max(-HALF, min(HALF, z))
    return -2 * H * zz / HALF ** 2


def log(m, a, b, r, seg=14, caps=True, cap_m=None):
    """A round log from a to b (game frame), radius r; end grain on the caps ([cap_m], a picture of a round log end)."""
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
    ring = []
    for i in range(seg + 1):
        an = 2 * math.pi * i / seg
        ring.append((math.cos(an), math.sin(an)))
    # Each log its own stretch of the picture (no two logs alike).
    off = (hash((round(a.x, 2), round(a.y, 2), round(a.z, 2))) % 997) / 997.0
    for i in range(seg):
        (c0, s0), (c1, s1) = ring[i], ring[i + 1]
        p0 = a + (ex * c0 + ey * s0) * r
        p1 = a + (ex * c1 + ey * s1) * r
        q0 = b + (ex * c0 + ey * s0) * r
        q1 = b + (ex * c1 + ey * s1) * r
        u0 = off + (i / seg) * 2 * math.pi * r / t
        u1 = off + ((i + 1) / seg) * 2 * math.pi * r / t
        K.poly(m, [tuple(p1), tuple(p0), tuple(q0), tuple(q1)], [(u1, off), (u0, off), (u0, off + L / t), (u1, off + L / t)])
    if caps:
        cm = cap_m or m
        for end, sign in ((a, -1), (b, 1)):
            rr = ring[:-1][::-1] if sign < 0 else ring[:-1]
            K.poly(cm, [tuple(end + (ex * c + ey * s) * r) for c, s in rr], [(0.5 + 0.44 * c, 0.5 + 0.44 * s) for c, s in rr])


def face(m, pts, out, uvs=None):
    """A flat polygon facing [out] (its points, and [uvs], reordered if needed)."""
    a, b, d = Vector(pts[0]), Vector(pts[1]), Vector(pts[-1])
    if (b - a).cross(d - a).dot(Vector(out)) < 0:
        pts = pts[::-1]
        uvs = uvs[::-1] if uvs else None
    if uvs:
        K.poly(m, [tuple(p) for p in pts], uvs)
    else:
        K.quad(m, *pts) if len(pts) == 4 else K.tri(m, *pts)


def rope(m, x0, x1, y, z, r, seg):
    """A rope lashing round a cross log (along x) from x0 to x1: the strands run round the log."""
    t = 0.44
    for i in range(seg):
        a0, a1 = 2 * math.pi * i / seg, 2 * math.pi * (i + 1) / seg
        p = [(x0, y + r * math.cos(a0), z + r * math.sin(a0)), (x0, y + r * math.cos(a1), z + r * math.sin(a1)),
             (x1, y + r * math.cos(a1), z + r * math.sin(a1)), (x1, y + r * math.cos(a0), z + r * math.sin(a0))]
        mid = Vector(((x0 + x1) / 2, y + r * math.cos((a0 + a1) / 2), z + r * math.sin((a0 + a1) / 2)))
        uv = [(i / seg * 2, 0), ((i + 1) / seg * 2, 0), ((i + 1) / seg * 2, (x1 - x0) / t), (i / seg * 2, (x1 - x0) / t)]
        face(m, p, tuple(mid - Vector((mid.x, y, z))), uv)


# ---- The railings: pieces cut from the two balustrade designs -------------------------------------------------------
# Both pictures (2368 x 1776) show one bay between two posts, at 1274 px a metre: post centres at x 160 and 2202 px,
# the plinth's foot at y 1740 px. Heights above the deck from the picture's rows.
RW, RH = 2368.0, 1776.0
P0, P1 = 160.0, 2202.0
BAY = 1.6


def _h(py):
    return (1740.0 - py) / 1274.0


def _uv(px, py):
    return (px / RW, 1.0 - py / RH)


def rail_strip(m, sgn, z0, z1, px0, px1, py0, py1, depth, n, faces=("out", "in", "top", "bot"), pz0=None, pz1=None):
    """A rail piece along the bay from z0 to z1 (following the deck), its picture the crop [px0..px1] x [py0..py1];
    [depth] across; [n] pieces along the curve. The crop's x runs from [pz0] to [pz1] (default z0..z1)."""
    pz0 = z0 if pz0 is None else pz0
    pz1 = z1 if pz1 is None else pz1
    h0, h1 = _h(py1), _h(py0)
    xo, xi = sgn * (RAIL_X + depth / 2), sgn * (RAIL_X - depth / 2)

    def U(z):
        return (px0 + (px1 - px0) * (z - pz0) / (pz1 - pz0)) / RW

    vb, vt = 1.0 - py1 / RH, 1.0 - py0 / RH
    for i in range(n):
        za = z0 + (z1 - z0) * i / n
        zb = z0 + (z1 - z0) * (i + 1) / n
        ya, yb = deck_y(za), deck_y(zb)
        ua, ub = U(za), U(zb)
        for side, x in (("out", xo), ("in", xi)):
            if side in faces:
                face(m, [(x, ya + h0, za), (x, yb + h0, zb), (x, yb + h1, zb), (x, ya + h1, za)], (x - sgn * RAIL_X, 0, 0),
                     [(ua, vb), (ub, vb), (ub, vt), (ua, vt)])
        # Top and bottom: a thin strip of the crop's top or bottom edge.
        dv = min(0.012, (vt - vb) * 0.15)
        if "top" in faces:
            face(m, [(xo, ya + h1, za), (xo, yb + h1, zb), (xi, yb + h1, zb), (xi, ya + h1, za)], (0, 1, 0),
                 [(ua, vt - dv), (ub, vt - dv), (ub, vt), (ua, vt)])
        if "bot" in faces:
            face(m, [(xo, ya + h0, za), (xo, yb + h0, zb), (xi, yb + h0, zb), (xi, ya + h0, za)], (0, -1, 0),
                 [(ua, vb), (ub, vb), (ub, vb + dv), (ua, vb + dv)])


def rail_block(m, sgn, z, px0, px1, py0, py1, depth, slope_pad=True, top=True, bottom=True):
    """An upright piece (strut, post) centred at z, as wide as its crop, its picture on all four sides."""
    w = (px1 - px0) / 1274.0
    h0, h1 = _h(py1), _h(py0)
    y = deck_y(z)
    pad = abs(deck_slope(z)) * w / 2 if slope_pad else 0.0
    ya, yb = y + h0 - pad, y + h1 + pad
    xs = (sgn * RAIL_X - depth / 2, sgn * RAIL_X + depth / 2)
    zs = (z - w / 2, z + w / 2)
    u0, u1 = px0 / RW, px1 / RW
    vb, vt = 1.0 - py1 / RH, 1.0 - py0 / RH
    uv = [(u0, vb), (u1, vb), (u1, vt), (u0, vt)]
    for x in xs:
        face(m, [(x, ya, zs[0]), (x, ya, zs[1]), (x, yb, zs[1]), (x, yb, zs[0])], (x - sgn * RAIL_X, 0, 0), uv)
    for zz in zs:
        face(m, [(xs[0], ya, zz), (xs[1], ya, zz), (xs[1], yb, zz), (xs[0], yb, zz)], (0, 0, zz - z), uv)
    dv = min(0.01, (vt - vb) * 0.2)
    if top:
        face(m, [(xs[0], yb, zs[0]), (xs[1], yb, zs[0]), (xs[1], yb, zs[1]), (xs[0], yb, zs[1])], (0, 1, 0),
             [(u0, vt - dv), (u1, vt - dv), (u1, vt), (u0, vt)])
    if bottom:
        face(m, [(xs[0], ya, zs[0]), (xs[1], ya, zs[0]), (xs[1], ya, zs[1]), (xs[0], ya, zs[1])], (0, -1, 0),
             [(u0, vb), (u1, vb), (u1, vb + dv), (u0, vb + dv)])


def finial(m, x, y0, z, seg):
    """The lotus-bud finial on a post's cap, its picture the finial in the design (front half, mirrored round the back)."""
    # (radius, height above the cap) from the picture's outline (centre x 163 px), bottom up.
    prof = [(0.074, 0.0), (0.072, 0.02), (0.084, 0.043), (0.077, 0.059), (0.085, 0.078), (0.093, 0.10), (0.093, 0.149),
            (0.082, 0.161), (0.074, 0.185), (0.05, 0.207), (0.034, 0.22), (0.021, 0.232), (0.0, 0.237)]

    def U(t, r):
        # 0 -> 1 -> 0 round the post: across the finial's outline at this height (not the white round it).
        f = abs(((t / (2 * math.pi)) % 1.0) * 2 - 1)
        hw = max(r, 0.012) * 1274 * 0.9
        return (163 + hw * (2 * f - 1)) / RW

    for j in range(len(prof) - 1):
        (r0, a0), (r1, a1) = prof[j], prof[j + 1]
        va, vb = _uv(0, 330 - a0 * 1274)[1], _uv(0, 330 - a1 * 1274)[1]
        for i in range(seg):
            t0, t1 = 2 * math.pi * i / seg, 2 * math.pi * (i + 1) / seg
            p = [(x + r0 * math.cos(t0), y0 + a0, z + r0 * math.sin(t0)), (x + r0 * math.cos(t1), y0 + a0, z + r0 * math.sin(t1)),
                 (x + r1 * math.cos(t1), y0 + a1, z + r1 * math.sin(t1)), (x + r1 * math.cos(t0), y0 + a1, z + r1 * math.sin(t0))]
            tm = (t0 + t1) / 2
            face(m, p, (math.cos(tm), 0, math.sin(tm)), [(U(t0, r0), va), (U(t1, r0), va), (U(t1, r1), vb), (U(t0, r1), vb)])


def railings(lo):
    n = 2 if lo else 4
    posts = [-11.2 + BAY * i for i in range(15)]
    for sgn in (-1, 1):
        for k in range(len(posts) - 1):
            z0, z1 = posts[k], posts[k + 1]
            m = "rail_a" if k % 2 == 0 else "rail_b"
            pz = dict(pz0=z0, pz1=z1)

            def zz(px):
                return z0 + BAY * (px - P0) / (P1 - P0)

            # Plinth (continuous along the bay, under the posts too), lower rail, panel, handrail (between the posts).
            rail_strip(m, sgn, z0, z1, P0, P1, 1615, 1740, 0.24, n, ("out", "in", "top"), **pz)
            rail_strip(m, sgn, zz(281), zz(2081), 281, 2081, 1401, 1540, 0.10, n, pz0=zz(281), pz1=zz(2081))
            rail_strip(m, sgn, zz(345), zz(2019), 345, 2019, 649, 1320, 0.06, n, pz0=zz(345), pz1=zz(2019))
            rail_strip(m, sgn, zz(281), zz(2081), 281, 2081, 432, 564, 0.12, n, pz0=zz(281), pz1=zz(2081))
            # The panel's ends (narrow edges against the posts' sides).
            for zp in (zz(345), zz(2019)):
                ya = deck_y(zp)
                xo, xi = sgn * (RAIL_X + 0.03), sgn * (RAIL_X - 0.03)
                face(m, [(xo, ya + _h(1320), zp), (xi, ya + _h(1320), zp), (xi, ya + _h(649), zp), (xo, ya + _h(649), zp)],
                     (0, 0, 1 if zp > (z0 + z1) / 2 else -1), [_uv(345, 1320), _uv(360, 1320), _uv(360, 649), _uv(345, 649)])
            # Struts above and below the panel, at the bay's middle.
            rail_block(m, sgn, zz(1183), 1131, 1235, 560, 653, 0.07, top=False, bottom=False)
            rail_block(m, sgn, zz(1178), 1120, 1236, 1316, 1405, 0.07, top=False, bottom=False)
        # Posts: body, cap, finial (the left post of design A).
        for k, z in enumerate(posts):
            y = deck_y(z)
            w = 0.19
            xs = (sgn * RAIL_X - w / 2, sgn * RAIL_X + w / 2)
            zs = (z - w / 2, z + w / 2)
            ya, yb = y - 0.14, y + _h(430)
            uv = [_uv(45, 1740), _uv(272, 1740), _uv(272, 430), _uv(45, 430)]
            for x in xs:
                face("rail_a", [(x, ya, zs[0]), (x, ya, zs[1]), (x, yb, zs[1]), (x, yb, zs[0])], (x - sgn * RAIL_X, 0, 0), uv)
            for z2 in zs:
                face("rail_a", [(xs[0], ya, z2), (xs[1], ya, z2), (xs[1], yb, z2), (xs[0], yb, z2)], (0, 0, z2 - z), uv)
            # Cap: a little wider, its picture the cap in the design.
            c = 0.235
            xs2 = (sgn * RAIL_X - c / 2, sgn * RAIL_X + c / 2)
            zs2 = (z - c / 2, z + c / 2)
            ya2, yb2 = yb, y + _h(330)
            uv2 = [_uv(38, 430), _uv(290, 430), _uv(290, 330), _uv(38, 330)]
            for x in xs2:
                face("rail_a", [(x, ya2, zs2[0]), (x, ya2, zs2[1]), (x, yb2, zs2[1]), (x, yb2, zs2[0])], (x - sgn * RAIL_X, 0, 0), uv2)
            for z2 in zs2:
                face("rail_a", [(xs2[0], ya2, z2), (xs2[1], ya2, z2), (xs2[1], yb2, z2), (xs2[0], yb2, z2)], (0, 0, z2 - z), uv2)
            face("rail_a", [(xs2[0], ya2, zs2[0]), (xs2[1], ya2, zs2[0]), (xs2[1], ya2, zs2[1]), (xs2[0], ya2, zs2[1])], (0, -1, 0),
                 [_uv(38, 430), _uv(290, 430), _uv(290, 420), _uv(38, 420)])
            face("rail_a", [(xs2[0], yb2, zs2[0]), (xs2[1], yb2, zs2[0]), (xs2[1], yb2, zs2[1]), (xs2[0], yb2, zs2[1])], (0, 1, 0),
                 [_uv(38, 345), _uv(290, 345), _uv(290, 330), _uv(38, 330)])
            finial("rail_a", sgn * RAIL_X, yb2, z, 8 if lo else 12)


def build(lo=False):
    K.reset()
    seg = 8 if lo else 14
    px = 1024 if lo else 2048
    K.mat("log", hd("log.jpg"), tile=1.4, rgb=(0.74, 0.64, 0.55), smooth=True, px=px)
    K.mat("post", hd("log.jpg"), tile=1.4, rgb=(0.66, 0.57, 0.49), px=px)
    K.mat("endgrain", hd("endgrain.jpg"), tile=1.0, rgb=(0.70, 0.60, 0.50), px=512 if lo else 1024)
    K.mat("plank", hd("deck.jpg"), tile=3.2, px=px)
    K.mat("timber", hd("deck.jpg"), tile=3.2, rgb=(0.82, 0.78, 0.72), px=px)
    K.mat("rail_a", hd("rail_a.jpg"), px=px)
    K.mat("rail_b", hd("rail_b.jpg"), px=px)
    K.mat("stone", hd("stone.jpg"), tile=2.7, rgb=(0.96, 0.93, 0.86), px=px)
    K.mat("stonetop", hd("stone.jpg"), tile=1.5, rgb=(0.96, 0.93, 0.86), px=px)
    if not lo:
        K.mat("rope", hd("rope.jpg"), tile=0.44, rgb=(0.70, 0.63, 0.52), px=1024)
    K.mat("bird", rgb=(0.12, 0.10, 0.08))

    # ---- Stone abutments and ramps (both banks) ----------------------------------------------------------------------
    for s in (-1, 1):
        # The ramp: under the deck from the face to the end, its top 0.30 under the deck's top, sides straight down.
        n = 10 if lo else 24
        zs = [FACE + (13.2 - FACE) * i / n for i in range(n + 1)]
        top = [max(deck_y(z) - 0.30, -0.02) for z in zs]
        for i in range(n):
            z0, z1 = s * zs[i], s * zs[i + 1]
            y0, y1 = top[i], top[i + 1]
            for x in (-HW - 0.12, HW + 0.12):
                face("stone", [(x, -0.6, z0), (x, -0.6, z1), (x, y1, z1), (x, y0, z0)], (1 if x > 0 else -1, 0, 0))
            face("stone", [(-HW - 0.12, y0, z0), (HW + 0.12, y0, z0), (HW + 0.12, y1, z1), (-HW - 0.12, y1, z1)], (0, 1, 0))
        # The face toward the river, with a ledge the arch feet stand on.
        yf = deck_y(FACE) - 0.30
        zf = s * FACE
        face("stone", [(-HW - 0.12, -0.6, zf), (HW + 0.12, -0.6, zf), (HW + 0.12, yf, zf), (-HW - 0.12, yf, zf)], (0, 0, -s))
        K.box("stonetop", 0, yf + 0.06, zf - s * 0.05, 2 * HW + 0.5, 0.14, 0.5)
        K.box("stonetop", 0, 0.75, zf - s * 0.45, 2 * HW + 0.3, 0.22, 0.9)
        # Wing walls along the bank (the quay's edge either side of the bridge).
        for x in (-1, 1):
            K.box("stone", x * (HW + 1.6), 0.15, zf + s * 0.6, 3.0, 1.5, 1.2)
            K.box("stonetop", x * (HW + 1.6), 0.96, zf + s * 0.6, 3.1, 0.12, 1.3)

    # ---- The woven arch -----------------------------------------------------------------------------------------------
    rA, rB, rX = 0.21, 0.20, 0.23
    yA_foot, yB_foot = 0.95, 0.80
    zA, yA = 3.2, deck_y(3.2) - 0.95          # A's shoulders and crown log
    zB, yB = 4.7, 3.15                         # B's knees
    yB_apex = deck_y(0) - 1.0                  # B's apex
    # A dense bundle, as in the painting: 7 + 6 ribs, 0.62 m apart.
    ribsA = [-3.72 + 1.24 * i for i in range(7)]
    ribsB = [-3.1 + 1.24 * i for i in range(6)]
    for x in ribsA:
        pts = [(x, yA_foot, -FACE + 0.15), (x, yA, -zA), (x, yA, zA), (x, yA_foot, FACE - 0.15)]
        for p, q in zip(pts, pts[1:]):
            log("log", p, q, rA, seg, cap_m="endgrain")
    for x in ribsB:
        pts = [(x, yB_foot, -FACE + 0.15), (x, yB, -zB), (x, yB_apex, 0.0), (x, yB, zB), (x, yB_foot, FACE - 0.15)]
        for p, q in zip(pts, pts[1:]):
            log("log", p, q, rB, seg, cap_m="endgrain")
    # Cross logs at the joints, standing out 0.55 m either side, lashed to every rib with rope.
    for (z, y) in ((-zA, yA), (zA, yA), (-zB, yB), (zB, yB), (0.0, yB_apex), (-FACE + 0.3, 1.0), (FACE - 0.3, 1.0)):
        log("log", (-HW - 0.55, y, z), (HW + 0.55, y, z), rX, seg, cap_m="endgrain")
        if not lo:
            for x in ribsA + ribsB:
                rope("rope", x - 0.13, x + 0.13, y, z, rX + 0.03, seg)

    # ---- Posts, stringers, deck -------------------------------------------------------------------------------------
    post_z = [-5.6, -4.0, -2.4, -0.8, 0.8, 2.4, 4.0, 5.6]

    def arch_top(z):
        """The arch's top (A or B, whichever is higher) at z."""
        az = abs(z)
        if az <= zA:
            ya = yA
        else:
            ya = yA + (yA_foot - yA) * (az - zA) / (FACE - zA)
        if az <= zB:
            yb = yB_apex + (yB - yB_apex) * az / zB
        else:
            yb = yB + (yB_foot - yB) * (az - zB) / (FACE - zB)
        return max(ya, yb) + rA

    for z in post_z:
        y0 = arch_top(z)
        y1 = deck_y(z) - 0.30
        if y1 - y0 > 0.12:
            for x in ribsA:
                K.box("post", x, (y0 + y1) / 2, z, 0.18, y1 - y0, 0.18)
    n = 24 if lo else 48
    for x in ribsA:
        for i in range(n):
            z0 = -FACE + 2 * FACE * i / n
            z1 = -FACE + 2 * FACE * (i + 1) / n
            K.beam("timber", (x, deck_y(z0) - 0.22, z0), (x, deck_y(z1) - 0.22, z1), 0.2, 0.16)
    # Deck planks across the width, 0.29 m wide with 1 cm gaps, following the curve from end to end.
    zc = -HALF - 0.8
    planks = []
    while zc < HALF + 0.8:
        planks.append(zc)
        zc += 0.30 / math.sqrt(1 + deck_slope(zc) ** 2)
    for z in planks:
        y = deck_y(z) if abs(z) <= HALF else 0.0
        sl = deck_slope(z) if abs(z) <= HALF else 0.0
        ang = math.atan(sl)
        ez = Vector((0, math.sin(ang), math.cos(ang)))
        ey = Vector((0, math.cos(ang), -math.sin(ang)))
        K.obox("plank", (0.0, y - 0.05, z), Vector((1, 0, 0)), ey, ez, HW, 0.05, 0.145, skip=("-y",))
    # A solid board course under the planks (from below the deck no sky shows through the planks' gaps).
    m3 = 30 if lo else 60
    for i in range(m3):
        z0 = -HALF + 2 * HALF * i / m3
        z1 = -HALF + 2 * HALF * (i + 1) / m3
        face("timber", [(-HW, deck_y(z0) - 0.11, z0), (HW, deck_y(z0) - 0.11, z0), (HW, deck_y(z1) - 0.11, z1), (-HW, deck_y(z1) - 0.11, z1)], (0, -1, 0))
        face("timber", [(-HW, deck_y(z0) - 0.11, z0), (HW, deck_y(z0) - 0.11, z0), (HW, deck_y(z1) - 0.11, z1), (-HW, deck_y(z1) - 0.11, z1)], (0, 1, 0))
    # Fascia boards along both edges of the deck (the edge seen from the side), in short pieces along the curve.
    for x in (-HW - 0.04, HW + 0.04):
        m2 = 30 if lo else 60
        for i in range(m2):
            z0 = -HALF + 2 * HALF * i / m2
            z1 = -HALF + 2 * HALF * (i + 1) / m2
            K.beam("timber", (x, deck_y(z0) - 0.17, z0), (x, deck_y(z1) - 0.17, z1), 0.08, 0.26, skip=("-z", "+z"))

    railings(lo)

    # ---- Wind-bird poles at the four corners ---------------------------------------------------------------------------
    for s in (-1, 1):
        for x in (-1, 1):
            px_, pz = x * (HW + 0.75), s * 12.4
            K.box("stonetop", px_, 0.25, pz, 0.8, 0.5, 0.8)
            log("post", (px_, 0.4, pz), (px_, 8.6, pz), 0.12, seg, cap_m="endgrain")
            K.box("post", px_, 7.9, pz, 0.08, 0.08, 1.2)
            K.box("post", px_, 7.9, pz, 1.2, 0.08, 0.08)
            K.box("bird", px_, 8.75, pz, 0.12, 0.14, 0.42)
            K.box("bird", px_, 8.82, pz + 0.18, 0.08, 0.1, 0.12)
            K.box("bird", px_, 8.7, pz - 0.2, 0.02, 0.12, 0.16)


for lo in (False, True):
    build(lo)
    name = "qm_bridge_lo.glb" if lo else "qm_bridge.glb"
    K.export(os.path.join(OUT, name), key=(-0.45, 0.75, 0.55), sky=(0.86, 0.88, 0.92), ground=(0.42, 0.40, 0.37),
             key_rgb=(1.0, 0.94, 0.84), key_k=0.42, gain=1.8, ao_dist=1.6, ao_floor=0.6)
