"""Each walk-in world's own light: its panorama, linearized and brightened, as an HDR for Filament's cmgen.

uv run --with pillow --with numpy python -I tools/pano_ibl.py <pano.jpg> <out.hdr> [gain]

Pure Python (no Blender, which the model prep keeps busy). The sky is lifted more than the ground, so the
light comes from above as outdoors (models shaded on top, darker beneath) instead of evenly from all round.
"""
import sys
import numpy as np
from PIL import Image

src, out = sys.argv[1], sys.argv[2]
gain = float(sys.argv[3]) if len(sys.argv) > 3 else 1.6
im = Image.open(src).convert("RGB").resize((1024, 512), Image.LANCZOS)
rgb = np.asarray(im, dtype=np.float32) / 255.0
lin = np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)
h = lin.shape[0]
# Upper half (sky) up to 1.6x, lower half (ground) down to 0.6x, smoothly.
v = np.linspace(1.0, -1.0, h, dtype=np.float32)[:, None, None]
lin = lin * gain * (1.1 + 0.5 * np.clip(v, -1, 1))
lin += np.array([0.01, 0.011, 0.014], dtype=np.float32)

# Radiance RGBE.
m = lin.max(axis=2)
e = np.ceil(np.log2(np.maximum(m, 1e-32)))
scale = np.where(m > 1e-32, 256.0 / np.exp2(e), 0.0)
rgbe = np.zeros(lin.shape[:2] + (4,), dtype=np.uint8)
rgbe[..., :3] = np.clip(lin * scale[..., None], 0, 255).astype(np.uint8)
rgbe[..., 3] = np.where(m > 1e-32, e + 128, 0).astype(np.uint8)
with open(out, "wb") as f:
    f.write(b"#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n")
    f.write(f"-Y {lin.shape[0]} +X {lin.shape[1]}\n".encode())
    f.write(rgbe.tobytes())
print("wrote", out, "mean", float(lin.mean()))
