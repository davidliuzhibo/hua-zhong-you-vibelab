#!/usr/bin/env python3
"""Writes assets/scenes/shapes.txt (画中游 0.5.2): each scene model's outline as columns, for core/MiniPanels (where
the panels stand clear of a miniature): its footprint cut into a 16 x 16 grid (model x, z) and, per cell, the lowest
and highest points of its triangles inside that cell (each triangle clipped to the cell, so a column is exactly as high
as the model there). All the versions the miniature may show count together (the model, _lo, _hero).

python3 Pico_Vibe_Lab/HuaZhongYou/tools/model_shapes.py

Line: "<model> <n> <x0> <x1> <z0> <z1> <cell> <cell> ..." (n*n cells, x outer, z inner; a cell "ymin,ymax" or "-").
"""
import glob
import json
import math
import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
SCENES = os.path.join(HERE, "..", "app", "app", "src", "main", "assets", "scenes")
N = 16


def read(p):
    with open(p, "rb") as f:
        f.read(12)
        ln, _ = struct.unpack("<II", f.read(8))
        j = json.loads(f.read(ln))
        bl, _ = struct.unpack("<II", f.read(8))
        b = f.read(bl)
    return j, b


def mat_mul(a, b):
    return [sum(a[k * 4 + r] * b[c * 4 + k] for k in range(4)) for c in range(4) for r in range(4)]


def local(n):
    if "matrix" in n:
        return list(n["matrix"])
    t = n.get("translation", [0, 0, 0])
    x, y, z, w = n.get("rotation", [0, 0, 0, 1])
    s = n.get("scale", [1, 1, 1])
    m = [1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
         2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
         2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
         t[0], t[1], t[2], 1]
    for c in range(3):
        for r in range(3):
            m[c * 4 + r] *= s[c]
    return m


def triangles(p):
    j, b = read(p)

    def acc(i):
        a = j["accessors"][i]
        v = j["bufferViews"][a["bufferView"]]
        o = v.get("byteOffset", 0) + a.get("byteOffset", 0)
        n = a["count"]
        if a["type"] == "VEC3":
            st = v.get("byteStride", 12) or 12
            return [struct.unpack_from("<3f", b, o + k * st) for k in range(n)]
        fmt = {5121: "B", 5123: "H", 5125: "I"}[a["componentType"]]
        sz = struct.calcsize(fmt)
        return [struct.unpack_from("<" + fmt, b, o + k * sz)[0] for k in range(n)]

    out = []

    def visit(ni, parent):
        n = j["nodes"][ni]
        m = mat_mul(parent, local(n))
        if "mesh" in n:
            for pr in j["meshes"][n["mesh"]]["primitives"]:
                pos = acc(pr["attributes"]["POSITION"])
                idx = acc(pr["indices"]) if "indices" in pr else list(range(len(pos)))
                w = [(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14]) for x, y, z in pos]
                for k in range(0, len(idx) - 2, 3):
                    out.append((w[idx[k]], w[idx[k + 1]], w[idx[k + 2]]))
        for c in n.get("children", []):
            visit(c, m)

    ident = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
    for r in j["scenes"][j.get("scene", 0)]["nodes"]:
        visit(r, ident)
    return out


def clip(poly, axis, v, keep_ge):
    """Sutherland-Hodgman against the plane coordinate[axis] = v (keep >= v or <= v)."""
    out = []
    n = len(poly)
    for i in range(n):
        a, b = poly[i], poly[(i + 1) % n]
        ina = a[axis] >= v if keep_ge else a[axis] <= v
        inb = b[axis] >= v if keep_ge else b[axis] <= v
        if ina:
            out.append(a)
        if ina != inb:
            t = (v - a[axis]) / (b[axis] - a[axis])
            out.append(tuple(a[k] + (b[k] - a[k]) * t for k in range(3)))
    return out


def shape(tris):
    x0 = min(min(p[0] for p in t) for t in tris)
    x1 = max(max(p[0] for p in t) for t in tris)
    z0 = min(min(p[2] for p in t) for t in tris)
    z1 = max(max(p[2] for p in t) for t in tris)
    dx = max((x1 - x0) / N, 1e-6)
    dz = max((z1 - z0) / N, 1e-6)
    lo = [[math.inf] * N for _ in range(N)]
    hi = [[-math.inf] * N for _ in range(N)]
    for t in tris:
        tx0, tx1 = min(p[0] for p in t), max(p[0] for p in t)
        tz0, tz1 = min(p[2] for p in t), max(p[2] for p in t)
        i0, i1 = max(0, int((tx0 - x0) / dx)), min(N - 1, int((tx1 - x0) / dx))
        k0, k1 = max(0, int((tz0 - z0) / dz)), min(N - 1, int((tz1 - z0) / dz))
        for i in range(i0, i1 + 1):
            for k in range(k0, k1 + 1):
                if i0 == i1 and k0 == k1:
                    poly = list(t)
                else:
                    poly = list(t)
                    for axis, v, ge in ((0, x0 + i * dx, True), (0, x0 + (i + 1) * dx, False), (2, z0 + k * dz, True), (2, z0 + (k + 1) * dz, False)):
                        poly = clip(poly, axis, v, ge)
                        if not poly:
                            break
                if not poly:
                    continue
                ys = [p[1] for p in poly]
                lo[i][k] = min(lo[i][k], min(ys))
                hi[i][k] = max(hi[i][k], max(ys))
    cells = []
    for i in range(N):
        for k in range(N):
            cells.append("-" if lo[i][k] == math.inf else f"{lo[i][k]:.2f},{hi[i][k]:.2f}")
    return f"{N} {x0:.3f} {x1:.3f} {z0:.3f} {z1:.3f} " + " ".join(cells)


def main():
    names = sorted({os.path.basename(p)[:-4] for p in glob.glob(os.path.join(SCENES, "*.glb"))})
    base = [n for n in names if not n.endswith(("_lo", "_hero", "_walk")) and not n.startswith(("ng_", "pano"))]
    lines = []
    for n in base:
        tris = []
        for v in (n, n + "_lo", n + "_hero"):
            if v in names:
                tris += triangles(os.path.join(SCENES, v + ".glb"))
        if tris:
            lines.append(f"{n} {shape(tris)}")
            print(n, len(tris), "triangles")
    with open(os.path.join(SCENES, "shapes.txt"), "w") as f:
        f.write("# v0.5.2: each scene model's outline as 16 x 16 columns (tools/model_shapes.py; core/MiniPanels)\n")
        f.write("\n".join(lines) + "\n")
    print(f"shapes.txt: {len(lines)} models")


main()
