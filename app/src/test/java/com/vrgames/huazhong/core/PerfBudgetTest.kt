package com.vrgames.huazhong.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.4.0 (PICO 2026-11-23: 「运行时帧数偏低……用高模烘焙低模……降低性能开销」; device 2026-11-18: 30–50 fps): what each
 * section draws, at life size and as the miniature, against 0.3.3 (counted the same way from its own models' table,
 * tools/tris_v033.txt). Prints the before/after table for the changelog.
 */
class PerfBudgetTest {
    private val scenes = File("src/main/assets/scenes")
    private val sizes: Map<String, DoubleArray> = File(scenes, "sizes.txt").readLines().mapNotNull { l ->
        val p = l.trim().split(Regex("\\s+"))
        if (p.size >= 4) p[0] to doubleArrayOf(p[1].toDouble(), p[2].toDouble(), p[3].toDouble()) else null
    }.toMap()
    private val now = Perf.parse(File(scenes, "tris.txt").readLines())
    private val before = Perf.parse(File(scenes, "tris_v033.txt").readLines())

    @Test
    fun everySectionDrawsAboutHalfOf033() {
        val sb = StringBuilder("section | life size 0.3.3 → 0.4.0 | miniature 0.3.3 → 0.4.0\n")
        var sumOld = 0L; var sumNew = 0L; var sumOldM = 0L; var sumNewM = 0L
        val checks = ArrayList<() -> Unit>()
        for (w in Worlds.ALL.values) {
            val extra = Dressing.extras(w) { m -> sizes[m]?.let { it[0] to it[2] } }
            val r = Dressing.regionOf(w)
            val k = minOf(1.1 / (r[1] - r[0]), 0.78 / (r[3] - r[2])).coerceAtMost(1.0 / 30)
            val oldFull = Perf.count(w, extra, r, before, false, Perf.V033)
            val oldMini = Perf.count(w, extra, r, before, true, Perf.V033)
            val newFull = Perf.count(w, extra, r, now, false, Perf.V040) + Perf.water(w, r, k, false)
            val newMini = Perf.count(w, extra, r, now, true, Perf.V040) + Perf.water(w, r, k, true)
            sb.append("${w.id} | ${oldFull.tris} → ${newFull.tris} (${100 * newFull.tris / oldFull.tris}%) | ${oldMini.tris} → ${newMini.tris} (${100 * newMini.tris / oldMini.tris}%)\n")
            checks += { assertTrue("${w.id} life size: ${newFull.tris} vs ${oldFull.tris}", newFull.tris <= oldFull.tris * FULL_MAX) }
            checks += { assertTrue("${w.id} miniature: ${newMini.tris} vs ${oldMini.tris}", newMini.tris <= oldMini.tris * MINI_MAX) }
            checks += { assertTrue("${w.id}: at most 300k triangles anywhere", maxOf(newFull.tris, newMini.tris) <= 300_000) }
            sumOld += oldFull.tris; sumNew += newFull.tris; sumOldM += oldMini.tris; sumNewM += newMini.tris
        }
        sb.append("all | $sumOld → $sumNew (${100 * sumNew / sumOld}%) | $sumOldM → $sumNewM (${100 * sumNewM / sumOldM}%)")
        println(sb)
        checks.forEach { it() }
        assertTrue("all sections at life size: $sumNew vs $sumOld", sumNew <= sumOld * 0.5)
        assertTrue("all miniatures: $sumNewM vs $sumOldM", sumNewM <= sumOldM * 0.65)
    }

    companion object {
        /** Each section at life size: at most 56 % of 0.3.3 (all together at most half). */
        const val FULL_MAX = 0.56
        /**
         * The miniature showed light versions already in 0.3.3 (a quarter of the full models), so it drops less; the
         * country road (汴京郊野) least, being mostly trees, whose leaves and petals do not decimate further.
         * All together at most 65 %.
         */
        const val MINI_MAX = 0.82
    }
}
