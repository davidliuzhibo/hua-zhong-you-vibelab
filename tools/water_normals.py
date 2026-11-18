"""Ripple normal maps for the walk-in worlds' water (v0.3): <out_dir>/wn_00..15.png, 256 px, tiling, looping.

uv run --with pillow --with numpy python -I tools/water_normals.py <out_dir>

A sum of waves with whole-number wave vectors (so each frame tiles) and whole-number phase speeds (so frame 16
is frame 0 again); most travel along +u, the flow, with a few slow cross ripples. Linear data: load with
TextureColorSpace.RAW.
"""
import sys
import numpy as np
from PIL import Image

out = sys.argv[1]
N, F = 256, 16
rnd = np.random.default_rng(7)
waves = []
for k in range(10):
    kx = int(rnd.integers(1, 7))
    ky = int(rnd.integers(-3, 4))
    amp = 1.0 / np.hypot(kx, ky) ** 1.1
    speed = kx if k < 7 else 0  # the last few stand still: a shimmer that is not all one way
    waves.append((kx, ky, amp, speed, rnd.random() * 2 * np.pi))
for k in range(4):  # fine cross ripples (wind)
    waves.append((int(rnd.integers(5, 12)), int(rnd.integers(-9, 10)), 0.12, int(rnd.integers(1, 3)), rnd.random() * 2 * np.pi))
u, v = np.meshgrid(np.arange(N) / N, np.arange(N) / N)
for f in range(F):
    t = f / F
    dhu = np.zeros((N, N)); dhv = np.zeros((N, N))
    for kx, ky, a, sp, ph in waves:
        arg = 2 * np.pi * (kx * u + ky * v - sp * t) + ph
        c = np.cos(arg) * a * 2 * np.pi
        dhu += c * kx; dhv += c * ky
    s = 0.035
    n = np.dstack([-dhu * s, -dhv * s, np.ones((N, N))])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    Image.fromarray(((n * 0.5 + 0.5) * 255).astype(np.uint8)).save(f"{out}/wn_{f:02d}.png", optimize=True)
print("wrote", F, "frames")
