#!/usr/bin/env python3
"""Writes assets/scenes/tris.txt: each scene model's triangles as drawn (a walk cycle: one frame's) and its file size,
for the HuaPerf log and core/Perf (v0.4.0). With --report, also prints each file's textures (size, which maps).

python3 Pico_Vibe_Lab/HuaZhongYou/tools/model_tris.py [--report]   (from the repository root or anywhere)
"""
import glob
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SCENES = os.path.join(HERE, "..", "app", "app", "src", "main", "assets", "scenes")


def read(p):
    with open(p, "rb") as f:
        f.read(12)
        ln, _ = struct.unpack("<II", f.read(8))
        j = json.loads(f.read(ln))
        bl, _ = struct.unpack("<II", f.read(8))
        b = f.read(bl)
    return j, b


def image_size(data):
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", data[16:24])
    i = 2
    while i < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        m = data[i + 1]
        if m in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", data[i + 5:i + 9])
            return w, h
        i += 2 + struct.unpack(">H", data[i + 2:i + 4])[0]
    return 0, 0


def tris(j):
    per = []
    for n in j.get("nodes", []):
        if "mesh" not in n:
            continue
        per.append(sum(j["accessors"][p["indices"]]["count"] // 3 for p in j["meshes"][n["mesh"]]["primitives"]))
    return per


def main():
    lines = []
    for p in sorted(glob.glob(os.path.join(SCENES, "*.glb"))):
        name = os.path.basename(p)[:-4]
        j, b = read(p)
        per = tris(j)
        t = max(per) if name.endswith("_walk") else sum(per)
        lines.append(f"{name} {t} {os.path.getsize(p)}")
        if "--report" in sys.argv:
            imgs = []
            for im in j.get("images", []):
                bv = j["bufferViews"][im["bufferView"]]
                o = bv.get("byteOffset", 0)
                imgs.append("%dx%d" % image_size(b[o:o + bv["byteLength"]]))
            m = j["materials"][0] if j.get("materials") else {}
            maps = [k for k in ("normalTexture", "emissiveTexture") if k in m] + list(m.get("pbrMetallicRoughness", {}).keys())
            tan = any("TANGENT" in pr["attributes"] for me in j["meshes"] for pr in me["primitives"])
            print(f"{name:24s} {t:7d} tris x{len(per)}  {os.path.getsize(p) // 1024:6d} KB  imgs {imgs} maps {maps} tangents {tan}")
    with open(os.path.join(SCENES, "tris.txt"), "w") as f:
        f.write("# v0.4.0: triangles per scene model as drawn (a walk cycle: one frame) and file bytes (tools/model_tris.py)\n")
        f.write("\n".join(lines) + "\n")
    print(f"tris.txt: {len(lines)} models")


main()
