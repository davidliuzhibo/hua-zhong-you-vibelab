"""画中游 0.5.2: the ox cart (太平车) put together from its baked parts. Seed3D, given the whole team in one picture,
twice turned the oxen across the cart; made apart (a yoked ox, a cart with its shafts), each comes out right.

blender -b --factory-startup -P tools/assemble_oxcart.py -- <cart.glb> <ox.glb> <out.glb> <ox1_fwd> <ox2_side> <ox2_fwd> <shaft_to>

Frames: both baked parts as bake_scene.py leaves them (game frame: +z forward, centred, feet at y 0). The shafts
(the cart's thin poles ahead of its body, 0.9 m apart) are drawn out so that their tips reach [shaft_to] m ahead of
the cart's middle (the first ox's yoke). One ox walks between the shafts, its middle [ox1_fwd] m ahead of the cart's
middle; the second beside it, [ox2_side] m to the side and [ox2_fwd] m ahead (a trace ox, as on the old carts). The
team heads +x in the result (core/Worlds.FWD qm_oxcart -90: side-on, toward +x), centred on x and z, feet at 0.
"""
import math
import sys

import bpy
from mathutils import Vector

a = sys.argv[sys.argv.index("--") + 1:]
cart, ox, out = a[0], a[1], a[2]
ox1_fwd, ox2_side, ox2_fwd, shaft_to = (float(v) for v in a[3:7])
SHAFT_FROM = 1.35     # the shafts' part beyond this (m ahead of the cart's middle) is drawn out
bpy.ops.wm.read_factory_settings(use_empty=True)


def load(path):
    before = set(bpy.context.scene.objects)
    bpy.ops.import_scene.gltf(filepath=path)
    new = [o for o in bpy.context.scene.objects if o not in before]
    root = bpy.data.objects.new("grp", None)
    bpy.context.scene.collection.objects.link(root)
    for o in new:
        if o.parent is None:
            o.parent = root
    return root, [o for o in new if o.type == "MESH"]


# Blender frame: the parts' forward (glTF +z) is Blender -y.
c_root, c_meshes = load(cart)
bpy.context.view_layer.update()
# The shafts' tips drawn forward: vertices ahead of SHAFT_FROM move ahead so the tip (the cart's front end) is at shaft_to.
tip = max(-(o.matrix_world @ v.co).y for o in c_meshes for v in o.data.vertices)
gain = (shaft_to - SHAFT_FROM) / max(1e-6, tip - SHAFT_FROM)
moved = 0
for o in c_meshes:
    mw = o.matrix_world
    inv = mw.inverted()
    for v in o.data.vertices:
        w = mw @ v.co
        f = -w.y
        if f > SHAFT_FROM:
            w.y = -(SHAFT_FROM + (f - SHAFT_FROM) * gain)
            v.co = inv @ w
            moved += 1
    o.data.update()
c_root.rotation_euler = (0, 0, math.radians(90))      # forward (-y) turned to +x
for k, (fwd, side, turn) in enumerate(((ox1_fwd, 0.0, -1.0), (ox2_fwd, ox2_side, 2.0))):
    o_root, _ = load(ox)
    o_root.rotation_euler = (0, 0, math.radians(90 + turn))
    o_root.location = (fwd, side, 0)
bpy.context.view_layer.update()
meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
bb = [o.matrix_world @ Vector(c) for o in meshes for c in o.bound_box]
lo = Vector((min(v.x for v in bb), min(v.y for v in bb), min(v.z for v in bb)))
hi = Vector((max(v.x for v in bb), max(v.y for v in bb), max(v.z for v in bb)))
shift = Vector((-(lo.x + hi.x) / 2, -(lo.y + hi.y) / 2, -lo.z))
for r in [o for o in bpy.context.scene.objects if o.parent is None]:
    r.location += shift
bpy.context.view_layer.update()
# Flat: every mesh keeps its place with no parent and its transform applied (tools/blender/walk.py joins the meshes
# and applies only their own transforms: a turned parent left the walk cycle a quarter turn off).
for o in meshes:
    mw = o.matrix_world.copy()
    o.parent = None
    o.matrix_world = mw
for e in [o for o in bpy.context.scene.objects if o.type == "EMPTY"]:
    bpy.data.objects.remove(e, do_unlink=True)
bpy.ops.object.select_all(action="DESELECT")
for o in meshes:
    o.select_set(True)
bpy.context.view_layer.objects.active = meshes[0]
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
bpy.ops.object.select_all(action="SELECT")
bpy.ops.export_scene.gltf(filepath=out, export_format="GLB", use_selection=True, export_apply=True, export_image_format="AUTO",
                          export_tangents=True)
print("ASSEMBLED", out, "shaft tip", round(tip, 2), "->", shaft_to, f"({moved} verts)", "extent", [round(v, 2) for v in (hi - lo)],
      "shift", [round(v, 2) for v in shift])
