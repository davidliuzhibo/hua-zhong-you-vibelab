package com.vrgames.huazhong.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * v0.3.1 (device 2026-11-18: pinching the donkeys did nothing, in the miniature or inside): from where the player's eyes
 * are — over the miniature, and standing in the painting — a look at the thing to find reaches its pinch box before any
 * other box. Boxes as the game makes them ([PinchBox]); movers sampled over two minutes (a mover may pass behind a house
 * for a while, but must be in the clear most of the time).
 */
class PinchReachTest {
    private val sizes: Map<String, DoubleArray> = File("src/main/assets/scenes/sizes.txt").readLines().mapNotNull { l ->
        val p = l.trim().split(Regex("\\s+"))
        if (p.size >= 4) p[0] to doubleArrayOf(p[1].toDouble(), p[2].toDouble(), p[3].toDouble()) else null
    }.toMap()
    private val fwd = mapOf("qm_cargo" to -90.0, "qm_bigboat" to -90.0, "qm_canopy" to -90.0, "qm_rowboat" to -90.0, "ql_fishboat" to -90.0, "qm_oxcart" to -90.0, "qm_sedan" to 90.0)

    /** A box: centre of its base, size in its own frame, yaw (degrees, as the game's). */
    private class Obb(val name: String, val x: Double, val y: Double, val z: Double, val w: Double, val h: Double, val d: Double, val yaw: Double, val spot: Boolean)

    private fun pieceBox(p: Piece, spot: Boolean): Obb? {
        val s = sizes[p.m] ?: return null
        val b = PinchBox.of(p.m, s, spot)
        return Obb(p.m, p.x, p.y, p.z, b[0] * p.scale, b[1] * p.scale, b[2] * p.scale, p.yaw, spot)
    }

    private fun moverBox(m: Mover, t: Double): Obb? {
        val s = sizes[m.m] ?: return null
        val b = PinchBox.of(m.m, s, m.find)
        val turned = abs(fwd[m.m] ?: 0.0) == 90.0
        val a = m.at(t)
        return Obb(m.m, a[0], a[3], a[1], if (turned) b[2] else b[0], b[1], if (turned) b[0] else b[2], a[2], m.find)
    }

    /** Where the ray o + s·dir enters [b] (s ≥ 0), or null. */
    private fun enter(b: Obb, o: DoubleArray, dir: DoubleArray): Double? {
        val r = Math.toRadians(b.yaw)
        // Into the box frame: inverse of local→world (wx = lx cos + lz sin, wz = -lx sin + lz cos).
        fun loc(x: Double, z: Double) = doubleArrayOf(x * cos(r) - z * sin(r), x * sin(r) + z * cos(r))
        val p = loc(o[0] - b.x, o[2] - b.z)
        val q = loc(dir[0], dir[2])
        val ox = doubleArrayOf(p[0], o[1] - (b.y + b.h / 2), p[1])
        val dv = doubleArrayOf(q[0], dir[1], q[1])
        val half = doubleArrayOf(b.w / 2, b.h / 2, b.d / 2)
        var t0 = 0.0
        var t1 = Double.MAX_VALUE
        for (i in 0..2) {
            if (abs(dv[i]) < 1e-9) {
                if (abs(ox[i]) > half[i]) return null
            } else {
                var a = (-half[i] - ox[i]) / dv[i]
                var c = (half[i] - ox[i]) / dv[i]
                if (a > c) { val tmp = a; a = c; c = tmp }
                t0 = max(t0, a)
                t1 = min(t1, c)
                if (t0 > t1) return null
            }
        }
        return t0
    }

    private fun centre(b: Obb) = doubleArrayOf(b.x, b.y + b.h * 0.45, b.z)

    /** Whether a look from [eye] at [target] meets it first among [all]. */
    private val blockers = HashMap<String, Int>()

    private fun reaches(eye: DoubleArray, target: Obb, all: List<Obb>): Boolean {
        val c = centre(target)
        val dir = doubleArrayOf(c[0] - eye[0], c[1] - eye[1], c[2] - eye[2])
        val tT = enter(target, eye, dir) ?: return false
        val b = all.firstOrNull { it !== target && !it.spot && (enter(it, eye, dir)?.let { s -> s < tT - 1e-6 } ?: false) }
        if (b != null) { val key = "${b.name}@(%.1f,%.1f)".format(b.x, b.z); blockers[key] = (blockers[key] ?: 0) + 1 }
        return b == null
    }

    @Test
    fun theThingToFindCanBePinchedFromWhereThePlayerLooks() {
        val bad = ArrayList<String>()
        for (w in Worlds.ALL.values) {
            if (w.pieces.none { it.find } && w.movers.none { it.find }) continue
            val r = Dressing.regionOf(w)
            val k = min(min(1.1 / (r[1] - r[0]), 0.78 / (r[3] - r[2])), 1.0 / 30)
            fun inRegion(x: Double, z: Double) = x >= r[0] && x <= r[1] && z >= r[2] && z <= r[3]
            val eyes = mapOf(
                "miniature" to doubleArrayOf((r[0] + r[1]) / 2, 0.45 / k, r[3] + 0.42 / k),
                "inside" to doubleArrayOf(0.0, 1.6, 0.0),
            )
            val pieces = w.pieces + Dressing.extras(w) { m -> sizes[m]?.let { it[0] to it[2] } }
            for ((where, eye) in eyes) {
                val mini = where == "miniature"
                val still = pieces.filter { !mini || inRegion(it.x, it.z) }.mapNotNull { pieceBox(it, it.find) }
                for (b in still.filter { it.spot }) if (!reaches(eye, b, still)) { bad += "${w.id} $where: ${b.name} hidden by ${blockers.keys}"; }
                blockers.clear()
                val finds = w.movers.filter { it.find }
                if (finds.isEmpty()) continue
                var seen = 0
                var n = 0
                var t = 0.0
                while (t < 120.0) {
                    val ms = w.movers.filter { m -> val a = m.at(t); !mini || inRegion(a[0], a[1]) }.mapNotNull { moverBox(it, t) }
                    val all = still + ms
                    if (ms.filter { it.spot }.any { reaches(eye, it, all) }) seen++
                    n++
                    t += 2.0
                }
                if (seen < n * 0.8) bad += "${w.id} $where: moving thing to find in the clear only $seen/$n of the time; in the way: " + blockers.entries.sortedByDescending { it.value }.take(6).joinToString { "${it.key}×${it.value}" }
                blockers.clear()
            }
        }
        if (bad.isNotEmpty()) File("build/reach-problems.txt").writeText(bad.joinToString("\n"))
        assertTrue("hidden:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
