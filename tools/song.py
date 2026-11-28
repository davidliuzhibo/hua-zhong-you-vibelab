"""画中游 0.5.0 (PICO 1127: 「进入 full space 后，模型精度和视觉效果还有较大提升空间」): the walk-in worlds' buildings
built here as Song architecture (宋式, after 《营造法式》 and the buildings in 《清明上河图》 and 《千里江山图》) instead of
Seed3D: sharp eaves, brackets, lattices and tiles at real size, tiled 2K pictures, the light baked in (tools/blender/
kit3d.py). Each replaces the model of the same name and keeps its size (assets/scenes/sizes.txt: width x, height y,
depth z; origin at the bottom middle, its front toward +z).

blender -b --factory-startup -P tools/song.py -- <out_dir> [name,...]
"""
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "tools", "blender"))
import kit3d as K  # noqa: E402

args = sys.argv[sys.argv.index("--") + 1:]
OUT = args[0]
ONLY = set(args[1].split(",")) if len(args) > 1 else None
os.makedirs(OUT, exist_ok=True)


def materials():
    K.mat("tile", K.tex("grey_roof_tiles"), tile=1.4, rgb=(0.78, 0.8, 0.84))
    K.mat("tile_green", K.tex("roof_tiles_14"), tile=1.6, rgb=(0.8, 0.95, 0.85), px=1024)
    K.mat("ridge", None, rgb=(0.055, 0.06, 0.068))
    K.mat("tileend", None, rgb=(0.07, 0.075, 0.085), smooth=True)
    K.mat("thatch", K.tex("thatch_roof_angled"), tile=2.0, rgb=(1.15, 1.08, 0.92))
    # 朱红 lacquer: a plain colour (the cherry-wood picture read brown under it).
    K.mat("red", None, rgb=(0.46, 0.032, 0.022))
    K.mat("redpost", None, rgb=(0.46, 0.032, 0.022), smooth=True)
    K.mat("wood", K.tex("weathered_planks"), tile=1.6, rgb=(1.1, 0.98, 0.84))
    K.mat("deck", K.tex("old_planks_02"), tile=1.4, rgb=(1.1, 1.0, 0.86))
    K.mat("woodpost", K.tex("rough_wood"), tile=1.4, rgb=(0.9, 0.82, 0.72), smooth=True, px=1024)
    K.mat("board", K.tex("wood_planks_grey"), tile=1.2, rgb=(1.0, 0.95, 0.88), px=1024)
    K.mat("lattice", None, rgb=(0.16, 0.025, 0.018))
    K.mat("paper", None, rgb=(0.78, 0.72, 0.58))
    K.mat("plaster", K.tex("plastered_wall_02"), tile=2.4, rgb=(1.05, 1.03, 0.98))
    K.mat("stone", K.tex("stone_brick_wall_001"), tile=2.2, rgb=(1.25, 1.2, 1.12), px=1024)
    K.mat("paving", K.tex("cobblestone_floor_01"), tile=1.8, px=1024)
    K.mat("brick", K.tex("brick_floor_02"), tile=1.4, rgb=(0.85, 0.85, 0.85), px=1024)
    K.mat("citybrick", K.tex("brick_floor_02"), tile=1.0, rgb=(0.82, 0.86, 0.92))
    K.mat("bracket", None, rgb=(0.035, 0.16, 0.12))    # 斗拱: Song 青绿 彩画 (green and blue blocks), plain colours at this size
    K.mat("bracket2", None, rgb=(0.03, 0.07, 0.19))
    K.mat("gold", None, rgb=(0.72, 0.55, 0.22))
    K.mat("cloth_blue", None, rgb=(0.20, 0.30, 0.42))
    K.mat("cloth_white", None, rgb=(0.85, 0.83, 0.76))
    K.mat("cloth_red", None, rgb=(0.62, 0.12, 0.08))
    K.mat("bamboo", None, rgb=(0.62, 0.55, 0.30))
    K.mat("dark", None, rgb=(0.08, 0.07, 0.07))
    K.mat("lantern", None, rgb=(0.75, 0.07, 0.04), self_lit=True, smooth=True)


# ---- Parts ------------------------------------------------------------------------------------------------------------
def platform(w, d, h, steps_front=True, mat="stone"):
    """台基: a stone base [w] x [d], [h] high, its top at y = h, steps up in front."""
    K.box(mat, 0, h / 2, 0, w, h, d, skip=("-y",))
    if steps_front and h > 0.12:
        n = max(1, int(round(h / 0.15)))
        sw = min(w * 0.4, 3.0)
        for i in range(n):
            y0 = h * i / n
            K.box(mat, 0, y0 + h / n / 2, d / 2 + (n - i) * 0.3 - 0.15, sw, h / n, 0.3, skip=("-y",))


def columns(xs, zs, y0, h, r, mat="redpost", base="stone"):
    for x in xs:
        for z in zs:
            K.cyl(mat, x, z, y0, y0 + h, r, seg=16, cap=False)
            K.box(base, x, y0 + 0.06, z, r * 3, 0.12, r * 3)


def frame_beams(xs, zs, y, mat="red", h=0.32, w=0.2):
    """阑额 along the front, back and the ends, on the column tops."""
    x0, x1, z0, z1 = min(xs), max(xs), min(zs), max(zs)
    for z in (z0, z1):
        K.box(mat, (x0 + x1) / 2, y - h / 2, z, x1 - x0 + w, h, w)
    for x in (x0, x1):
        K.box(mat, x, y - h / 2, (z0 + z1) / 2, w, h, z1 - z0 + w)


def brackets(xs, zs, y, size=0.5, mat="bracket"):
    """斗拱 (simplified): on each outer column top and between them, a block (栌斗) with crossed arms (栱) stepping out."""
    x0, x1, z0, z1 = min(xs), max(xs), min(zs), max(zs)
    pts = []
    for z, outz in ((z0, -1), (z1, 1)):
        for i, x in enumerate(xs):
            pts.append((x, z, 0, outz))
            if i < len(xs) - 1:
                pts.append(((x + xs[i + 1]) / 2, z, 0, outz))
    for x, outx in ((x0, -1), (x1, 1)):
        for i, z in enumerate(zs[1:-1] if len(zs) > 2 else []):
            pts.append((x, z, outx, 0))
    s = size
    for k, (x, z, ox, oz) in enumerate(pts):
        mat = "bracket" if k % 2 == 0 else "bracket2"
        K.box("red", x, y + s * 0.2, z, s * 0.5, s * 0.4, s * 0.5)                    # 栌斗
        K.box(mat, x, y + s * 0.5, z, s * 1.6 if oz else s * 0.3, s * 0.22, s * 0.3 if oz else s * 1.6)  # 泥道栱 along the wall
        K.box(mat, x + ox * s * 0.35, y + s * 0.5, z + oz * s * 0.35, s * 0.3 if oz else s * 0.9, s * 0.22, s * 0.9 if oz else s * 0.3)  # 华栱 out
        K.box(mat, x + ox * s * 0.7, y + s * 0.75, z + oz * s * 0.7, s * 1.2 if oz else s * 0.35, s * 0.22, s * 0.35 if oz else s * 1.2)  # 令栱 out


def lattice_door(x0, x1, z, y0, y1, face=1, leaves=4):
    """格扇门: [leaves] leaves between x0..x1 at [z], each a lattice upper half and a panel lower; frame red."""
    lw = (x1 - x0) / leaves
    for i in range(leaves):
        a, b = x0 + i * lw, x0 + (i + 1) * lw
        cx = (a + b) / 2
        # Frame.
        for xx in (a + 0.04, b - 0.04):
            K.box("red", xx, (y0 + y1) / 2, z, 0.07, y1 - y0, 0.08)
        for yy in (y0 + 0.04, y0 + (y1 - y0) * 0.38, y1 - 0.04):
            K.box("red", cx, yy, z, lw, 0.07, 0.08)
        # Panel below.
        K.box("red", cx, y0 + (y1 - y0) * 0.19, z, lw - 0.1, (y1 - y0) * 0.36, 0.04)
        # Lattice above: vertical bars and three cross bars, paper behind.
        ly0, ly1 = y0 + (y1 - y0) * 0.4, y1 - 0.07
        K.box("paper", cx, (ly0 + ly1) / 2, z - face * 0.02, lw - 0.1, ly1 - ly0, 0.01)
        for k in range(1, 5):
            K.box("lattice", a + 0.05 + k * (lw - 0.1) / 5, (ly0 + ly1) / 2, z + face * 0.01, 0.025, ly1 - ly0, 0.03)
        for k in range(1, 4):
            K.box("lattice", cx, ly0 + k * (ly1 - ly0) / 4, z + face * 0.01, lw - 0.1, 0.025, 0.03)


def window(x0, x1, z, y0, y1, face=1, sill="plaster"):
    """槛墙 and a 直棂窗 (vertical bars) above it."""
    sy = y0 + (y1 - y0) * 0.42
    K.box(sill, (x0 + x1) / 2, (y0 + sy) / 2, z, x1 - x0, sy - y0, 0.24)
    K.box("red", (x0 + x1) / 2, sy + 0.04, z, x1 - x0, 0.08, 0.26)
    K.box("red", (x0 + x1) / 2, y1 - 0.05, z, x1 - x0, 0.1, 0.18)
    K.box("paper", (x0 + x1) / 2, (sy + y1) / 2, z - face * 0.04, x1 - x0, y1 - sy, 0.01)
    n = max(3, int((x1 - x0) / 0.12))
    for k in range(1, n):
        K.box("lattice", x0 + k * (x1 - x0) / n, (sy + y1) / 2, z + face * 0.02, 0.035, y1 - sy - 0.1, 0.05)


def wall(x0, x1, z, y0, y1, mat="plaster", axis="x", t=0.24):
    if axis == "x":
        K.box(mat, (x0 + x1) / 2, (y0 + y1) / 2, z, x1 - x0, y1 - y0, t)
    else:
        K.box(mat, z, (y0 + y1) / 2, (x0 + x1) / 2, t, y1 - y0, x1 - x0)


def gable_roof(W, D, ye, yr, oz, ox, mat="tile", lift=0.14, under="wood", nu=24, nv=10, ridge=True, chiwen=True, barge=True, k=1.45, ends=True):
    """悬山 roof: ridge along x. Eaves at z = ±(D/2 + oz), height ye; ridge at yr; gables overhang ox past ±W/2.
    Each slope is concave (举折: shallow at the eave, steep at the ridge), its eave line lifting [lift] at the corners;
    an underside of boards, fascia along the eaves, barge boards on the gables, the ridge with 鸱吻 ornaments."""
    xe = W / 2 + ox
    ze = D / 2 + oz
    th = 0.16

    def P(s, u, v, off=0.0):
        x = -xe + 2 * xe * u
        z = s * ze * (1 - v)
        t = abs(x) / xe
        y = ye + (yr - ye) * (v ** k) + lift * (t ** 4) * (1 - v) ** 2 - off
        return (x, y, z)
    for s in (1, -1):
        K.grid(mat, lambda u, v, s=s: P(s, u, v), nu, nv, uvscale=(2 * xe / K.MATS[mat]["tile"], math.hypot(ze, yr - ye) / K.MATS[mat]["tile"]), flip=(s < 0))
        K.grid(under, lambda u, v, s=s: P(s, u, v, th), nu, nv, uvscale=(2 * xe / 1.6, ze / 1.6), flip=(s > 0))
        # Fascia along the eave (front face) and the barge edges.
        for i in range(nu):
            a, b = P(s, i / nu, 0), P(s, (i + 1) / nu, 0)
            a2, b2 = P(s, i / nu, 0, th), P(s, (i + 1) / nu, 0, th)
            if s > 0:
                K.quad("red" if under == "wood" else under, a2, b2, b, a)
            else:
                K.quad("red" if under == "wood" else under, b2, a2, a, b)
        if barge:
            for e in (0, 1):
                for j in range(nv):
                    a, b = P(s, e, j / nv), P(s, e, (j + 1) / nv)
                    a2, b2 = P(s, e, j / nv, th), P(s, e, (j + 1) / nv, th)
                    pts = [a2, b2, b, a] if (e == 1) == (s > 0) else [b2, a2, a, b]
                    K.quad("wood", *pts)
    if ends and mat != "thatch":
        # 瓦当 (tile ends) along each eave and the rafter ends (椽头) under them.
        for s in (1, -1):
            n = int(2 * xe / 0.23)
            for i in range(n):
                u = (i + 0.5) / n
                x, y, z = P(s, u, 0)
                K.cyl("tileend", x, z + s * 0.02, y - 0.02, y + 0.06, 0.07, seg=8, cap=True)
            for i in range(int(2 * xe / 0.32)):
                u = (i + 0.5) / int(2 * xe / 0.32)
                x, y, z = P(s, u, 0, 0.1)
                K.box("woodpost", x, y - 0.06, z - s * 0.02, 0.1, 0.1, 0.14)
    if ridge:
        K.box("ridge", 0, yr + 0.18, 0, 2 * xe - 0.2, 0.42, 0.34)
        K.box("ridge", 0, yr + 0.42, 0, 2 * xe - 0.4, 0.1, 0.24)
        if chiwen:
            for sx in (-1, 1):
                x = sx * (xe - 0.3)
                K.box("ridge", x, yr + 0.55, 0, 0.36, 0.9, 0.32)
                K.box("ridge", x - sx * 0.18, yr + 0.95, 0, 0.3, 0.35, 0.26)
                K.box("ridge", x + sx * 0.08, yr + 1.05, 0, 0.16, 0.3, 0.2)


def gable_ends(W, D, yw, yr, ze, mat="plaster"):
    """The gable walls' triangles under a 悬山 roof, from the wall tops [yw] to the ridge."""
    for sx in (-1, 1):
        x = sx * W / 2
        a, b, c = (x, yw, D / 2), (x, yw, -D / 2), (x, yr - 0.2, 0)
        if sx > 0:
            K.tri(mat, a, b, c)
        else:
            K.tri(mat, b, a, c)


def hip_roof(W, D, ye, yr, o, mat="tile", lift=0.2, apex_w=0.0, n=10, k=1.5, finial=True):
    """A hip (四坡) or pyramid (攒尖, apex_w 0) roof over W x D with overhang [o]: four concave faces with lifted corners."""
    xe, ze = W / 2 + o, D / 2 + o
    ax = apex_w / 2

    def edge(side, u):   # eave point along a side, u 0..1
        if side == 0:
            return (-xe + 2 * xe * u, ze)
        if side == 1:
            return (xe, ze - 2 * ze * u)
        if side == 2:
            return (xe - 2 * xe * u, -ze)
        return (-xe, -ze + 2 * ze * u)

    def top(side, u):
        if side in (0, 2):
            x = (-ax + 2 * ax * u) * (1 if side == 0 else -1)
            return (x, 0.0)
        return (ax if side == 1 else -ax, 0.0)

    for side in range(4):
        def F(u, v, side=side):
            ex, ez = edge(side, u)
            tx, tz = top(side, u)
            x = ex + (tx - ex) * v
            z = ez + (tz - ez) * v
            corner = abs(2 * u - 1) ** 4
            y = ye + (yr - ye) * (v ** k) + lift * corner * (1 - v) ** 2
            return (x, y, z)
        K.grid(mat, F, 12, n, uvscale=(2 * max(xe, ze) / K.MATS[mat]["tile"], math.hypot(max(xe, ze), yr - ye) / K.MATS[mat]["tile"]))
        K.grid("wood", lambda u, v, F=F: (F(u, v)[0], F(u, v)[1] - 0.15, F(u, v)[2]), 12, n, uvscale=(3, 2), flip=True)
        for i in range(12):
            a, b = F(i / 12, 0), F((i + 1) / 12, 0)
            K.quad("red", (a[0], a[1] - 0.15, a[2]), (b[0], b[1] - 0.15, b[2]), b, a)
    # Hip ridges along the corners.
    for cx, cz in ((-xe, ze), (xe, ze), (xe, -ze), (-xe, -ze)):
        ex, ez = cx, cz
        tx, tz = (ax if cx > 0 else -ax), 0.0
        prev = None
        for j in range(n + 1):
            v = j / n
            p = (ex + (tx - ex) * v, ye + (yr - ye) * (v ** k) + lift * (1 - v) ** 2 + 0.08, ez + (tz - ez) * v)
            if prev:
                K.beam("ridge", prev, p, 0.18, 0.18)
            prev = p
    if apex_w > 0:
        K.box("ridge", 0, yr + 0.15, 0, apex_w + 0.3, 0.36, 0.32)
    if finial:
        K.lathe("ridge", 0, yr, 0, [(0.35, 0), (0.3, 0.25), (0.18, 0.45), (0.26, 0.7), (0.0, 1.1)], seg=12)


def skirt_roof(W, D, y0, y1, o, mat="tile", lift=0.2):
    """A lower eave (腰檐 / 副阶) all round: from the wall at y1 out [o] and down to y0."""
    for side in range(4):
        if side in (0, 2):
            L, s, along = W / 2, (1 if side == 0 else -1), "x"
            inner = D / 2
        else:
            L, s, along = D / 2, (1 if side == 1 else -1), "z"
            inner = W / 2

        def F(u, v, L=L, s=s, along=along, inner=inner):
            # u along the side (corners swept out), v from the eave (0) to the wall (1)
            off = inner + o * (1 - v)
            reach = L + o * (1 - v)
            a = -reach + 2 * reach * u
            y = y0 + (y1 - y0) * (v ** 1.4) + lift * (abs(2 * u - 1) ** 4) * (1 - v) ** 2
            return (a, y, s * off) if along == "x" else (s * off, y, -a * s)
        K.grid(mat, F, 14, 4, uvscale=(2 * L / K.MATS[mat]["tile"], o / K.MATS[mat]["tile"] * 1.3), flip=(side in (2,)) )
        K.grid("wood", lambda u, v, F=F: (F(u, v)[0], F(u, v)[1] - 0.12, F(u, v)[2]), 14, 4, uvscale=(3, 1), flip=(side not in (2,)))


def railing(x0, x1, z, y, h=0.9, axis="x", mat="red", step=0.3):
    """勾栏: posts, a top rail and a mid rail, along x (at z) or along z (at x = z arg)."""
    L = x1 - x0
    n = max(2, int(L / step))
    for i in range(n + 1):
        t = x0 + L * i / n
        if axis == "x":
            K.box(mat, t, y + h / 2, z, 0.07, h, 0.07)
        else:
            K.box(mat, z, y + h / 2, t, 0.07, h, 0.07)
    for hh, s in ((h, 0.09), (h * 0.5, 0.06), (0.06, 0.08)):
        if axis == "x":
            K.box(mat, (x0 + x1) / 2, y + hh, z, L, s, 0.08)
        else:
            K.box(mat, z, y + hh, (x0 + x1) / 2, 0.08, s, L)


def plaque(x, y, z, w, h, face=1):
    K.box("dark", x, y, z, w, h, 0.08)
    K.box("gold", x, y, z + face * 0.045, w - 0.08, h - 0.08, 0.01)
    K.box("dark", x, y, z + face * 0.05, w - 0.16, h - 0.16, 0.01)


# ---- Buildings ----------------------------------------------------------------------------------------------------------
def hall(W, D, wall_h, bays_front, kinds_front, roof="gable", ye=None, yr=None, oz=1.1, ox=0.6, plinth=0.45, post="redpost",
         grand=False, back="wall", sides="wall", roof_mat="tile"):
    """A one-storey hall W x D (column lines), [wall_h] up to the beams. [kinds_front]: per bay 'door', 'window', 'open',
    'wall'. Returns the y of the roof's eave."""
    platform(W + 1.0, D + 1.0, plinth)
    xs = [-W / 2 + W * i / bays_front for i in range(bays_front + 1)]
    zs = [-D / 2, D / 2] if D < 7 else [-D / 2, 0.0, D / 2]
    y0 = plinth
    columns(xs, [-D / 2, D / 2], y0, wall_h, 0.17 if grand else 0.14, mat=post)
    if len(zs) > 2:
        columns([xs[0], xs[-1]], [0.0], y0, wall_h, 0.15, mat=post)
    top = y0 + wall_h
    frame_beams(xs, [-D / 2, D / 2], top, mat="red" if post == "redpost" else "wood")
    if grand:
        brackets(xs, zs, top, size=0.55)
        top += 0.75
    # Front bays.
    for i, kind in enumerate(kinds_front):
        a, b = xs[i] + 0.15, xs[i + 1] - 0.15
        z = D / 2
        if kind == "door":
            lattice_door(a, b, z, y0, top - (0.75 if grand else 0) - 0.33)
        elif kind == "window":
            window(a, b, z, y0, top - (0.75 if grand else 0) - 0.33)
        elif kind == "wall":
            wall(a - 0.15, b + 0.15, z - 0.1, y0, top - (0.75 if grand else 0) - 0.32)
        elif kind == "open":
            # A shop front: a counter, the back wall's shelves behind (dark inside).
            K.box("board", (a + b) / 2, y0 + 0.45, z - 0.2, b - a, 0.9, 0.5)
            K.box("wood", (a + b) / 2, y0 + 0.92, z - 0.2, b - a + 0.1, 0.06, 0.6)
    # Back and sides.
    hw = top - (0.75 if grand else 0) - 0.32
    wall(-W / 2, W / 2, -D / 2, y0, hw, axis="x")
    for sx in (-1, 1):
        wall(-D / 2, D / 2, sx * W / 2, y0, hw, axis="z")
    # Inside darkness for open fronts: a dark backdrop a little inside.
    if "open" in kinds_front:
        K.box("dark", 0, (y0 + hw) / 2, -D / 2 + 0.2, W - 0.4, hw - y0, 0.05)
    ye = top + 0.15 if ye is None else ye
    yr = ye + D * 0.42 if yr is None else yr
    if roof == "gable":
        gable_roof(W, D, ye, yr, oz, ox, mat=roof_mat)
        gable_ends(W, D, hw, yr, D / 2)
    elif roof == "hip":
        hip_roof(W, D, ye, yr, oz, mat=roof_mat, apex_w=W * 0.5, finial=False)
    return ye, yr


def qm_shop():
    """汴京 street shop (sizes.txt 9.71 x 5.99 x 8.71): three bays open to the street with a counter, a 悬山 roof with
    deep eaves, a cloth awning on poles over the front, a signboard."""
    W, D = 7.2, 5.6
    ye, yr = hall(W, D, 2.9, 3, ["open", "open", "door"], oz=1.2, ox=0.8, plinth=0.3, post="woodpost")
    # Awning.
    for sx in (-1, 1):
        K.cyl("bamboo", sx * 3.0, D / 2 + 2.4, 0.3, 2.65, 0.05, seg=8)
    K.grid("cloth_blue", lambda u, v: (-3.2 + 6.4 * u, 2.95 - 0.35 * v - 0.12 * math.sin(math.pi * u), D / 2 + 0.6 + 1.9 * v), 10, 3)
    K.grid("cloth_blue", lambda u, v: (-3.2 + 6.4 * u, 2.95 - 0.35 * v - 0.12 * math.sin(math.pi * u) - 0.02, D / 2 + 0.6 + 1.9 * v), 10, 3, flip=True)
    plaque(0, ye - 0.55, D / 2 + 0.25, 2.2, 0.55)


def qm_house():
    """A town house (7.77 x 6.00 x 6.42): three bays, door in the middle, windows either side, plastered walls."""
    hall(5.6, 4.4, 2.7, 3, ["window", "door", "window"], oz=0.9, ox=0.7, plinth=0.35, post="woodpost")


def upper_storey(W, D, y0, h, kinds="window", balcony=0.7, post="redpost"):
    """A second storey on a 平座: floor at y0, a balcony [balcony] wide with a railing all round, columns, lattice
    windows on all four sides. Returns the top of its walls."""
    K.box("board", 0, y0 - 0.08, 0, W + 2 * balcony, 0.16, D + 2 * balcony)
    K.box("red", 0, y0 - 0.25, 0, W + 2 * balcony, 0.2, D + 2 * balcony, skip=("+y",))
    xe, ze = W / 2 + balcony - 0.05, D / 2 + balcony - 0.05
    railing(-xe, xe, ze, y0, 0.85)
    railing(-xe, xe, -ze, y0, 0.85)
    railing(-ze, ze, xe, y0, 0.85, axis="z")
    railing(-ze, ze, -xe, y0, 0.85, axis="z")
    bays = max(3, int(round(W / 2.2)))
    xs = [-W / 2 + W * i / bays for i in range(bays + 1)]
    columns(xs, [-D / 2, D / 2], y0, h, 0.12, mat=post, base="board")
    frame_beams(xs, [-D / 2, D / 2], y0 + h)
    for i in range(bays):
        a, b = xs[i] + 0.12, xs[i + 1] - 0.12
        for z, f in ((D / 2, 1), (-D / 2, -1)):
            if kinds == "door" and i == bays // 2 and f > 0:
                lattice_door(a, b, z, y0, y0 + h - 0.3, face=f)
            else:
                window(a, b, z, y0, y0 + h - 0.3, face=f)
    for sx in (-1, 1):
        wall(-D / 2, D / 2, sx * W / 2, y0, y0 + h - 0.3, axis="z")
    return y0 + h


def wine_flag(x, z, h=7.5, cloth="cloth_white"):
    """酒旗: a tall pole with a long banner, its edge red."""
    K.cyl("woodpost", x, z, 0, h, 0.08, seg=8)
    K.box("woodpost", x + 0.5, h - 0.3, z, 1.1, 0.08, 0.08)
    K.grid(cloth, lambda u, v: (x + 0.08 + 0.9 * u, h - 0.35 - 2.6 * v, z + 0.08 * math.sin(math.pi * v * 2 + u)), 4, 8)
    K.grid(cloth, lambda u, v: (x + 0.08 + 0.9 * u, h - 0.35 - 2.6 * v, z + 0.08 * math.sin(math.pi * v * 2 + u) - 0.01), 4, 8, flip=True)
    K.box("cloth_red", x + 0.53, h - 0.35 - 2.6, z, 0.9, 0.18, 0.04)


def qm_tavern():
    """正店 (14.32 x 10.99 x 9.70): two storeys; the ground floor open to the street with lattice doors, a lower eave
    round it, a balcony and windows above, a hip roof with brackets; a wine flag on its pole."""
    W, D = 10.4, 6.6
    plinth = 0.45
    platform(W + 1.2, D + 1.2, plinth)
    xs = [-W / 2 + W * i / 5 for i in range(6)]
    columns(xs, [-D / 2, D / 2], plinth, 3.3, 0.17)
    frame_beams(xs, [-D / 2, D / 2], plinth + 3.3)
    brackets(xs, [-D / 2, D / 2], plinth + 3.3, size=0.45)
    for i, kind in enumerate(["door", "door", "open", "door", "door"]):
        a, b = xs[i] + 0.15, xs[i + 1] - 0.15
        if kind == "door":
            lattice_door(a, b, D / 2, plinth, plinth + 3.0)
        else:
            K.box("board", (a + b) / 2, plinth + 0.45, D / 2 - 0.2, b - a, 0.9, 0.5)
    wall(-W / 2, W / 2, -D / 2, plinth, plinth + 3.0)
    for sx in (-1, 1):
        wall(-D / 2, D / 2, sx * W / 2, plinth, plinth + 3.0, axis="z")
    K.box("dark", 0, plinth + 1.5, -D / 2 + 0.2, W - 0.4, 3.0, 0.05)
    skirt_roof(W, D, plinth + 3.55, plinth + 4.35, 1.5)
    top = upper_storey(W - 0.6, D - 0.8, plinth + 4.55, 2.6)
    brackets([-(W - 0.6) / 2 + (W - 0.6) * i / 4 for i in range(5)], [-(D - 0.8) / 2, (D - 0.8) / 2], top, size=0.45)
    hip_roof(W - 0.6, D - 0.8, top + 0.75, top + 3.0, 1.6, apex_w=(W - 0.6) * 0.45, finial=False)
    plaque(0, plinth + 3.0 + 0.95, D / 2 + 0.75, 2.8, 0.7)
    wine_flag(W / 2 + 1.0, D / 2 + 1.3)
    # Red lanterns along the lower eave.
    for x in (-4.2, -2.1, 2.1, 4.2):
        K.lathe("lantern", x, plinth + 2.65, D / 2 + 1.0, [(0.12, 0), (0.24, 0.08), (0.28, 0.25), (0.24, 0.45), (0.12, 0.52)], seg=14)
        K.box("ridge", x, plinth + 3.35, D / 2 + 1.0, 0.03, 0.3, 0.03)


def qm_shop2():
    """A two-storey shop on the river street (8 m): open counters below, windows and a balcony above, a gable roof."""
    W, D = 6.6, 5.0
    plinth = 0.3
    platform(W + 1.0, D + 1.0, plinth)
    xs = [-W / 2 + W * i / 3 for i in range(4)]
    columns(xs, [-D / 2, D / 2], plinth, 2.9, 0.14, mat="woodpost")
    frame_beams(xs, [-D / 2, D / 2], plinth + 2.9, mat="wood")
    for i in range(3):
        a, b = xs[i] + 0.15, xs[i + 1] - 0.15
        K.box("board", (a + b) / 2, plinth + 0.45, D / 2 - 0.2, b - a, 0.9, 0.5)
        K.box("wood", (a + b) / 2, plinth + 0.92, D / 2 - 0.2, b - a + 0.1, 0.06, 0.6)
    wall(-W / 2, W / 2, -D / 2, plinth, plinth + 2.6)
    for sx in (-1, 1):
        wall(-D / 2, D / 2, sx * W / 2, plinth, plinth + 2.6, axis="z")
    K.box("dark", 0, plinth + 1.3, -D / 2 + 0.2, W - 0.4, 2.6, 0.05)
    skirt_roof(W, D, plinth + 3.0, plinth + 3.6, 1.1)
    top = upper_storey(W, D - 0.6, plinth + 3.8, 2.4, balcony=0.5, post="woodpost")
    gable_roof(W, D - 0.6, top + 0.15, top + 2.0, 1.0, 0.7)
    gable_ends(W, D - 0.6, top - 0.3, top + 2.0, (D - 0.6) / 2)
    plaque(0, plinth + 3.25, D / 2 + 0.7, 2.0, 0.45)


def qm_warehouse():
    """A storehouse on the bank (13.49 x 6.99 x 10.86): a long plain hall, plank doors, high small windows."""
    W, D = 11.2, 8.0
    ye, yr = hall(W, D, 3.6, 4, ["wall", "door", "door", "wall"], oz=1.2, ox=0.8, plinth=0.5, post="woodpost")
    # Plank doors instead of the lattice ones: boards over the two middle bays.
    for x in (-1.4, 1.4):
        K.box("board", x, 0.5 + 1.45, 4.0 + 0.06, 2.6, 2.9, 0.06)
        for k in range(3):
            K.box("dark", x - 0.9 + k * 0.9, 0.5 + 1.45, 4.0 + 0.1, 0.05, 2.9, 0.02)


def thatch_house(W, D, wall_h, plinth=0.25, door=True):
    """A farmhouse: earthen walls on a low base, a wooden door, small windows, a thick thatched gable roof."""
    platform(W + 0.6, D + 0.6, plinth, steps_front=False, mat="brick")
    K.box("plaster", 0, plinth + wall_h / 2, 0, W, wall_h, D)
    if door:
        K.box("board", 0, plinth + 1.0, D / 2 + 0.02, 1.0, 2.0, 0.06)
        K.box("woodpost", 0, plinth + 2.08, D / 2 + 0.05, 1.3, 0.14, 0.12)
    for x in (-W / 3, W / 3):
        K.box("dark", x, plinth + 1.5, D / 2 + 0.01, 0.8, 0.6, 0.03)
        for k in range(4):
            K.box("woodpost", x - 0.3 + k * 0.2, plinth + 1.5, D / 2 + 0.04, 0.04, 0.62, 0.04)
    gable_roof(W, D, plinth + wall_h + 0.05, plinth + wall_h + D * 0.55, 0.9, 0.5, mat="thatch", under="woodpost", ridge=False, chiwen=False, ends=False, lift=0.0, k=1.15)
    gable_ends(W, D, plinth + wall_h, plinth + wall_h + D * 0.55, D / 2)
    K.box("thatch", 0, plinth + wall_h + D * 0.55 + 0.1, 0, W + 1.0, 0.3, 0.5)


def fence(pts, h=1.1, mat="bamboo"):
    """A wattle fence through [pts] (x, z)."""
    for (x0, z0), (x1, z1) in zip(pts, pts[1:]):
        L = math.hypot(x1 - x0, z1 - z0)
        n = max(2, int(L / 0.25))
        for i in range(n + 1):
            t = i / n
            K.cyl(mat, x0 + (x1 - x0) * t, z0 + (z1 - z0) * t, 0, h + (0.08 if i % 2 else 0), 0.035, seg=6)
        for y in (0.35, 0.8):
            K.beam(mat, (x0, y, z0), (x1, y, z1), 0.04, 0.04)


def qm_farm():
    """郊野 farmhouse (8.5 x 5.0 x 11.45): a thatched house at the back, a fenced yard in front."""
    W, D = 6.6, 4.4
    # The house at the back of the plot, the yard toward +z.
    K_off = -3.2
    _shift(lambda: thatch_house(W, D, 2.4), 0, K_off)
    fence([(-4.0, -0.4), (-4.0, 5.6), (-0.9, 5.6)])
    fence([(0.9, 5.6), (4.0, 5.6), (4.0, -0.4)])
    for x in (-0.9, 0.9):
        K.cyl("woodpost", x, 5.6, 0, 1.6, 0.07, seg=8)


def qm_teashed():
    """A roadside tea shed (3.96 x 3.5 x 5.59): four posts, a thatched roof, a table, benches, a stove."""
    for x in (-1.5, 1.5):
        for z in (-2.0, 2.0):
            K.cyl("woodpost", x, z, 0, 2.4, 0.07, seg=8)
    gable_roof(3.0, 4.0, 2.4, 3.3, 0.5, 0.3, mat="thatch", under="woodpost", ridge=False, chiwen=False, ends=False, lift=0.0, k=1.1, barge=False)
    K.box("board", 0, 0.75, 0.4, 1.4, 0.06, 0.8)
    for x in (-0.6, 0.6):
        for z in (0.1, 0.7):
            K.box("woodpost", x, 0.37, z, 0.06, 0.74, 0.06)
    for z in (-0.3, 1.1):
        K.box("board", 0, 0.42, z, 1.5, 0.05, 0.3)
    K.lathe("dark", -1.0, 0, -1.3, [(0.35, 0), (0.38, 0.4), (0.3, 0.7), (0.32, 0.75)], seg=12)


def qm_stall():
    """A street stall (3.2): a big cloth parasol over a low table of goods."""
    K.cyl("bamboo", 0, 0, 0, 2.6, 0.04, seg=8)
    K.lathe("cloth_white", 0, 2.2, 0, [(1.55, 0.0), (1.3, 0.25), (0.8, 0.55), (0.05, 0.75)], seg=16)
    K.lathe("cloth_white", 0, 2.18, 0, [(0.05, 0.75), (0.8, 0.55), (1.3, 0.25), (1.55, 0.0)], seg=16)
    K.box("board", 0, 0.62, 0.5, 1.4, 0.05, 0.7)
    for x in (-0.6, 0.6):
        for z in (0.2, 0.8):
            K.box("woodpost", x, 0.3, z, 0.05, 0.6, 0.05)
    for i in range(5):
        K.lathe("bamboo", -0.5 + i * 0.25, 0.65, 0.5, [(0.08, 0), (0.1, 0.05), (0.06, 0.12)], seg=8)


def qm_cailou():
    """彩楼欢门 (9.10 x 7.00 x 3.14): the festive gateway before a tavern: posts and bars of lashed poles hung with
    swags of red, blue and white cloth, little flags on top."""
    for x in (-4.2, -1.6, 1.6, 4.2):
        for z in (-1.2, 1.2):
            K.cyl("woodpost", x, z, 0, 6.2 if abs(x) < 2 else 5.4, 0.08, seg=8)
    for y, x0 in ((2.6, 4.2), (4.0, 4.2), (5.2, 4.2), (6.0, 1.6)):
        for z in (-1.2, 1.2):
            K.beam("woodpost", (-x0, y, z), (x0, y, z), 0.09, 0.09)
        for x in ((-x0, -1.6, 1.6, x0) if x0 > 2 else (-1.6, 1.6)):
            K.beam("woodpost", (x, y, -1.2), (x, y, 1.2), 0.08, 0.08)
    for y, cl in ((4.0, "cloth_red"), (5.2, "cloth_blue"), (2.6, "cloth_white")):
        for (a, b) in ((-4.2, -1.6), (-1.6, 1.6), (1.6, 4.2)):
            K.grid(cl, lambda u, v, a=a, b=b, y=y: (a + (b - a) * u, y - 0.08 - 0.55 * math.sin(math.pi * u) - 0.25 * v, 1.25), 10, 2)
            K.grid(cl, lambda u, v, a=a, b=b, y=y: (a + (b - a) * u, y - 0.08 - 0.55 * math.sin(math.pi * u) - 0.25 * v, 1.24), 10, 2, flip=True)
    for x in (-1.6, 1.6, -4.2, 4.2):
        top = 6.2 if abs(x) < 2 else 5.4
        K.grid("cloth_red", lambda u, v, x=x, top=top: (x + 0.05 + 0.5 * u, top - 0.05 - 0.4 * v + 0.15 * u * v, 1.2), 2, 2)
        K.grid("cloth_red", lambda u, v, x=x, top=top: (x + 0.05 + 0.5 * u, top - 0.05 - 0.4 * v + 0.15 * u * v, 1.19), 2, 2, flip=True)


def qm_gate():
    """城门 (29.11 x 16.00 x 15.26): a brick-faced platform with an arched passage, walls running off both sides with
    crenellations, and on top a gate tower with brackets, a lower eave and a hip roof."""
    PW, PD, PH = 18.0, 13.0, 7.6
    aw, ah = 4.6, 5.4
    # The platform round the passage (left and right blocks, the block over the arch), slightly battered.
    for sx in (-1, 1):
        K.box("citybrick", sx * (PW / 2 + aw / 2) / 2, PH / 2, 0, (PW - aw) / 2, PH, PD)
    K.box("citybrick", 0, (ah + 1.2 + PH) / 2, 0, aw, PH - ah - 1.2, PD)
    # The arch: half a cylinder of stone over the passage.
    r = aw / 2
    for i in range(12):
        a0, a1 = math.pi * i / 12, math.pi * (i + 1) / 12
        p0 = (r * math.cos(a0), ah - r + r * math.sin(a0) + 1.2, 0)
        p1 = (r * math.cos(a1), ah - r + r * math.sin(a1) + 1.2, 0)
        q = [(p1[0], p1[1], PD / 2), (p0[0], p0[1], PD / 2), (p0[0], p0[1], -PD / 2), (p1[0], p1[1], -PD / 2)]
        K.quad("citybrick", *q)
        K.quad("citybrick", *q[::-1])
        for zz, f in ((PD / 2, 1), (-PD / 2, -1)):
            top_y = ah + 1.2
            pts = [(p0[0], p0[1], zz), (p1[0], p1[1], zz), (p1[0], top_y, zz), (p0[0], top_y, zz)]
            K.quad("citybrick", *pts)
            K.quad("citybrick", *pts[::-1])
    # Crenellated walls to both sides.
    for sx in (-1, 1):
        K.box("citybrick", sx * (PW / 2 + 2.6), PH * 0.8 / 2, -1.5, 5.4, PH * 0.8, 8.0)
        for k in range(5):
            K.box("citybrick", sx * (PW / 2 + 0.6 + k * 1.1), PH * 0.8 + 0.4, 2.4, 0.6, 0.8, 0.5)
    for k in range(int(PW / 1.1)):
        K.box("citybrick", -PW / 2 + 0.55 + k * 1.1, PH + 0.4, PD / 2 - 0.25, 0.6, 0.8, 0.5)
    # A stone band at the foot of the platform and round the arch's mouth.
    K.box("stone", 0, 0.5, PD / 2 + 0.05, PW, 1.0, 0.12)
    # The tower.
    TW, TD = 12.0, 7.0
    K.box("brick", 0, PH + 0.15, 0, TW + 2.0, 0.3, TD + 2.0)
    xs = [-TW / 2 + TW * i / 5 for i in range(6)]
    columns(xs, [-TD / 2, TD / 2], PH + 0.3, 3.2, 0.2)
    frame_beams(xs, [-TD / 2, TD / 2], PH + 3.5)
    brackets(xs, [-TD / 2, TD / 2], PH + 3.5, size=0.6)
    for i in range(5):
        a, b = xs[i] + 0.15, xs[i + 1] - 0.15
        (lattice_door if i in (1, 2, 3) else window)(a, b, TD / 2, PH + 0.3, PH + 3.15)
    wall(-TW / 2, TW / 2, -TD / 2, PH + 0.3, PH + 3.15)
    for sx in (-1, 1):
        wall(-TD / 2, TD / 2, sx * TW / 2, PH + 0.3, PH + 3.15, axis="z")
    skirt_roof(TW, TD, PH + 4.35, PH + 5.0, 1.6)
    wall(-TW / 2 + 0.6, TW / 2 - 0.6, TD / 2 - 0.6, PH + 4.9, PH + 6.0, mat="red")
    wall(-TW / 2 + 0.6, TW / 2 - 0.6, -TD / 2 + 0.6, PH + 4.9, PH + 6.0, mat="red")
    hip_roof(TW - 1.2, TD - 1.2, PH + 6.1, PH + 8.1, 1.7, apex_w=(TW - 1.2) * 0.4, finial=False)
    plaque(0, PH + 4.55, TD / 2 + 0.95, 2.4, 0.9)


def qm_bridge():
    """虹桥 (8.48 x 7.75 x 26.01, its length along z): the timber arch bridge of 《清明上河图》: rows of logs bearing an
    arched deck. The deck follows the walkers' path (Worlds: ±12 m, 6 m high in the middle), railings along both sides."""
    L, H, Wd = 12.0, 6.0, 7.6

    def y(z):
        return max(0.0, H * (1 - (z / L) ** 2))
    # Deck (boards) and its underside.
    K.grid("deck", lambda u, v: (-Wd / 2 + Wd * u, y(-13 + 26 * v) + 0.0, -13 + 26 * v), 6, 40, uvscale=(Wd / 1.4, 26 / 1.4), flip=True)
    K.grid("wood", lambda u, v: (-Wd / 2 + Wd * u, y(-13 + 26 * v) - 0.35, -13 + 26 * v), 6, 40, uvscale=(Wd / 1.6, 26 / 1.6))
    for sx in (-1, 1):
        K.grid("wood", lambda u, v, sx=sx: (sx * Wd / 2, y(-13 + 26 * u) - 0.35 + 0.35 * v, -13 + 26 * u), 40, 1, uvscale=(16, 0.3), flip=(sx < 0))
    # The arch: five rows of log beams in a polygonal arch under the deck (the interlocking 虹桥 frame).
    for k in range(6):
        x = -Wd / 2 + 0.5 + k * (Wd - 1.0) / 5
        prev = None
        for i in range(9):
            z = -11.0 + 22.0 * i / 8
            p = (x, y(z) - 0.55 - 0.5 * (1 - (z / 11.0) ** 2), z)
            if prev:
                K.beam("woodpost", prev, p, 0.42, 0.42)
            prev = p
    for i in range(9):
        z = -11.0 + 22.0 * i / 8
        K.beam("woodpost", (-Wd / 2, y(z) - 0.9 - 0.5 * (1 - (z / 11.0) ** 2), z), (Wd / 2, y(z) - 0.9 - 0.5 * (1 - (z / 11.0) ** 2), z), 0.28, 0.28)
    # Railings along both edges, following the deck.
    for sx in (-1, 1):
        xx = sx * (Wd / 2 - 0.1)
        prev = None
        for i in range(53):
            z = -13 + 26 * i / 52
            K.box("red", xx, y(z) + 0.5, z, 0.14, 1.0, 0.14)
            p = (xx, y(z) + 1.0, z)
            if prev:
                K.beam("red", prev, p, 0.14, 0.12)
                K.beam("red", (prev[0], prev[1] - 0.5, prev[2]), (p[0], p[1] - 0.5, p[2]), 0.08, 0.08)
            prev = p
    # Abutments.
    for sz in (-1, 1):
        K.box("stone", 0, 0.4, sz * 12.3, Wd + 0.8, 0.8, 1.6)


def ql_pavilion():
    """千里江山图 pavilion (9.99 x 7.01 x 9.98): square, on a stone base, red posts with railings between, a pyramid
    roof with a finial."""
    W = 6.4
    platform(W + 1.6, W + 1.6, 0.7)
    xs = [-W / 2, -W / 6, W / 6, W / 2]
    for x in xs:
        for z in xs:
            if abs(x) < W / 2 - 0.01 and abs(z) < W / 2 - 0.01:
                continue
            K.cyl("redpost", x, z, 0.7, 3.6, 0.15, seg=16, cap=False)
            K.box("stone", x, 0.76, z, 0.42, 0.12, 0.42)
    frame_beams(xs, xs, 3.6)
    brackets([-W / 2, W / 2], [-W / 2, W / 2], 3.6, size=0.45)
    for (a, b) in ((-W / 2, -W / 6), (W / 6, W / 2)):
        railing(a + 0.15, b - 0.15, W / 2, 0.7, 0.75)
    for side in (-1,):
        railing(-W / 2 + 0.15, W / 2 - 0.15, side * W / 2, 0.7, 0.75)
    for sx in (-1, 1):
        railing(-W / 2 + 0.15, W / 2 - 0.15, sx * W / 2, 0.7, 0.75, axis="z")
    hip_roof(W, W, 4.25, 6.4, 1.6, finial=True)


def ql_cottage():
    """千里江山图 cottages (16.04 x 6.00 x 15.00): a thatched main house, a side house, a fenced yard."""
    _shift(lambda: thatch_house(7.0, 4.6, 2.5), -2.0, -4.0)
    _shift(lambda: thatch_house(4.6, 3.8, 2.2, door=True), 4.8, 2.0, yaw=-90)
    fence([(-7.5, -1.0), (-7.5, 6.8), (2.4, 6.8)])


def ql_mill():
    """水磨 (8.41 x 6.99 x 8.06): a mill house on a stone base by the water, its wheel on the right."""
    _shift(lambda: hall(4.6, 4.2, 2.6, 2, ["door", "window"], oz=0.9, ox=0.6, plinth=0.9, post="woodpost"), -1.0, 0.0)
    # The wheel: two rims, spokes, paddles, on an axle into the house.
    cx, cy, R = 2.6, 2.3, 2.1
    for dz in (-0.45, 0.45):
        prev = None
        for i in range(25):
            a = 2 * math.pi * i / 24
            p = (cx + dz, cy + R * math.sin(a), R * math.cos(a))
            if prev:
                K.beam("woodpost", prev, p, 0.12, 0.16)
            prev = p
        for i in range(8):
            a = 2 * math.pi * i / 8
            K.beam("woodpost", (cx + dz, cy, 0), (cx + dz, cy + R * math.sin(a), R * math.cos(a)), 0.1, 0.1)
    for i in range(16):
        a = 2 * math.pi * i / 16
        K.box("board", cx, cy + (R + 0.15) * math.sin(a), (R + 0.15) * math.cos(a), 1.0, 0.06 + 0.3 * abs(math.cos(a)) * 0, 0.4) if False else \
            K.beam("board", (cx - 0.5, cy + (R + 0.1) * math.sin(a), (R + 0.1) * math.cos(a)), (cx + 0.5, cy + (R + 0.1) * math.sin(a), (R + 0.1) * math.cos(a)), 0.45, 0.06, up=(0, math.sin(a), math.cos(a)))
    K.beam("woodpost", (cx - 2.0, cy, 0), (cx + 0.7, cy, 0), 0.2, 0.2)
    K.box("stone", cx + 0.9, 0.9, 0, 0.5, 1.8, 1.2)


def ql_longbridge():
    """千里江山图 long bridge (39.99 x 12.46 x 5.83, its length along x): a level deck on piers across the water, railings,
    and in the middle a two-storey pavilion over the deck."""
    L, Wd, yd = 40.0, 4.6, 3.6
    K.box("board", 0, yd, 0, L, 0.2, Wd)
    K.box("wood", 0, yd - 0.3, 0, L, 0.4, Wd - 0.2, skip=("+y",))
    for k in range(9):
        x = -L / 2 + 2 + k * (L - 4) / 8
        K.box("stone", x, (yd - 0.5) / 2, 0, 1.2, yd - 0.5, Wd + 0.6)
        K.box("stone", x, (yd - 0.5) / 2, Wd / 2 + 0.6, 0.9, yd - 0.5, 0.6, yaw=45)
    for sz in (-1, 1):
        railing(-L / 2, -3.4, sz * (Wd / 2 - 0.1), yd + 0.1, 0.95, step=0.5)
        railing(3.4, L / 2, sz * (Wd / 2 - 0.1), yd + 0.1, 0.95, step=0.5)
    # The pavilion over the middle.
    PW, PD = 6.0, Wd + 0.4
    xs = [-PW / 2, 0.0, PW / 2]
    columns(xs, [-PD / 2, PD / 2], yd + 0.1, 3.0, 0.16)
    frame_beams(xs, [-PD / 2, PD / 2], yd + 3.1)
    brackets(xs, [-PD / 2, PD / 2], yd + 3.1, size=0.45)
    skirt_roof(PW, PD, yd + 3.75, yd + 4.4, 1.4)
    top = upper_storey(PW - 0.8, PD - 0.8, yd + 4.6, 2.2, balcony=0.4)
    hip_roof(PW - 0.8, PD - 0.8, top + 0.2, top + 2.6, 1.4, apex_w=(PW - 0.8) * 0.4, finial=False)


_SHIFT = [None]


def _shift(fn, dx, dz, yaw=0.0):
    """Runs [fn] with everything it adds moved by (dx, 0, dz) and turned [yaw] degrees about y."""
    before = {m: len(K.BATCH.get(m, ([], [], []))[0]) for m in K.MATS}
    fn()
    a = math.radians(yaw)
    c, s_ = math.cos(a), math.sin(a)
    for m, (vs, fs, us) in K.BATCH.items():
        for i in range(before.get(m, 0), len(vs)):
            bx, by, bz = vs[i]           # Blender (x, -z, y)
            gx, gz = bx, -by
            rx, rz = gx * c + gz * s_, -gx * s_ + gz * c
            vs[i] = (rx + dx, -(rz + dz), bz)


BUILD = {"qm_shop": qm_shop, "qm_house": qm_house, "qm_tavern": qm_tavern, "qm_shop2": qm_shop2, "qm_warehouse": qm_warehouse,
         "qm_farm": qm_farm, "qm_teashed": qm_teashed, "qm_stall": qm_stall, "qm_cailou": qm_cailou, "qm_gate": qm_gate,
         "qm_bridge": qm_bridge, "ql_pavilion": ql_pavilion, "ql_cottage": ql_cottage, "ql_mill": ql_mill, "ql_longbridge": ql_longbridge}

for name, fn in BUILD.items():
    if ONLY and name not in ONLY:
        continue
    K.reset()
    materials()
    fn()
    K.export(os.path.join(OUT, f"{name}.glb"), key=(0.45, 0.55, 0.7), key_k=0.34, ao_dist=1.6, gain=1.2)
