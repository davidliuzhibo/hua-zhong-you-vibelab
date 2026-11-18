"""The walk-in worlds' ambient loops (v0.2), synthesized: river, waterfall, lake, fields, street, gate, 上元 night.

uv run -q --with numpy --with scipy python -I tools/make_ambience.py <out_dir>
Writes <name>.wav (30 s, 22050 Hz mono, seamless: the end crossfades into the start); convert to ogg after.
"""
import os
import sys
import wave

import numpy as np
from scipy.signal import butter, sosfilt

SR = 22050
N = SR * 30
out = sys.argv[1]
rng = np.random.default_rng(5)


def band(x, lo, hi):
    return sosfilt(butter(2, [lo, hi], btype="band", fs=SR, output="sos"), x)


def low(x, f):
    return sosfilt(butter(2, f, fs=SR, output="sos"), x)


def slow(rate, depth, n=N):
    """A wandering gain around 1."""
    k = int(30 * rate) + 2
    pts = rng.uniform(1 - depth, 1 + depth, k)
    pts[-1] = pts[0]
    return np.interp(np.linspace(0, k - 1, n), np.arange(k), pts)


def noise():
    return rng.standard_normal(N + SR)


def loop(x):
    """Crossfade the extra second at the end into the start, so the loop has no seam."""
    head = x[:SR].copy()
    tail = x[N:N + SR]
    f = np.linspace(0, 1, SR)
    y = x[:N].copy()
    y[:SR] = head * f + tail * (1 - f)
    return y


def chirp(t0, f0, f1, d, amp, x):
    i = int(t0 * SR)
    n = int(d * SR)
    if i + n >= len(x):
        return
    t = np.arange(n) / SR
    f = np.linspace(f0, f1, n)
    x[i:i + n] += amp * np.sin(2 * np.pi * np.cumsum(f) / SR) * np.sin(np.pi * t / d) ** 2


def bell(t0, f, amp, x, d=1.6):
    i = int(t0 * SR)
    n = int(d * SR)
    if i + n >= len(x):
        return
    t = np.arange(n) / SR
    env = np.exp(-t * 3.2)
    x[i:i + n] += amp * env * (np.sin(2 * np.pi * f * t) + 0.5 * np.sin(2 * np.pi * f * 2.76 * t) + 0.25 * np.sin(2 * np.pi * f * 5.4 * t))


def clack(t0, amp, x):
    i = int(t0 * SR)
    n = int(0.06 * SR)
    if i + n >= len(x):
        return
    x[i:i + n] += amp * band(rng.standard_normal(n), 900, 2500) * np.exp(-np.arange(n) / SR * 60)


def birds(x, count, amp):
    for _ in range(count):
        t = rng.uniform(0, 29)
        f = rng.uniform(2500, 4200)
        for k in range(rng.integers(2, 5)):
            chirp(t + k * 0.12, f, f * rng.uniform(1.1, 1.4), 0.08, amp, x)


def murmur():
    x = band(noise(), 250, 1100) * slow(1.5, 0.5, N + SR)
    x += 0.5 * band(noise(), 500, 2000) * slow(3, 0.7, N + SR)
    return x


def save(name, x):
    x = x / (np.max(np.abs(x)) + 1e-9) * 0.7
    with wave.open(os.path.join(out, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((x * 32767).astype(np.int16).tobytes())


river = low(noise(), 900) * slow(0.8, 0.25, N + SR) + 0.3 * band(noise(), 1500, 4000) * slow(4, 0.8, N + SR)
save("amb_river", loop(river))
fall = 0.8 * low(noise(), 3000) + 0.5 * band(noise(), 200, 900)
save("amb_fall", loop(fall))
lake = 0.6 * low(noise(), 500) * slow(0.4, 0.4, N + SR)
for _ in range(14):
    t = rng.uniform(0, 29)
    i = int(t * SR); n = int(0.5 * SR)
    lake[i:i + n] += 0.6 * low(rng.standard_normal(n), 700) * np.sin(np.pi * np.arange(n) / n)
birds(lake, 6, 0.08)
save("amb_lake", loop(lake))
field = 0.7 * band(noise(), 150, 700) * slow(0.5, 0.6, N + SR)
birds(field, 16, 0.12)
save("amb_field", loop(field))
street = murmur()
for _ in range(30):
    clack(rng.uniform(0, 29), 0.6, street)
save("amb_street", loop(street))
gate = 0.6 * band(noise(), 150, 600) * slow(0.5, 0.6, N + SR) + 0.4 * murmur()
t = 0.5
while t < 29:
    bell(t, 880 * rng.uniform(0.97, 1.03), 0.35, gate)
    t += rng.uniform(0.9, 1.4)
save("amb_gate", loop(gate))
night = murmur() * 1.2
for _ in range(10):
    t = rng.uniform(0, 28)
    i = int(t * SR); n = int(1.0 * SR)
    night[i:i + n] += 1.2 * low(rng.standard_normal(n), 400) * np.exp(-np.arange(n) / SR * 5)
    for k in range(25):
        clack(t + 0.1 + rng.uniform(0, 0.8), 0.3, night)
save("amb_night", loop(night))
print("ok")
