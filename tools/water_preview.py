"""A look at the v0.4.0 water before the emulator: the dump of WaterDumpTest (wave surface, shore bands; life size and
miniature) rendered with the game's water colour, its ripple normal map, the shore picture and the world's panorama.

blender -b --factory-startup --python Pico_Vibe_Lab/HuaZhongYou/tools/water_preview.py -- <dump_dir> <world> <out_prefix>
Writes <out_prefix>_full.png (from the player's eyes, 1.2 m) and <out_prefix>_mini.png (from the seat over the table).
"""
import math
import os
import sys

import bpy

a = sys.argv[sys.argv.index("--") + 1:]
dump, wid, out = a[0], a[1], a[2]
H = os.path.dirname(os.path.abspath(__file__))
A = os.path.join(H, "..", "app", "app", "src", "main", "assets")
info = dict(l.split(" ", 1) for l in open(os.path.join(dump, "info.txt")).read().strip().split("\n"))
k = float(info["k"])
reg = [float(x) for x in info["region"].split()]
col = int(info["color"], 16)
wr, wg, wb = ((col >> 16) & 255) / 255, ((col >> 8) & 255) / 255, (col & 255) / 255


def srgb2lin(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def setup(mini):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.samples = 24
    sc.cycles.use_denoising = True
    sc.render.resolution_x, sc.render.resolution_y = 1280, 720
    sc.view_settings.view_transform = "Standard"
    w = bpy.data.worlds.new("w")
    sc.world = w
    w.use_nodes = True
    nt = w.node_tree
    env = nt.nodes.new("ShaderNodeTexEnvironment")
    env.image = bpy.data.images.load(os.path.join(A, "scenes", f"pano_{wid}.jpg"))
    nt.links.new(env.outputs["Color"], nt.nodes["Background"].inputs["Color"])
    nt.nodes["Background"].inputs["Strength"].default_value = 1.0
    sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
    sun.data.energy = 2.5
    sun.rotation_euler = (math.radians(50), 0, math.radians(-30))
    sc.collection.objects.link(sun)
    bpy.ops.wm.obj_import(filepath=os.path.join(dump, f"{wid}_{'mini' if mini else 'full'}.obj"), forward_axis="NEGATIVE_Z", up_axis="Y")
    objs = [o for o in sc.objects if o.type == "MESH"]
    # Water: the game's PBR water (colour × 0.46/0.54/0.58, roughness 0.07, ripples' normal map at 0.8).
    wm = bpy.data.materials.new("water")
    wm.use_nodes = True
    n = wm.node_tree.nodes
    b = n["Principled BSDF"]
    b.inputs["Base Color"].default_value = (srgb2lin(wr * 0.46), srgb2lin(wg * 0.54), srgb2lin(wb * 0.58), 1)
    b.inputs["Roughness"].default_value = 0.07
    nimg = n.new("ShaderNodeTexImage")
    nimg.image = bpy.data.images.load(os.path.join(A, "water", "wn_00.png"))
    nimg.image.colorspace_settings.name = "Non-Color"
    nm = n.new("ShaderNodeNormalMap")
    nm.inputs["Strength"].default_value = 0.8
    wm.node_tree.links.new(nimg.outputs["Color"], nm.inputs["Color"])
    wm.node_tree.links.new(nm.outputs["Normal"], b.inputs["Normal"])
    # Shore: unlit, its picture's alpha.
    sm = bpy.data.materials.new("shore")
    sm.use_nodes = True
    sn = sm.node_tree.nodes
    sn.remove(sn["Principled BSDF"])
    img = sn.new("ShaderNodeTexImage")
    img.image = bpy.data.images.load(os.path.join(dump, "shore.png"))
    em = sn.new("ShaderNodeEmission")
    tr = sn.new("ShaderNodeBsdfTransparent")
    mix = sn.new("ShaderNodeMixShader")
    L = sm.node_tree.links
    L.new(img.outputs["Color"], em.inputs["Color"])
    L.new(img.outputs["Alpha"], mix.inputs["Fac"])
    L.new(tr.outputs["BSDF"], mix.inputs[1])
    L.new(em.outputs["Emission"], mix.inputs[2])
    L.new(mix.outputs["Shader"], sn["Material Output"].inputs["Surface"])
    for o in objs:
        o.data.materials.clear()
        o.data.materials.append(sm if o.name.startswith("shore") else wm)
    # Ground under it all (the world's paving), at 0.
    bpy.ops.mesh.primitive_plane_add(size=1)
    g = bpy.context.active_object
    gm = bpy.data.materials.new("ground")
    gm.use_nodes = True
    gn = gm.node_tree.nodes
    gi = gn.new("ShaderNodeTexImage")
    gi.image = bpy.data.images.load(os.path.join(A, "ground", "pave.jpg"))
    tc = gn.new("ShaderNodeTexCoord")
    mp = gn.new("ShaderNodeMapping")
    mp.inputs["Scale"].default_value = (240 / 3.5, 240 / 3.5, 1) if not mini else ((reg[1] - reg[0]) / 3.5, (reg[3] - reg[2]) / 3.5, 1)
    gm.node_tree.links.new(tc.outputs["Object"], mp.inputs["Vector"])
    gm.node_tree.links.new(mp.outputs["Vector"], gi.inputs["Vector"])
    gm.node_tree.links.new(gi.outputs["Color"], gn["Principled BSDF"].inputs["Base Color"])
    gn["Principled BSDF"].inputs["Roughness"].default_value = 0.9
    g.data.materials.append(gm)
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    sc.collection.objects.link(cam)
    sc.camera = cam
    if not mini:
        g.scale = (240, 240, 1)
        cam.location = (0, 0, 1.2)
        cam.rotation_euler = (math.radians(66), 0, 0)
        cam.data.lens = 16
    else:
        # The board's part only; the camera at the seat: the table's middle 0.45 m below the eyes, its near edge 0.42 m ahead.
        cx, cz = (reg[0] + reg[1]) / 2, (reg[2] + reg[3]) / 2
        g.scale = (reg[1] - reg[0], reg[3] - reg[2], 1)
        g.location = (cx, -cz, 0)
        gm.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.5, 0.36, 0.2, 1)
        depth = (reg[3] - reg[2]) * k
        # Eyes in world metres: above the board by 0.45 / k, back from its middle by (0.42 + depth/2) / k.
        cam.location = (cx, -(cz + (0.42 + depth / 2) / k), 0.45 / k)
        cam.rotation_euler = (math.radians(52), 0, 0)
        cam.data.lens = 22
        cam.data.clip_end = 1e4
    return sc


for mini in (False, True):
    sc = setup(mini)
    sc.render.filepath = f"{out}_{'mini' if mini else 'full'}.png"
    bpy.ops.render.render(write_still=True)
    print("PREVIEW", sc.render.filepath)
    if not mini:
        # Close, down at the quay's edge.
        sc.camera.rotation_euler = (math.radians(45), 0, math.radians(-25))
        sc.render.filepath = f"{out}_quay.png"
        bpy.ops.render.render(write_still=True)
        print("PREVIEW", sc.render.filepath)
