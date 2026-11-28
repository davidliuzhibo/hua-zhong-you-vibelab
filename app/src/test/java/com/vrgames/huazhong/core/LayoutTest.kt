package com.vrgames.huazhong.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * v0.3.1 (device 2026-11-18, 画中游: 「人和驴在行走中，会直接穿过房子……驴队也是直接穿过竹子做成的矮篱笆墙……人物也会从船身
 * 穿过」): nothing that moves goes through anything that stands, nor through another mover; walkers keep out of the water.
 * Footprints are the models' prepared sizes (assets scenes/sizes.txt) turned by their yaw.
 */
class LayoutTest {
    private val sizes: Map<String, DoubleArray> = File("src/main/assets/scenes/sizes.txt").readLines().mapNotNull { l ->
        val p = l.trim().split(Regex("\\s+"))
        if (p.size >= 4) p[0] to doubleArrayOf(p[1].toDouble(), p[2].toDouble(), p[3].toDouble()) else null
    }.toMap()

    /** Models turned a quarter in their holders (as WorldView.FWD): their x and z swap for a mover. */
    private val fwd = mapOf("qm_cargo" to -90.0, "qm_bigboat" to -90.0, "qm_canopy" to -90.0, "qm_rowboat" to -90.0, "ql_fishboat" to -90.0, "qm_oxcart" to -90.0, "qm_sedan" to 90.0)

    /** Walked on or passed under: not obstacles. */
    private val bridges = setOf("qm_bridge", "ql_longbridge")

    private fun isTree(m: String) = m.startsWith("t_") || m == "ql_pine"
    private fun isBoat(mv: Mover) = mv.boat

    /** A mover's half width across its way, and half length along it. */
    private fun moverHalf(mv: Mover): Pair<Double, Double> {
        val s = sizes[mv.m] ?: doubleArrayOf(1.0, 1.0, 1.0)
        val turned = abs(fwd[mv.m] ?: 0.0) == 90.0
        val across = (if (turned) s[2] else s[0]) / 2
        val along = (if (turned) s[0] else s[2]) / 2
        return across to along
    }

    /** A standing piece's footprint: centre, half extents (its own x, z) and yaw; trees count their trunk and lower boughs. */
    private data class Box(val m: String, val x: Double, val z: Double, val hx: Double, val hz: Double, val yaw: Double, val round: Boolean = false)

    private fun boxes(w: World, withExtras: Boolean): List<Box> {
        val restore = listOfNotNull(w.restore?.let { Piece(it.m, it.x, it.z, it.yaw, it.y) })
        val all = (if (withExtras) w.pieces + Dressing.extras(w) { m -> sizes[m]?.let { it[0] to it[2] } } else w.pieces) + restore
        return all.filter { it.m !in bridges && it.y < 0.5 }.mapNotNull { p ->
            val s = sizes[p.m] ?: return@mapNotNull null
            val k = p.scale * if (isTree(p.m)) 0.3 else 1.0
            // Peaks and hills are cones: their foot an ellipse inside the box, a little in from its edge.
            val round = p.m.startsWith("ql_mount") || p.m == "ql_hill"
            val kk = if (round) k * 0.85 else k
            Box(p.m, p.x, p.z, s[0] / 2 * kk, s[2] / 2 * kk, p.yaw, round)
        }
    }

    /** Distance from (x, z) to [b] (0 inside). */
    private fun dist(b: Box, x: Double, z: Double): Double {
        val r = Math.toRadians(b.yaw)
        val dx = x - b.x
        val dz = z - b.z
        // Into the box's own frame (inverse of local→world: wx = lx cos + lz sin, wz = -lx sin + lz cos).
        val lx = dx * cos(r) - dz * sin(r)
        val lz = dx * sin(r) + dz * cos(r)
        if (b.round) {
            // Roughly: how far outside the ellipse, in metres along its smaller radius.
            val e = hypot(lx / b.hx, lz / b.hz)
            return max(0.0, (e - 1) * min(b.hx, b.hz))
        }
        val ox = max(abs(lx) - b.hx, 0.0)
        val oz = max(abs(lz) - b.hz, 0.0)
        return hypot(ox, oz)
    }

    private fun samples(mv: Mover): List<Pair<Double, Double>> {
        val pts = if (mv.loop && !mv.reset) mv.path + mv.path.first() else mv.path
        val out = ArrayList<Pair<Double, Double>>()
        for (i in 0 until pts.size - 1) {
            val (ax, az) = pts[i]
            val (bx, bz) = pts[i + 1]
            val n = max(1, (hypot(bx - ax, bz - az) / 0.2).toInt())
            for (j in 0..n) out += (ax + (bx - ax) * j / n) to (az + (bz - az) * j / n)
        }
        return out
    }

    private fun inWater(w: World, x: Double, z: Double, margin: Double) =
        w.water.any { x > min(it.x0, it.x1) - margin && x < max(it.x0, it.x1) + margin && z > min(it.z0, it.z1) - margin && z < max(it.z0, it.z1) + margin }

    private fun problems(w: World, withExtras: Boolean): List<String> {
        val out = ArrayList<String>()
        val bs = boxes(w, withExtras)
        for (mv in w.movers) {
            val (across, _) = moverHalf(mv)
            val onBridge = mv.arc > 0 || mv.y > 0.5
            for ((x, z) in samples(mv)) {
                for (b in bs) {
                    // A figure standing on a boat, or the boat a boatman stands on, is a pair that belongs together.
                    if (isBoat(mv) && b.m.startsWith("p_")) continue
                    val d = dist(b, x, z)
                    if (d < across + 0.15) { out += "${w.id}: ${mv.m} through ${b.m}@(${b.x},${b.z}) at (%.1f, %.1f) gap %.2f".format(x, z, d - across); break }
                }
                if (!onBridge && !isBoat(mv) && inWater(w, x, z, -0.2)) out += "${w.id}: ${mv.m} walks in the water at (%.1f, %.1f)".format(x, z)
                if (isBoat(mv) && !inWater(w, x, z, 0.0)) out += "${w.id}: ${mv.m} sails on land at (%.1f, %.1f)".format(x, z)
            }
        }
        // v0.3.3 (device 2026-11-18, 虹桥: 「大船开过来……船的一侧从自己身体中穿过」): at life size the player stands at the
        // origin; nothing comes within 1.2 m of them. v0.5.1: measured to the mover's real outline along its way (a 16 m
        // boat passing 1.8 m to one side was counted as a 16 m circle).
        for (mv in w.movers) {
            val (across, along) = moverHalf(mv)
            val pts = if (mv.loop && !mv.reset) mv.path + mv.path.first() else mv.path
            loop@ for (i in 0 until pts.size - 1) {
                val (ax, az) = pts[i]
                val (bx, bz) = pts[i + 1]
                val l = hypot(bx - ax, bz - az)
                if (l <= 0) continue
                val ux = (bx - ax) / l
                val uz = (bz - az) / l
                val n = max(1, (l / 0.2).toInt())
                for (j in 0..n) {
                    val x = ax + (bx - ax) * j / n
                    val z = az + (bz - az) * j / n
                    // The origin in the mover's frame: along its way and across it.
                    val a = (-x) * ux + (-z) * uz
                    val c = (-x) * uz - (-z) * ux
                    val d = hypot(max(abs(a) - along, 0.0), max(abs(c) - across, 0.0))
                    if (d < 1.2) { out += "${w.id}: ${mv.m} reaches the player at (%.1f, %.1f)".format(x, z); break@loop }
                }
            }
        }
        for (b in bs) if (dist(b, 0.0, 0.0) < 0.8) out += "${w.id}: ${b.m}@(${b.x},${b.z}) stands on the player"
        // Movers against each other, over ten minutes.
        val ms = w.movers
        var t = 0.0
        val seen = HashSet<String>()
        while (t < 600.0) {
            val at = ms.map { it.at(t) }
            for (i in ms.indices) for (j in i + 1 until ms.size) {
                val a = ms[i]; val b = ms[j]
                if (isBoat(a) != isBoat(b)) continue
                val ra = moverHalf(a).first * 0.9
                val rb = moverHalf(b).first * 0.9
                val d = hypot(at[i][0] - at[j][0], at[i][1] - at[j][1])
                // Same way along the same line (a train): spaced by phase, never closer than their lengths.
                if (d < ra + rb) {
                    val key = "$i-$j"
                    if (seen.add(key)) out += "${w.id}: ${a.m} and ${b.m} meet at t=%.1f (%.1f, %.1f) d=%.2f".format(t, at[i][0], at[i][1], d)
                }
            }
            t += 0.2
        }
        return out.distinct()
    }

    @Test
    fun nothingMovesThroughAnything() {
        val all = Worlds.ALL.values.flatMap { w -> problems(w, withExtras = true) }
        val byWorld = all.groupBy { it.substringBefore(":") }
        val report = byWorld.entries.joinToString("\n") { (k, v) -> "$k (${v.size}):\n  " + v.distinctBy { it.substringBefore(" at ") }.take(12).joinToString("\n  ") }
        if (all.isNotEmpty()) File("build/layout-problems.txt").writeText(report)
        assertTrue("clipping:\n$report", all.isEmpty())
    }
}
