package com.vrgames.huazhong.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * v0.5.1 (device 2026-11-27: 「整个游戏里边有运动的情况再重新检查一遍，不要再有这种情况出现」): every moving thing in
 * every world, walked along its way step by step at its real size and heading, must not go through a house, a tree, a
 * moored boat or another mover, must not pass within reach of the player, a boat must stay on the water and a walker
 * off it (except on a bridge), and 虹桥's mast must stay clear of the bridge. Sizes from assets scenes/sizes.txt.
 */
class MoverClearanceTest {
    private val sizes: Map<String, DoubleArray> = File("src/main/assets/scenes/sizes.txt").readLines().mapNotNull { l ->
        val f = l.trim().split(Regex("\\s+"))
        if (f.size >= 4) f[0] to doubleArrayOf(f[1].toDouble(), f[2].toDouble(), f[3].toDouble()) else null
    }.toMap()

    private enum class Kind { BUILDING, BRIDGE, TREE, HILL, BOAT, PERSON, BEAST, OTHER }

    private fun kind(m: String) = when {
        m == "qm_bridge" || m == "ql_longbridge" -> Kind.BRIDGE
        m.startsWith("t_") || m == "ql_pine" -> Kind.TREE
        m == "ql_mount1" || m == "ql_mount2" || m == "ql_hill" -> Kind.HILL
        m in setOf("qm_cargo", "qm_bigboat", "qm_canopy", "qm_rowboat", "ql_fishboat") -> Kind.BOAT
        m.startsWith("p_") -> Kind.PERSON
        m in setOf("qm_donkey", "qm_camel", "qm_horse", "qm_oxcart", "qm_sedan", "qm_barrow", "ql_traveler") -> Kind.BEAST
        m.startsWith("lantern") || m == "qm_mast" -> Kind.OTHER
        else -> Kind.BUILDING
    }

    /** An oriented box on the ground: centre, half extents along its own x and z, heading (degrees, 0 = +z). */
    private data class Box(val x: Double, val z: Double, val hx: Double, val hz: Double, val yaw: Double) {
        fun corners(): List<Pair<Double, Double>> {
            val r = Math.toRadians(yaw)
            // Model +x -> (cos, -sin), model +z -> (sin, cos) (a heading of 90 faces +x).
            val ax = cos(r) to -sin(r)
            val az = sin(r) to cos(r)
            return listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (i, j) ->
                (x + i * hx * ax.first + j * hz * az.first) to (z + i * hx * ax.second + j * hz * az.second)
            }
        }
        fun axes(): List<Pair<Double, Double>> {
            val r = Math.toRadians(yaw)
            return listOf(cos(r) to -sin(r), sin(r) to cos(r))
        }
    }

    private fun overlap(a: Box, b: Box): Boolean {
        val ca = a.corners()
        val cb = b.corners()
        for ((ux, uz) in a.axes() + b.axes()) {
            val pa = ca.map { it.first * ux + it.second * uz }
            val pb = cb.map { it.first * ux + it.second * uz }
            if (pa.max() < pb.min() || pb.max() < pa.min()) return false
        }
        return true
    }

    /** Distance from the origin (the player) to the box. */
    private fun distToOrigin(b: Box): Double {
        val r = Math.toRadians(b.yaw)
        // The origin in the box's own frame.
        val dx = -b.x
        val dz = -b.z
        val lx = dx * cos(r) - dz * sin(r)
        val lz = dx * sin(r) + dz * cos(r)
        val ex = (abs(lx) - b.hx).coerceAtLeast(0.0)
        val ez = (abs(lz) - b.hz).coerceAtLeast(0.0)
        return hypot(ex, ez)
    }

    /** How much of its bounding box a model really fills on the ground. */
    private fun shrink(k: Kind) = when (k) {
        Kind.BUILDING -> 0.85
        Kind.HILL -> 0.75
        Kind.BOAT -> 0.95
        Kind.PERSON -> 0.55
        Kind.BEAST -> 0.85
        else -> 1.0
    }

    private fun pieceBox(p: Piece): Box? {
        val s = sizes[p.m] ?: return null
        val k = kind(p.m)
        if (k == Kind.OTHER) return null
        if (k == Kind.TREE) return Box(p.x, p.z, 0.45 * p.scale, 0.45 * p.scale, p.yaw)
        val f = shrink(k) * p.scale
        return Box(p.x, p.z, s[0] / 2 * f, s[2] / 2 * f, p.yaw)
    }

    private fun moverBox(m: Mover, a: DoubleArray): Box? {
        val s = sizes[m.m] ?: return null
        val f = shrink(kind(m.m))
        return Box(a[0], a[1], s[0] / 2 * f, s[2] / 2 * f, a[2] + (Worlds.FWD[m.m] ?: 0.0))
    }

    private fun inWater(w: World, x: Double, z: Double, margin: Double) =
        w.water.any { x > minOf(it.x0, it.x1) + margin && x < maxOf(it.x0, it.x1) - margin && z > minOf(it.z0, it.z1) + margin && z < maxOf(it.z0, it.z1) - margin }

    @Test
    fun nothingMovesThroughAnything() {
        val problems = LinkedHashMap<String, String>()
        fun note(key: String, what: String) { if (key !in problems) problems[key] = what }
        for (w in Worlds.ALL.values) {
            val statics = (w.pieces + Dressing.extras(w) { m -> sizes[m]?.let { it[0] to it[2] } } +
                listOfNotNull(w.restore?.let { Piece(it.m, it.x, it.z, it.yaw) }))
            val staticBoxes = statics.mapNotNull { p -> pieceBox(p)?.let { p to it } }
            val period = w.movers.maxOfOrNull { it.period } ?: 0.0
            val end = minOf(maxOf(period * 2.0, 120.0), 1500.0)
            var t = 0.0
            while (t <= end) {
                val boxes = w.movers.map { m -> val a = m.at(t); Triple(m, a, moverBox(m, a)) }
                for ((m, a, b) in boxes) {
                    b ?: continue
                    val k = kind(m.m)
                    val onBridge = m.arc > 0 || m.y > 0.5
                    // The player.
                    if (distToOrigin(b) < 1.4) note("${w.id} ${m.m} player", "${w.id}: ${m.m} passes %.2f m from the player at t=%.1f (%.1f, %.1f)".format(distToOrigin(b), t, a[0], a[1]))
                    // Water: boats on it (all four corners), walkers off it.
                    if (k == Kind.BOAT) {
                        if (b.corners().any { (x, z) -> !inWater(w, x, z, -0.3) }) note("${w.id} ${m.m} dry", "${w.id}: ${m.m} runs aground at t=%.1f (%.1f, %.1f)".format(t, a[0], a[1]))
                    } else if (!onBridge && inWater(w, a[0], a[1], 0.3)) note("${w.id} ${m.m} wet", "${w.id}: ${m.m} walks in the water at t=%.1f (%.1f, %.1f)".format(t, a[0], a[1]))
                    // Standing things.
                    for ((p, pb) in staticBoxes) {
                        val pk = kind(p.m)
                        if (pk == Kind.BRIDGE && (onBridge || k == Kind.BOAT)) continue
                        // Boats moor in the water, walkers do not meet them there; people and moored boats are both checked.
                        if (overlap(b, pb)) note("${w.id} ${m.m} ${p.m}@${p.x},${p.z}", "${w.id}: ${m.m} goes through ${p.m} at (%.1f, %.1f), t=%.1f, mover at (%.1f, %.1f)".format(p.x, p.z, t, a[0], a[1]))
                    }
                }
                // Movers against each other.
                for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                    val bi = boxes[i].third ?: continue
                    val bj = boxes[j].third ?: continue
                    // A walker on a bridge and a boat under it are at different heights.
                    val mi = boxes[i].first
                    val mj = boxes[j].first
                    val upI = mi.arc > 0 || mi.y > 0.5
                    val upJ = mj.arc > 0 || mj.y > 0.5
                    if ((upI && kind(mj.m) == Kind.BOAT) || (upJ && kind(mi.m) == Kind.BOAT)) continue
                    if (overlap(bi, bj)) note("${w.id} $i-$j", "${w.id}: ${boxes[i].first.m} and ${boxes[j].first.m} run into each other at t=%.1f (%.1f, %.1f)".format(t, boxes[i].second[0], boxes[i].second[1]))
                }
                // 虹桥's mast against the bridge.
                if (w.mastBridgeZ != null) {
                    val boat = boxes.firstOrNull { it.first.m == "qm_bigboat" }
                    // The bridge's deck and arch over the water (its bounding box reaches out to the wing walls on the
                    // quays): 8.6 m wide (across the river's flow), 26.4 m long.
                    val bridge = staticBoxes.firstOrNull { it.first.m == "qm_bridge" }?.first?.let { Box(it.x, it.z, 4.3, 13.2, it.yaw) }
                    if (boat != null && bridge != null) {
                        val (m, a, _) = boat
                        val ang = Math.toRadians(Worlds.mastAngle(m.progress(t)))
                        val h = Math.toRadians(a[2])
                        // Toward the stern: against the heading; the hinge MAST_FWD toward the bow.
                        val bx = -sin(h)
                        val bz = -cos(h)
                        val hx = a[0] - bx * Worlds.MAST_FWD
                        val hz = a[1] - bz * Worlds.MAST_FWD
                        // The bow (8 m forward of the middle) no further than half a metre under the bridge's near edge.
                        val bowZ = a[1] - bz * 8.0
                        if (bowZ < -12.8 - 0.5) note("${w.id} bow", "${w.id}: the big boat's bow goes %.1f m under the bridge at t=%.1f".format(-12.8 - bowZ, t))
                        for (q in 0..24) {
                            val l = Worlds.MAST_LEN * q / 24
                            // Lowered, the mast stays above the cabin roof (it sank into the cabin, hinged at 1.6 m).
                            val y = Worlds.MAST_HINGE + cos(ang) * l
                            val along = Worlds.MAST_FWD - sin(ang) * l
                            if (along > -8.0 && y < Worlds.bigboatRoof(along) + 0.12) note("${w.id} mast roof", "${w.id}: the mast goes into the cabin at t=%.1f (%.0f deg, %.1f m along)".format(t, Math.toDegrees(ang), l))
                            val x = hx + bx * sin(ang) * l
                            val z = hz + bz * sin(ang) * l
                            val near = Box(x, z, 0.3, 0.3, 0.0)
                            if (overlap(near, bridge.copy(hx = bridge.hx + 0.6, hz = bridge.hz + 0.6))) {
                                note("${w.id} mast", "${w.id}: the mast reaches the bridge at t=%.1f (boat at %.1f, %.1f, mast %.0f deg)".format(t, a[0], a[1], Math.toDegrees(ang)))
                                break
                            }
                        }
                    }
                }
                t += 0.2
            }
        }
        problems.values.forEach { println(it) }
        assertTrue("${problems.size} clearance problems:\n" + problems.values.joinToString("\n"), problems.isEmpty())
    }
}
