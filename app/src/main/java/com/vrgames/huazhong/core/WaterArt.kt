package com.vrgames.huazhong.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** The water's overlay pictures (v0.4.0, render/WaterView): pure pixels, so the tests can check and dump them. */
object WaterArt {
    const val SHORE_W = 256
    const val SHORE_H = 64
    const val WAKE_W = 128
    const val WAKE_H = 256

    private fun ch(v: Double) = (v.coerceIn(0.0, 1.0) * 255).toInt()
    private fun argb(a: Double, r: Double, g: Double, b: Double) = (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    private fun smooth(e0: Double, e1: Double, x: Double): Double { val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    /**
     * The shore band's flip-book ([n] frames, [SHORE_W] × [SHORE_H] ARGB, u along the bank repeating, v from the bank (0) inward):
     * shallow water of [shallow] fading out by mid-river, and a broken foam line lapping in and out at the bank.
     */
    fun shore(shallow: Int, n: Int = 8): List<IntArray> {
        val W = SHORE_W; val H = SHORE_H
        val sr = ((shallow shr 16) and 0xFF) / 255.0; val sg = ((shallow shr 8) and 0xFF) / 255.0; val sb = (shallow and 0xFF) / 255.0
        return List(n) { f ->
            val ph = 2 * PI * f / n
            val px = IntArray(W * H)
            for (y in 0 until H) for (x in 0 until W) {
                val u = x.toDouble() / W
                val v = (y + 0.5) / H
                // Periodic in u (whole cycles across the tile): the foam's broken edge and its lapping.
                val wob = 0.45 * sin(2 * PI * 3 * u + 0.7) + 0.3 * sin(2 * PI * 7 * u + ph + 1.9) + 0.25 * sin(2 * PI * 13 * u - ph * 2 + 0.4)
                val edge = 0.075 + 0.03 * sin(ph) + 0.022 * wob
                val foam = (1 - smooth(edge - 0.035, edge + 0.01, v)) * (0.62 + 0.38 * (0.5 + 0.5 * sin(2 * PI * 9 * u + ph + 3 * v * 20)))
                // A second, fainter line further out, half a cycle behind (the wash going back).
                val e2 = edge + 0.09 + 0.02 * sin(ph + PI)
                val foam2 = 0.35 * exp(-((v - e2) / 0.018).let { it * it }) * (0.5 + 0.5 * sin(2 * PI * 5 * u - ph + 1.3))
                val fa = (foam + foam2).coerceIn(0.0, 0.85)
                val sa = 0.5 * (1 - smooth(0.12, 1.0, v))
                val a = fa + sa * (1 - fa)
                val k = if (a > 1e-4) fa / a else 0.0
                px[(H - 1 - y) * W + x] = argb(a, 0.93 * k + sr * (1 - k), 0.96 * k + sg * (1 - k), 0.94 * k + sb * (1 - k))
            }
            // v = 0 (the bank) is the bitmap's bottom row: a quad's uv (0, 0) shows the picture's lower left (开发经验清单 §9).
            px
        }
    }

    /**
     * A wake's flip-book ([n] frames, [WAKE_W] × [WAKE_H] ARGB; u across, v from the bow back): Kelvin's two diverging foam arms
     * with their crests running back, faint cross-waves between, and the churned trail behind the stern.
     */
    fun wake(n: Int = 8): List<IntArray> {
        val W = WAKE_W; val H = WAKE_H
        return List(n) { f ->
            val ph = 2 * PI * f / n
            val px = IntArray(W * H)
            for (y in 0 until H) for (x in 0 until W) {
                val u = (x + 0.5) / W * 2 - 1
                val v = (y + 0.5) / H
                val arm = v * 0.96
                val d = abs(abs(u) - arm)
                val fade = (1 - v).let { it * it }
                val crest = 0.62 + 0.38 * sin(2 * PI * (v * 9) - ph)
                val a1 = exp(-(d / (0.018 + 0.06 * v)).let { it * it }) * fade * crest * smooth(0.0, 0.05, v)
                val inside = if (abs(u) < arm) 1.0 else 0.0
                val cross = 0.16 * inside * fade * (0.5 + 0.5 * sin(2 * PI * (v * 16) - 2 * ph)) * (1 - abs(u) / (arm + 1e-3)).coerceIn(0.0, 1.0)
                // The trail: from the stern (about a fifth of the way back) on, along the middle.
                val trail = 0.42 * smooth(0.14, 0.24, v) * exp(-(u / (0.05 + 0.12 * v)).let { it * it }) * (1 - v) *
                    (0.6 + 0.4 * sin(2 * PI * (v * 23 + 0.3 * sin(u * 9)) - 3 * ph))
                val a = (a1 + cross + trail).coerceIn(0.0, 0.9)
                px[(H - 1 - y) * W + x] = argb(a, 0.95, 0.97, 0.96)
            }
            px
        }
    }
}
