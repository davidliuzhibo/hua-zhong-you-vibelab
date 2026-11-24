package com.vrgames.huazhong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v0.4.0 (PICO 2026-11-23: 「水体可以考虑用网格和 shader 模拟材质与动态」): every water of every world is a wave surface at
 * life size and on the miniature, its frames loop without a seam, the waves lie down at the banks, a trough never dips
 * into the ground, and the grids stay light.
 */
class WaterTest {
    private fun region(w: World) = Dressing.regionOf(w)
    private fun scale(r: DoubleArray) = minOf(1.1 / (r[1] - r[0]), 0.78 / (r[3] - r[2])).coerceAtMost(1.0 / 30)

    @Test
    fun everyWaterIsAMovingSurfaceInBothModes() {
        var n = 0
        for (w in Worlds.ALL.values) {
            if (w.water.isEmpty()) continue
            val r = region(w)
            val k = scale(r)
            val full = Waves.surfaces(w, r, k, false)
            assertEquals("${w.id}: every water at life size", w.water.size, full.size)
            assertTrue("${w.id}: water on the miniature", Waves.surfaces(w, r, k, true).isNotEmpty())
            for (mini in listOf(false, true)) for (s in Waves.surfaces(w, r, k, mini)) {
                val f = Waves.field(s.water, w.water, s.spec, s.box, s.step, if (mini) 0.0 else 0.06, s.maxStep, edgeRamp = s.edgeRamp)
                assertEquals("${w.id}: frames", 16, s.spec.frames)
                assertTrue("${w.id} ${if (mini) "mini" else "full"}: ${f.triangles} triangles a frame", f.triangles in 40..16_000)
                // Frames differ (the waves move) and the loop closes: the frame after the last is the first.
                val a = f.at(0.0).first; val b = f.at(s.spec.period).first; val c = f.frame(s.spec.frames / 2).first
                var seam = 0.0; var moved = 0.0
                for (i in a.indices) { seam = maxOf(seam, abs(a[i] - b[i]).toDouble()); moved = maxOf(moved, abs(a[i] - c[i]).toDouble()) }
                assertTrue("${w.id}: seamless loop ($seam)", seam < 1e-4)
                assertTrue("${w.id}: the waves move ($moved)", moved > s.spec.amp * 0.3)
                // Never below the rest height (the ground under the water is at 0, the water's rest at its y).
                for (fr in 0 until s.spec.frames) {
                    val p = f.frame(fr).first
                    for (i in 1 until p.size step 3) assertTrue("${w.id}: trough at ${p[i]}", p[i] >= s.water.y - 1e-4)
                }
                n++
            }
        }
        assertTrue("surfaces: $n", n >= 16)
    }

    @Test
    fun wavesLieDownAtTheBanks() {
        val w = Worlds.of("qm2")!!
        val s = Waves.surfaces(w, region(w), scale(region(w)), false).first()
        val f = Waves.field(s.water, w.water, s.spec, s.box, s.step, 0.06, s.maxStep, edgeRamp = s.edgeRamp)
        // 汴河 runs along x: its banks are z = -21 and z = -6 (the quay before the player).
        val b = Waves.banks(s.water, w.water)
        assertTrue("both banks", b.z0 && b.z1)
        assertTrue("its far ends (90 m off) are not banks", !b.x0 && !b.x1)
        for (j in f.zs.indices) for (i in f.xs.indices) {
            val v = j * f.xs.size + i
            if (f.zs[j] == -6.0 || f.zs[j] == -21.0) assertEquals("still at the quay", 0.0, f.reachAt(v), 1e-9)
        }
        val mid = f.zs.indices.minBy { abs(f.zs[it] + 13.5) } * f.xs.size + f.xs.indices.minBy { abs(f.xs[it]) }
        assertEquals("open water mid-river", 1.0, f.reachAt(mid), 1e-6)
    }

    @Test
    fun joinedWatersHaveNoBankBetweenThem() {
        // 山居: the stream runs into the pool below the fall.
        val w = Worlds.of("ql3")!!
        val stream = w.water.first { !it.alongX }
        val b = Waves.banks(stream, w.water)
        assertTrue("the stream's sides are banks", b.x0 && b.x1)
        assertTrue("where it meets the pool is not", !b.z0)
    }

    @Test
    fun gridLinesReachTheEdgesAndOpenOut() {
        val l = Waves.lines(-90.0, 90.0, 0.0, 0.5, 0.06, 6.0)
        assertEquals(-90.0, l.first(), 1e-9)
        assertEquals(90.0, l.last(), 1e-9)
        assertTrue("fine by the player", l.toList().zipWithNext().filter { abs(it.first) < 5 }.all { it.second - it.first <= 0.5 + 1e-9 })
        assertTrue("coarse far off", l.toList().zipWithNext().any { it.second - it.first > 4 })
        assertTrue("few lines (${l.size})", l.size < 140)
    }

    @Test
    fun overlayPicturesArePowerOfTwoAndFadeOut() {
        fun pot(n: Int) = n > 0 && n and (n - 1) == 0
        assertTrue(pot(WaterArt.SHORE_W) && pot(WaterArt.SHORE_H) && pot(WaterArt.WAKE_W) && pot(WaterArt.WAKE_H))
        val shore = WaterArt.shore(0xFF8FA8A0.toInt())
        assertEquals("shore flip-book", 8, shore.size)
        fun alpha(px: IntArray, w: Int, x: Int, y: Int) = (px[y * w + x] ushr 24) and 0xFF
        val W = WaterArt.SHORE_W; val H = WaterArt.SHORE_H
        // The bank is the bottom row (v = 0); mid-river the top row: foam and shallows there, clear water here.
        assertTrue("foam at the bank", (0 until W).map { alpha(shore[0], W, it, H - 2) }.average() > 120)
        assertTrue("clear by mid-river", (0 until W).map { alpha(shore[0], W, it, 0) }.average() < 10)
        val wake = WaterArt.wake()
        assertEquals("wake flip-book", 8, wake.size)
        assertTrue("the wake fades out behind", (0 until WaterArt.WAKE_W).maxOf { alpha(wake[0], WaterArt.WAKE_W, it, 0) } < 20)
        assertTrue("frames move", !shore[0].contentEquals(shore[4]) && !wake[0].contentEquals(wake[4]))
    }
}
