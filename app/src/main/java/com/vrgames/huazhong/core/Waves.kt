package com.vrgames.huazhong.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Water as a moving surface (v0.4.0; PICO 2026-11-23: 「水体可以考虑用网格和 shader 模拟材质与动态」). Until 0.3.3 every river and
 * lake was one flat quad with a flip-book of ripples. Now each is a grid whose height is a sum of a few travelling waves,
 * baked into [Spec.frames] frames over one [Spec.period] and played in a loop like the walk cycles (each wave goes a
 * whole number of its own lengths per loop, so the loop has no seam). The waves die down toward the banks (water
 * laps, not heaves, at a quay) and far off (where the grid is coarse). The surface's rest height rises by the local
 * wave height above [base], so a trough never dips into the ground under it. Pure maths: the stage turns frames into
 * meshes (render/WaterView).
 */
object Waves {
    /** A wave: heading [deg] off the flow (along u), length [len] m, height [amp] m, [cycles] lengths per loop. */
    data class Wave(val deg: Double, val len: Double, val amp: Double, val cycles: Int, val phase: Double)

    /**
     * [ramp]: metres from a bank over which the waves grow to full height. [fade0]..[fade1]: distance from the player
     * over which they die out (the grid is coarse there).
     */
    data class Spec(val waves: List<Wave>, val period: Double, val frames: Int, val ramp: Double, val fade0: Double, val fade1: Double) {
        val amp: Double get() = waves.sumOf { abs(it.amp) }
    }

    /** Life size: a river's chop (a few centimetres), and a lake's longer, lower swell. */
    val RIVER = Spec(
        listOf(Wave(8.0, 3.6, 0.022, 1, 0.0), Wave(-24.0, 2.3, 0.015, 1, 1.7), Wave(37.0, 1.5, 0.009, 2, 4.1), Wave(-55.0, 0.95, 0.006, 2, 2.6)),
        period = 3.2, frames = 16, ramp = 1.6, fade0 = 28.0, fade1 = 48.0,
    )
    val LAKE = Spec(
        listOf(Wave(-12.0, 5.5, 0.026, 1, 0.3), Wave(21.0, 3.4, 0.016, 1, 2.2), Wave(-47.0, 2.1, 0.01, 2, 5.0), Wave(66.0, 1.3, 0.006, 2, 1.1)),
        period = 4.0, frames = 16, ramp = 2.5, fade0 = 30.0, fade1 = 55.0,
    )

    /**
     * The miniature's (v0.4.0): the same water seen at about 1:45 on a table. Real ripples there would be a hundredth of
     * a millimetre; a diorama's resin water shows its ripples about a millimetre high, a few centimetres apart. In the table's own
     * metres; scaled into the world by 1 / [k] (the miniature's scale).
     */
    fun mini(lake: Boolean, k: Double): Spec {
        val s = 1.0 / k
        val w = if (lake) listOf(Wave(-12.0, 0.085, 0.0006, 1, 0.3), Wave(23.0, 0.052, 0.0004, 1, 2.2), Wave(-49.0, 0.031, 0.00025, 2, 5.0))
        else listOf(Wave(8.0, 0.07, 0.0005, 1, 0.0), Wave(-26.0, 0.045, 0.00035, 1, 1.7), Wave(39.0, 0.028, 0.00025, 2, 4.1))
        return Spec(w.map { it.copy(len = it.len * s, amp = it.amp * s) }, if (lake) 4.0 else 3.2, 16, 0.012 * s, 1e9, 2e9)
    }

    /** Which edges of a water box are banks: x0 (left), x1 (right), z0 (far), z1 (near). */
    data class Banks(val x0: Boolean, val x1: Boolean, val z0: Boolean, val z1: Boolean)

    /**
     * The banks of [w] among [all] the world's waters: an edge is a bank unless another water carries on past it, or
     * it is out past [reach] metres from the player (the lakes' far shores are the panorama's).
     */
    fun banks(w: Water, all: List<Water>, reach: Double = 70.0): Banks {
        val xa = min(w.x0, w.x1); val xb = max(w.x0, w.x1); val za = min(w.z0, w.z1); val zb = max(w.z0, w.z1)
        fun joined(x: Double?, z: Double?): Boolean = all.any { o ->
            if (o === w) return@any false
            val oxa = min(o.x0, o.x1); val oxb = max(o.x0, o.x1); val oza = min(o.z0, o.z1); val ozb = max(o.z0, o.z1)
            if (x != null) x >= oxa - 0.05 && x <= oxb + 0.05 && min(zb, ozb) - max(za, oza) > 0.5
            else min(xb, oxb) - max(xa, oxa) > 0.5 && z!! >= oza - 0.05 && z <= ozb + 0.05
        }
        fun near(x0: Double, z0: Double, x1: Double, z1: Double) = segDist(0.0, 0.0, x0, z0, x1, z1) < reach
        return Banks(
            x0 = !joined(xa, null) && near(xa, za, xa, zb),
            x1 = !joined(xb, null) && near(xb, za, xb, zb),
            z0 = !joined(null, za) && near(xa, za, xb, za),
            z1 = !joined(null, zb) && near(xa, zb, xb, zb),
        )
    }

    /**
     * Grid lines from [a] to [b]: [step] m apart around [focus] (the player's side), opening out by [growth] per line
     * with distance, at most [maxStep]. Ends exactly at a and b.
     */
    fun lines(a: Double, b: Double, focus: Double, step: Double, growth: Double, maxStep: Double): DoubleArray {
        val f = focus.coerceIn(a, b)
        val up = ArrayList<Double>()
        var x = f
        while (x < b - 1e-6) { x += min(maxStep, max(step, (x - f) * growth)); up += min(x, b) }
        val down = ArrayList<Double>()
        x = f
        while (x > a + 1e-6) { x -= min(maxStep, max(step, (f - x) * growth)); down += max(x, a) }
        // Drop a sliver at either end (a line closer than a fifth of a step to the end).
        val out = (down.reversed() + f + up).distinct().toMutableList()
        if (out.size > 2 && out[1] - out[0] < step * 0.2) out.removeAt(1)
        if (out.size > 2 && out[out.size - 1] - out[out.size - 2] < step * 0.2) out.removeAt(out.size - 2)
        return out.toDoubleArray()
    }

    /**
     * A surface's grid (lines [xs] across x, [zs] across z; vertex j * xs.size + i) and its waves: [at] gives the
     * positions (x, y, z) and normals at any time; [frames] / [normals] are the loop's baked frames.
     */
    class Field(val xs: DoubleArray, val zs: DoubleArray, val spec: Spec, private val w: Water, private val base: Double, private val kmap: DoubleArray) {
        val vertices get() = xs.size * zs.size
        val triangles get() = 2 * (xs.size - 1) * (zs.size - 1)
        /** How much of the waves reaches each vertex (0 at a bank, 1 in open water). */
        fun reachAt(v: Int) = kmap[v]

        fun at(t: Double, p: FloatArray = FloatArray(vertices * 3), nn: FloatArray = FloatArray(vertices * 3)): Pair<FloatArray, FloatArray> {
            val s = DoubleArray(3)
            for (j in zs.indices) for (i in xs.indices) {
                val v = j * xs.size + i
                val k = kmap[v]
                sample(spec, w.alongX, w.flow, xs[i], zs[j], t, k, s)
                p[v * 3] = xs[i].toFloat()
                // Lifted by the local wave height, so the trough stays at the rest height or above.
                p[v * 3 + 1] = (base + spec.amp * k + s[0]).toFloat()
                p[v * 3 + 2] = zs[j].toFloat()
                val nx = -s[1]; val nz = -s[2]
                val l = sqrt(nx * nx + 1 + nz * nz)
                nn[v * 3] = (nx / l).toFloat(); nn[v * 3 + 1] = (1 / l).toFloat(); nn[v * 3 + 2] = (nz / l).toFloat()
            }
            return p to nn
        }

        private val baked: List<Pair<FloatArray, FloatArray>> by lazy { (0 until spec.frames).map { at(spec.period * it / spec.frames) } }
        val frames: List<FloatArray> get() = baked.map { it.first }
        val normals: List<FloatArray> get() = baked.map { it.second }
        fun frame(f: Int) = baked[f]
    }

    /** How much of the waves' height reaches (x, z): grown from the banks, faded far off. */
    fun reach(spec: Spec, box: DoubleArray, banks: Banks, x: Double, z: Double, edgeRamp: Double = spec.ramp): Double {
        val (xa, xb, za, zb) = box.toList()
        var d = Double.MAX_VALUE
        // Banks grow the waves over spec.ramp; any other edge of the box (a miniature's cut) over edgeRamp.
        d = min(d, (x - xa) / (if (banks.x0) spec.ramp else edgeRamp))
        d = min(d, (xb - x) / (if (banks.x1) spec.ramp else edgeRamp))
        d = min(d, (z - za) / (if (banks.z0) spec.ramp else edgeRamp))
        d = min(d, (zb - z) / (if (banks.z1) spec.ramp else edgeRamp))
        val b = smooth(d.coerceIn(0.0, 1.0))
        val r = hypot(x, z)
        val f = 1 - smooth(((r - spec.fade0) / (spec.fade1 - spec.fade0)).coerceIn(0.0, 1.0))
        return b * f
    }

    /** The surface's height above [base] and its slope (dh/dx, dh/dz) at (x, z), time [t]. */
    fun sample(spec: Spec, alongX: Boolean, flow: Double, x: Double, z: Double, t: Double, k: Double, out: DoubleArray) {
        var h = 0.0; var gx = 0.0; var gz = 0.0
        val sgn = if (flow < 0) -1.0 else 1.0
        for (w in spec.waves) {
            val r = Math.toRadians(w.deg)
            // Heading in (u, v) = (along the flow, across it), with the flow's sign; then into x, z.
            val du = cos(r) * sgn
            val dv = sin(r)
            val dx = if (alongX) du else dv
            val dz = if (alongX) dv else du
            val kk = 2 * PI / w.len
            val ph = kk * (dx * x + dz * z) - 2 * PI * w.cycles * t / spec.period + w.phase
            val a = w.amp * k
            h += a * sin(ph)
            gx += a * kk * dx * cos(ph)
            gz += a * kk * dz * cos(ph)
        }
        out[0] = h; out[1] = gx; out[2] = gz
    }

    /**
     * The frames of water [w] (cut to [box] = x0, x1, z0, z1 if given) at rest height [base]: grid lines [step] m apart
     * near the player at (0, 0), opening out to [maxStep]. [edgeRamp]: how the waves die toward a cut edge (not a bank).
     */
    fun field(w: Water, all: List<Water>, spec: Spec, box: DoubleArray? = null, step: Double = 0.5, growth: Double = 0.06, maxStep: Double = 6.0, base: Double = w.y, edgeRamp: Double = spec.ramp, focus: Pair<Double, Double> = 0.0 to 0.0): Field {
        val xa = box?.get(0) ?: min(w.x0, w.x1); val xb = box?.get(1) ?: max(w.x0, w.x1)
        val za = box?.get(2) ?: min(w.z0, w.z1); val zb = box?.get(3) ?: max(w.z0, w.z1)
        val b0 = banks(w, all)
        // A cut edge of the miniature is not the water's own bank.
        val banks = Banks(
            b0.x0 && xa <= min(w.x0, w.x1) + 1e-6, b0.x1 && xb >= max(w.x0, w.x1) - 1e-6,
            b0.z0 && za <= min(w.z0, w.z1) + 1e-6, b0.z1 && zb >= max(w.z0, w.z1) - 1e-6,
        )
        val xs = lines(xa, xb, focus.first, step, growth, maxStep)
        val zs = lines(za, zb, focus.second, step, growth, maxStep)
        val bx = doubleArrayOf(xa, xb, za, zb)
        val kmap = DoubleArray(xs.size * zs.size)
        for (j in zs.indices) for (i in xs.indices) kmap[j * xs.size + i] = reach(spec, bx, banks, xs[i], zs[j], edgeRamp)
        return Field(xs, zs, spec, w, base, kmap)
    }

    /**
     * A band along a bank (v0.4.0 shore): [pos] per frame (x, y, z per vertex, riding the waves a little above them),
     * [uv] (u along the bank in [tile]-metre repeats, v from the bank 0.02 to 0.98 inward), [idx] triangles, [dir] the
     * bank's direction (x, z) for the tangent.
     */
    class Band(val pos: List<FloatArray>, val uv: FloatArray, val idx: IntArray, val dir: DoubleArray)

    /**
     * The shore bands of water [w] (cut to [box]) for each of [spec]'s frames: along every bank of its own, [width] m
     * inward (at most 38 % of the water's breadth), on the surface plus [lift].
     */
    fun bands(w: Water, all: List<Water>, spec: Spec, box: DoubleArray?, width: Double, mini: Boolean, lift: Double, edgeRamp: Double, tile: Double = 6.0): List<Band> {
        val xa = box?.get(0) ?: min(w.x0, w.x1); val xb = box?.get(1) ?: max(w.x0, w.x1)
        val za = box?.get(2) ?: min(w.z0, w.z1); val zb = box?.get(3) ?: max(w.z0, w.z1)
        val b = banks(w, all)
        val own = Banks(
            b.x0 && xa <= min(w.x0, w.x1) + 1e-6, b.x1 && xb >= max(w.x0, w.x1) - 1e-6,
            b.z0 && za <= min(w.z0, w.z1) + 1e-6, b.z1 && zb >= max(w.z0, w.z1) - 1e-6,
        )
        // Each bank: start, end, the inward direction.
        val edges = ArrayList<Triple<DoubleArray, DoubleArray, DoubleArray>>()
        if (own.x0) edges += Triple(doubleArrayOf(xa, za), doubleArrayOf(xa, zb), doubleArrayOf(1.0, 0.0))
        if (own.x1) edges += Triple(doubleArrayOf(xb, zb), doubleArrayOf(xb, za), doubleArrayOf(-1.0, 0.0))
        if (own.z0) edges += Triple(doubleArrayOf(xb, za), doubleArrayOf(xa, za), doubleArrayOf(0.0, 1.0))
        if (own.z1) edges += Triple(doubleArrayOf(xa, zb), doubleArrayOf(xb, zb), doubleArrayOf(0.0, -1.0))
        val wd = min(width, min(xb - xa, zb - za) * 0.38)
        val bx = doubleArrayOf(xa, xb, za, zb)
        val out = ArrayList<Band>()
        val smp = DoubleArray(3)
        for ((s, t, inw) in edges) {
            val len = hypot(t[0] - s[0], t[1] - s[1])
            if (len < 0.5) continue
            val dx = (t[0] - s[0]) / len; val dz = (t[1] - s[1]) / len
            // Segments along the bank: fine near the player, longer far off; rows denser at the bank (the foam).
            val near = ((-s[0]) * dx + (-s[1]) * dz).coerceIn(0.0, len)
            val ts = lines(0.0, len, near, if (mini) wd * 0.6 else 0.8, if (mini) 0.0 else 0.08, if (mini) wd * 0.6 else 8.0)
            val rows = doubleArrayOf(0.0, 0.06, 0.15, 0.3, 0.5, 0.75, 1.0)
            val n = ts.size
            val xz = DoubleArray(rows.size * n * 2)
            val uv = FloatArray(rows.size * n * 2)
            val kk = DoubleArray(rows.size * n)
            for ((j, r) in rows.withIndex()) for ((i, u) in ts.withIndex()) {
                val v = j * n + i
                val x = s[0] + dx * u + inw[0] * wd * r
                val z = s[1] + dz * u + inw[1] * wd * r
                xz[v * 2] = x; xz[v * 2 + 1] = z
                kk[v] = reach(spec, bx, own, x, z, edgeRamp)
                uv[v * 2] = (u / tile).toFloat(); uv[v * 2 + 1] = (0.02 + 0.96 * r).toFloat()
            }
            val frames = (0 until spec.frames).map { f ->
                val tt = spec.period * f / spec.frames
                val p = FloatArray(rows.size * n * 3)
                for (v in 0 until rows.size * n) {
                    sample(spec, w.alongX, w.flow, xz[v * 2], xz[v * 2 + 1], tt, kk[v], smp)
                    p[v * 3] = xz[v * 2].toFloat()
                    p[v * 3 + 1] = (w.y + spec.amp * kk[v] + smp[0] + lift).toFloat()
                    p[v * 3 + 2] = xz[v * 2 + 1].toFloat()
                }
                p
            }
            val idx = IntArray((rows.size - 1) * (n - 1) * 6)
            var q = 0
            for (j in 0 until rows.size - 1) for (i in 0 until n - 1) {
                val a = j * n + i
                idx[q++] = a; idx[q++] = a + 1; idx[q++] = a + n + 1; idx[q++] = a; idx[q++] = a + n + 1; idx[q++] = a + n
            }
            out += Band(frames, uv, idx, doubleArrayOf(dx, dz))
        }
        return out
    }

    /** One surface to build: [water] (cut to [box] on the miniature) with its waves and grid. */
    data class Surface(val water: Water, val spec: Spec, val box: DoubleArray?, val step: Double, val maxStep: Double, val edgeRamp: Double, val shoreWidth: Double, val lift: Double)

    /**
     * The surfaces of world [w]: at life size each water whole (lines half a metre apart round the player, opening out
     * to 6 m far off); as the miniature (scale [k], its part [region] = x0, x1, z0, z1) each water's part on the board,
     * a grid of about a centimetre on the table, its waves at the diorama's scale.
     */
    fun surfaces(w: World, region: DoubleArray, k: Double, mini: Boolean): List<Surface> = w.water.mapNotNull { wt ->
        val lake = isLake(wt)
        if (!mini) return@mapNotNull Surface(wt, if (lake) LAKE else RIVER, null, 0.5, 6.0, (if (lake) LAKE else RIVER).ramp, if (lake) 6.0 else 4.0, 0.012)
        val a = max(min(wt.x0, wt.x1), region[0]); val b = min(max(wt.x0, wt.x1), region[1])
        val c = max(min(wt.z0, wt.z1), region[2]); val d = min(max(wt.z0, wt.z1), region[3])
        if (a >= b || c >= d) return@mapNotNull null
        val step = 0.011 / k
        Surface(wt, mini(lake, k), doubleArrayOf(a, b, c, d), step, step, 0.008 / k, 0.05 / k, 0.001 / k)
    }

    /** Which frame shows at [t] seconds. */
    fun frameAt(spec: Spec, t: Double): Int = (floor(((t / spec.period) % 1.0 + 1.0) % 1.0 * spec.frames).toInt()).coerceIn(0, spec.frames - 1)

    /** Whether a world's water is a lake (its box wider than 60 m both ways) rather than a river. */
    fun isLake(w: Water) = abs(w.x1 - w.x0) > 60 && abs(w.z1 - w.z0) > 60

    private fun smooth(x: Double) = x * x * (3 - 2 * x)

    private fun segDist(px: Double, pz: Double, x0: Double, z0: Double, x1: Double, z1: Double): Double {
        val dx = x1 - x0; val dz = z1 - z0
        val l2 = dx * dx + dz * dz
        val t = if (l2 > 0) (((px - x0) * dx + (pz - z0) * dz) / l2).coerceIn(0.0, 1.0) else 0.0
        return hypot(px - (x0 + t * dx), pz - (z0 + t * dz))
    }
}
