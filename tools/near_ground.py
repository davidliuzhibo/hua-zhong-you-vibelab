"""The ground round the player, made for real (画中游 0.5.1, device 2026-11-27: 「用户附近区域内能够看到的近景……地面道路
等等都做得相当之精细」). One GLB per world, drawn at life size over the flat photo ground within ~17 m of the player:

- stone paving: slab by slab (rows of 0.5-0.7 m, slabs 0.7-1.3 m long), each bevelled, a little high or low, 1.6 cm
  joints over a gravel bed; the street's darker slabs where 孙羊正店's street runs;
- earth and roads: a gently uneven surface (0.4 m grid), pebbles strewn on it, grass at its edges;
- fields and meadows: the same with tufts of real grass blades (geometry, no alpha cards);
- the water's edges: in town a dressed-stone embankment (two courses of blocks, coping stones, mooring posts, a flight
  of steps down to the water at 汴河); in the hills boulders half sunk at the waterline and reeds;
- the light baked into the vertices (kit3d), the buildings standing round included as occluders (shade at their feet).

blender -b --factory-startup -P tools/near_ground.py -- <layout.json> <sizes.txt> <out.glb>
(layout: core/WorldDumpTest with HZ_DUMP; game frame: x right, y up, z toward the player's back, the player at 0,0.)
"""
import json
import math
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "tools", "blender"))
import bmesh  # noqa: E402
import bpy  # noqa: E402
import kit3d as K  # noqa: E402
from mathutils import Vector  # noqa: E402

args = sys.argv[sys.argv.index("--") + 1:]
L = json.load(open(args[0]))
SIZES = {}
for line in open(args[1]):
    f = line.split()
    if len(f) >= 4:
        SIZES[f[0]] = (float(f[1]), float(f[2]), float(f[3]))
OUT = args[2]
WID = L["id"]
rnd = random.Random(hash(WID) & 0xFFFF)

R0, R1 = 23.0, 26.0          # all of the photo ground disc (26 m): thinning out raggedly at its edge
X0, X1, Z0, Z1 = -26.5, 26.5, -26.5, 26.5
GRASS_R = 14.0                # tufts of grass only this near
GROUND_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "app", "src", "main", "assets", "ground")

KIND = {"FFB8AD98": "pave", "FFA89F8C": "pave2", "FFB59A72": "earth", "FFC9B48E": "road", "FF8FA968": "field",
        "FF9DB37A": "field", "FFC4A774": "shore"}
TOWN = WID.startswith("qm")


def inside(r, x, z, m=0.0):
    x0, z0, x1, z1 = r[0], r[1], r[2], r[3]
    return min(x0, x1) - m <= x <= max(x0, x1) + m and min(z0, z1) - m <= z <= max(z0, z1) + m


def surface(x, z):
    for w in L["water"]:
        if inside(w, x, z):
            return "water"
    k = KIND.get(L["ground"], "earth")
    for s in L["strips"]:
        if inside(s, x, z):
            k = KIND.get(s[4], "road")
    return k


# Footprints of what stands (no slabs or grass under a house; boxes that shade the ground).
FOOT = []
for m, x, y, z, yaw, sc in L["pieces"] + ([L["restore"][:4] + [L["restore"][4], 1.0]] if L.get("restore") else []):
    s = SIZES.get(m)
    if not s or m.startswith("p_") or m.startswith("lantern"):
        continue
    tree = m.startswith("t_") or m == "ql_pine"
    FOOT.append(dict(m=m, x=x, z=z, yaw=yaw, hx=s[0] / 2 * sc, hz=s[2] / 2 * sc, h=s[1] * sc, tree=tree,
                     bridge=m in ("qm_bridge", "ql_longbridge"), boat=m in ("qm_cargo", "qm_bigboat", "qm_canopy", "qm_rowboat", "ql_fishboat")))


def local(f, x, z):
    a = math.radians(f["yaw"])
    dx, dz = x - f["x"], z - f["z"]
    return dx * math.cos(a) - dz * math.sin(a), dx * math.sin(a) + dz * math.cos(a)


def covered(x, z, m=0.0, trees=False, bridges=True):
    for f in FOOT:
        if f["boat"] or (f["tree"] and not trees) or (f["bridge"] and not bridges):
            continue
        lx, lz = local(f, x, z)
        k = 0.3 if f["tree"] else 0.92
        if abs(lx) < f["hx"] * k + m and abs(lz) < f["hz"] * k + m:
            return True
    return False


def keep(x, z):
    """Within the near region, thinning out raggedly between R0 and R1."""
    if not (X0 <= x <= X1 and Z0 <= z <= Z1):
        return False
    r = math.hypot(x, z * 1.0)
    if r <= R0:
        return True
    if r >= R1:
        return False
    return rnd.random() > (r - R0) / (R1 - R0)


# ---- Materials -----------------------------------------------------------------------------------------------------
K.reset()
K.mat("slab_a", K.tex("rock_boulder_dry"), tile=1.8, rgb=(1.02, 0.99, 0.93), px=2048)
K.mat("slab_b", K.tex("rock_boulder_dry"), tile=1.8, rgb=(0.93, 0.91, 0.86), px=2048)
K.mat("slab_c", K.tex("rock_boulder_dry"), tile=1.8, rgb=(1.08, 1.03, 0.94), px=2048)
K.mat("slab_d", K.tex("rock_boulder_dry"), tile=1.8, rgb=(0.80, 0.78, 0.74), px=2048)
K.mat("bed", K.tex("rocky_trail"), tile=1.5, rgb=(0.62, 0.58, 0.52), px=1024)
# The game's own ground photos at the game's gains (render/WorldView GROUND_GAIN): the made ground matches the photo
# ground and the fade beyond it in colour; it adds the relief, the light and the stones.
K.mat("earth", os.path.join(GROUND_DIR, "earth.jpg"), tile=4.0, rgb=(0.64, 0.64, 0.64), px=2048)
K.mat("road", os.path.join(GROUND_DIR, "road.jpg"), tile=4.0, rgb=(0.60, 0.60, 0.60), px=2048)
K.mat("field", os.path.join(GROUND_DIR, "field.jpg"), tile=4.0, rgb=(0.80, 0.80, 0.80), px=2048)
K.mat("shore", os.path.join(GROUND_DIR, "shore.jpg"), tile=4.0, rgb=(0.52, 0.52, 0.52), px=2048)
K.mat("block", K.tex("stone_brick_wall_001"), tile=1.6, rgb=(1.45, 1.38, 1.25), px=1024)
K.mat("coping", K.tex("rock_boulder_dry"), tile=1.2, rgb=(0.95, 0.93, 0.88), px=1024)
K.mat("rock", K.tex("rock_boulder_dry"), tile=1.4, rgb=(0.92, 0.90, 0.86), smooth=True, px=1024)
K.mat("grass", rgb=(0.36, 0.52, 0.20))
K.mat("grass2", rgb=(0.50, 0.60, 0.26))
K.mat("reed", rgb=(0.58, 0.62, 0.32))


def flat_tri_count():
    return sum(len(fs) for _, fs, _ in K.BATCH.values())


# ---- Paving --------------------------------------------------------------------------------------------------------
def slab(x0, z0, x1, z1, mname):
    g = 0.008
    x0, x1, z0, z1 = x0 + g, x1 - g, z0 + g, z1 - g
    yt = 0.036 + rnd.uniform(-0.003, 0.005)
    c = 0.028
    ye = yt - 0.014
    ou, ov = rnd.random(), rnd.random()
    t = K.MATS[mname]["tile"]

    def uv(x, z):
        return ((x - x0) / t + ou, (z - z0) / t + ov)
    # Top (facing up: a-b-c-d counter-clockwise seen from above = x0,z1 -> x1,z1 -> x1,z0 -> x0,z0).
    pts = [(x0 + c, yt, z1 - c), (x1 - c, yt, z1 - c), (x1 - c, yt, z0 + c), (x0 + c, yt, z0 + c)]
    K.poly(mname, pts, [uv(p[0], p[2]) for p in pts])
    # Bevels: from the top's edge down to the slab's edge.
    ring_in = [(x0 + c, z1 - c), (x1 - c, z1 - c), (x1 - c, z0 + c), (x0 + c, z0 + c)]
    ring_out = [(x0, z1), (x1, z1), (x1, z0), (x0, z0)]
    for i in range(4):
        a, b = ring_in[i], ring_in[(i + 1) % 4]
        A, B = ring_out[i], ring_out[(i + 1) % 4]
        q = [(A[0], ye, A[1]), (B[0], ye, B[1]), (b[0], yt, b[1]), (a[0], yt, a[1])]
        K.poly(mname, q, [uv(p[0], p[2]) for p in q])


def paving():
    z = Z0
    n = 0
    while z < Z1:
        w = rnd.uniform(0.48, 0.68)
        x = X0 - rnd.uniform(0, 1.0)
        while x < X1:
            l = rnd.uniform(0.7, 1.3)
            cx, cz = x + l / 2, z + w / 2
            corners = [(x, z), (x + l, z), (x, z + w), (x + l, z + w)]
            kinds = {surface(px, pz) for px, pz in corners}
            # The street's darker paving and the plain paving are one surface (a slab may straddle where they meet).
            if kinds <= {"pave", "pave2"} and keep(cx, cz) and not covered(cx, cz, 0.2):
                dark = surface(cx, cz) == "pave2"
                mname = "slab_d" if dark else rnd.choice(["slab_a", "slab_a", "slab_b", "slab_c"])
                slab(x, z, x + l, z + w, mname)
                n += 1
            x += l
        z += w
    # The gravel bed seen in the joints: 1 m cells under paved ground.
    for i in range(int(X0), int(X1) + 1):
        for j in range(int(Z0), int(Z1) + 1):
            cx, cz = i + 0.5, j + 0.5
            if surface(cx, cz) in ("pave", "pave2") and math.hypot(cx, cz) < R1 + 0.5 and all(surface(cx + dx, cz + dz) != "water" for dx in (-0.5, 0.5) for dz in (-0.5, 0.5)):
                K.quad("bed", (i, 0.017, j + 1), (i + 1, 0.017, j + 1), (i + 1, 0.017, j), (i, 0.017, j))
    return n


# ---- Earth, roads, fields, shores: an uneven grid -------------------------------------------------------------------
def hnoise(x, z):
    return (0.012 * math.sin(x * 1.7 + 0.3) * math.sin(z * 1.3 - 0.7) + 0.008 * math.sin(x * 4.1 + z * 3.3)
            + 0.005 * math.sin(x * 9.3 - z * 7.7))


# Everything made here stays above the game's flat photo ground and its strips (0 and 0.012 m): where it dipped
# under them they showed through as dark patches (emulator 2026-11-27, 城外街道).
SOFT_Y = 0.052


def soft_ground():
    step = 0.5
    nx = int((X1 - X0) / step)
    nz = int((Z1 - Z0) / step)
    H = {}

    def h(i, j):
        if (i, j) not in H:
            x, z = X0 + i * step, Z0 + j * step
            H[(i, j)] = SOFT_Y + hnoise(x, z)
        return H[(i, j)]
    for i in range(nx):
        for j in range(nz):
            x, z = X0 + (i + 0.5) * step, Z0 + (j + 0.5) * step
            k = surface(x, z)
            if k not in ("earth", "road", "field", "shore") or not keep(x, z) or covered(x, z, 0.1):
                continue
            # No grid cell may reach over the water's edge.
            if any(surface(x + dx, z + dz) == "water" for dx in (-0.2, 0.2) for dz in (-0.2, 0.2)):
                continue
            xa, xb, za, zb = X0 + i * step, X0 + (i + 1) * step, Z0 + j * step, Z0 + (j + 1) * step
            K.quad(k, (xa, h(i, j + 1), zb), (xb, h(i + 1, j + 1), zb), (xb, h(i + 1, j), za), (xa, h(i, j), za))


def pebble(x, z, r, mname="rock"):
    """A small rounded stone, half sunk (an icosphere, flattened a little, its lower half left out)."""
    bm = bmesh.new()
    bmesh.ops.create_icosphere(bm, subdivisions=1, radius=1.0)
    sx, sy, sz = r * rnd.uniform(0.8, 1.25), r * rnd.uniform(0.55, 0.8), r * rnd.uniform(0.8, 1.25)
    a = rnd.uniform(0, math.pi)
    ca, sa = math.cos(a), math.sin(a)
    for f in bm.faces:
        pts = []
        for v in f.verts:
            px, py, pz = v.co.x * sx, v.co.z * sy, v.co.y * sz
            pts.append((x + px * ca - pz * sa, SOFT_Y - 0.01 + py - sy * 0.35, z + px * sa + pz * ca))
        if max(p[1] for p in pts) < SOFT_Y - 0.01:
            continue
        K.tri(mname, pts[0], pts[1], pts[2])
    bm.free()


def boulder(x, z, r):
    """A boulder half sunk: a squashed icosphere with its vertices pushed about."""
    bm = bmesh.new()
    bmesh.ops.create_icosphere(bm, subdivisions=2, radius=1.0)
    out = []
    for f in bm.faces:
        pts = []
        for v in f.verts:
            p = v.co
            k = 1.0 + 0.18 * math.sin(p.x * 3.1 + x) * math.cos(p.y * 2.7 + z) + rnd.uniform(-0.06, 0.06) * 0
            pts.append((x + p.x * r * k * 1.15, max(-0.3, p.z * r * k * 0.7 - r * 0.2), z + p.y * r * k))
        out.append(pts)
    bm.free()
    for pts in out:
        if max(p[1] for p in pts) < -0.25:
            continue
        K.tri("rock", pts[0], pts[1], pts[2])


def tuft(x, z, height, mname="grass", blades=7):
    for b in range(blades):
        a = rnd.uniform(0, 2 * math.pi)
        lean = rnd.uniform(0.15, 0.45)
        hgt = height * rnd.uniform(0.6, 1.1)
        w = rnd.uniform(0.012, 0.022)
        bx, bz = x + rnd.uniform(-0.05, 0.05), z + rnd.uniform(-0.05, 0.05)
        dx, dz = math.cos(a), math.sin(a)
        px, pz = -dz * w, dx * w
        y0 = SOFT_Y - 0.02
        tip = (bx + dx * lean * hgt, y0 + hgt, bz + dz * lean * hgt)
        K.tri(mname, (bx - px, y0, bz - pz), (bx + px, y0, bz + pz), tip)


def scatter():
    n_peb = n_tuft = 0
    tries = 9000
    for _ in range(tries):
        x, z = rnd.uniform(X0, X1), rnd.uniform(Z0, Z1)
        k = surface(x, z)
        if not keep(x, z) or covered(x, z, 0.15) or math.hypot(x, z) < 0.8:
            continue
        r = math.hypot(x, z)
        if k in ("earth", "road", "shore") and rnd.random() < (0.12 if r < GRASS_R else 0.02):
            pebble(x, z, rnd.uniform(0.04, 0.11))
            n_peb += 1
        elif k == "field" and r < GRASS_R and rnd.random() < 0.55:
            tuft(x, z, rnd.uniform(0.18, 0.4), rnd.choice(["grass", "grass", "grass2"]), blades=rnd.randint(6, 10))
            n_tuft += 1
    # Grass along the edges of earth and roads and at the feet of walls (where nobody treads).
    for _ in range(4000):
        x, z = rnd.uniform(X0, X1), rnd.uniform(Z0, Z1)
        k = surface(x, z)
        if k not in ("earth", "road", "field") or not keep(x, z) or covered(x, z, 0.05) or not (1.0 < math.hypot(x, z) < GRASS_R):
            continue
        near_wall = covered(x, z, 0.6) and not covered(x, z, 0.05)
        edge = k == "earth" and any(surface(x + dx, z + dz) not in ("earth",) for dx in (-0.8, 0.8) for dz in (-0.8, 0.8))
        if near_wall or edge:
            tuft(x, z, rnd.uniform(0.12, 0.3), rnd.choice(["grass", "grass2"]), blades=rnd.randint(5, 8))
            n_tuft += 1
    return n_peb, n_tuft


# ---- Water's edges ---------------------------------------------------------------------------------------------------
def edges():
    """Each water box's sides that have land beside them, within the near region, as (x0, z0, x1, z1, land side)."""
    out = []
    for w in L["water"]:
        x0, x1 = min(w[0], w[2]), max(w[0], w[2])
        z0, z1 = min(w[1], w[3]), max(w[1], w[3])
        for (ax, az, bx, bz, nx, nz) in ((x0, z0, x0, z1, -1, 0), (x1, z0, x1, z1, 1, 0), (x0, z0, x1, z0, 0, -1), (x0, z1, x1, z1, 0, 1)):
            # Clip to the near region.
            if nx != 0:
                za, zb = max(az, Z0), min(bz, Z1)
                if za >= zb or not (X0 - 1 <= ax <= X1 + 1):
                    continue
                out.append((ax, za, ax, zb, nx, nz))
            else:
                xa, xb = max(ax, X0), min(bx, X1)
                if xa >= xb or not (Z0 - 1 <= az <= Z1 + 1):
                    continue
                out.append((xa, az, xb, az, nx, nz))
    return out


def embankment():
    n = 0
    for (ax, az, bx, bz, nx, nz) in edges():
        L_ = math.hypot(bx - ax, bz - az)
        ux, uz = (bx - ax) / L_, (bz - az) / L_
        s = 0.0
        while s < L_:
            l = rnd.uniform(0.55, 1.05)
            l = min(l, L_ - s)
            mx, mz = ax + ux * (s + l / 2), az + uz * (s + l / 2)
            land_x, land_z = mx + nx * 0.6, mz + nz * 0.6
            if keep(mx, mz) and surface(land_x, land_z) != "water" and not covered(land_x, land_z, 0.3) and not covered(mx, mz, 0.5):
                if TOWN:
                    yaw = math.degrees(math.atan2(ux, uz)) + 90
                    # Two courses of blocks, their faces at the water's edge, each a little in or out.
                    for (y0, hh) in ((-0.62, 0.34), (-0.28, 0.3)):
                        o = rnd.uniform(-0.015, 0.02)
                        K.box("block", mx + nx * (0.22 + o), y0 + hh / 2, mz + nz * (0.22 + o), l - 0.012, hh - 0.012, 0.44, yaw=yaw)
                    # The coping: a long stone on top, a little over the water.
                    K.box("coping", mx + nx * 0.2, 0.045, mz + nz * 0.2, l - 0.01, 0.12, 0.5, yaw=yaw)
                    n += 1
                else:
                    # Boulders along the waterline, half sunk (two rows, the second smaller on the land side); reeds between.
                    boulder(mx + nx * 0.05, mz + nz * 0.05, rnd.uniform(0.35, 0.75))
                    if rnd.random() < 0.7:
                        boulder(mx + nx * 0.65 + ux * rnd.uniform(-0.3, 0.3), mz + nz * 0.65 + uz * rnd.uniform(-0.3, 0.3), rnd.uniform(0.18, 0.4))
                    if rnd.random() < 0.8:
                        for _ in range(rnd.randint(2, 4)):
                            tuft(mx + nx * rnd.uniform(-0.4, 0.3) + ux * rnd.uniform(-0.4, 0.4), mz + nz * rnd.uniform(-0.4, 0.3) + uz * rnd.uniform(-0.4, 0.4),
                                 rnd.uniform(0.6, 1.2), "reed", blades=rnd.randint(8, 14))
                    n += 1
            s += l
        # Mooring posts along town quays, every ~5 m.
        if TOWN:
            p = 2.5
            while p < L_:
                mx, mz = ax + ux * p, az + uz * p
                if keep(mx, mz) and surface(mx + nx * 0.6, mz + nz * 0.6) != "water" and not covered(mx + nx * 0.6, mz + nz * 0.6, 0.3):
                    K.lathe("coping", mx + nx * 0.28, 0.1, mz + nz * 0.28, [(0.15, 0.0), (0.15, 0.42), (0.13, 0.5), (0.07, 0.56), (0.0, 0.57)], seg=14)
                p += rnd.uniform(4.5, 6.0)
    return n


def steps_qm2():
    """汴河: a flight of steps down to the water by the grain boat (the porters' landing), right of the road."""
    if WID != "qm2":
        return
    x0, x1 = 4.6, 6.4
    for i in range(4):
        z = -6.0 - 0.32 * i
        y = 0.0 - 0.17 * (i + 1)
        K.box("coping", (x0 + x1) / 2, y + 0.08, z - 0.16, x1 - x0, 0.17, 0.33)


# ---- Build -----------------------------------------------------------------------------------------------------------
n_slabs = paving()
soft_ground()
n_peb, n_tuft = scatter()
n_edge = embankment()
steps_qm2()

# The buildings round as occluders for the light (not exported).
for f in FOOT:
    if f["tree"] or f["bridge"] or f["boat"] or math.hypot(f["x"], f["z"]) > 40:
        continue
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    me = bpy.data.meshes.new("occ")
    bm.to_mesh(me)
    bm.free()
    a = math.radians(f["yaw"])
    for v in me.vertices:
        lx, ly, lz = v.co.x * 2 * f["hx"] * 0.92, (v.co.z + 0.5) * f["h"], v.co.y * 2 * f["hz"] * 0.92
        gx = f["x"] + lx * math.cos(a) + lz * math.sin(a)
        gz = f["z"] - lx * math.sin(a) + lz * math.cos(a)
        v.co = K.G(gx, ly, gz)
    o = bpy.data.objects.new("occ", me)
    bpy.context.scene.collection.objects.link(o)
    K.OCCLUDE.append(o)

tris = K.export(OUT, key=(-0.45, 0.75, 0.6), sky=(0.88, 0.90, 0.93), ground=(0.50, 0.47, 0.42), key_rgb=(1.0, 0.95, 0.86),
                key_k=0.35, gain=1.25, ao_dist=2.5, ao_floor=0.5, quiet=True)
print(f"NEAR {WID}: {n_slabs} slabs, {n_peb} pebbles, {n_tuft} tufts, {n_edge} edge pieces, {tris} tris, {os.path.getsize(OUT) // 1024} KB")
