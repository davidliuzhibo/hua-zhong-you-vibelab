package com.vrgames.huazhong.core

import kotlin.math.hypot
import kotlin.random.Random

/**
 * More of each world (v0.3, 2026-11-18). Seen as a miniature, the worlds read empty: a lawn, a road, a few trees
 * (清明上河图 is crowded). Each world gets a kit of further models from the same set — cottages and fields in the
 * country, sheds and stalls by the river, shops along the streets, pines and huts in the mountains — placed off the
 * roads, the water and the walkers' ways, clear of what stands there already and of where the player stands when
 * walking in. Seeded by the world, so it is the same every visit.
 */
object Dressing {
    /** A model of the kit, how many, and its scale range on top of its prepared size. */
    data class Item(val m: String, val n: Int, val s0: Double = 0.9, val s1: Double = 1.1, val onWater: Boolean = false)

    val KITS: Map<String, List<Item>> = mapOf(
        // 汴京郊野: farmsteads, cottages, peach and elm, a few country folk.
        "qm1" to listOf(Item("qm_farm", 2), Item("ql_cottage", 2), Item("t_peach", 4, 0.7, 1.0), Item("t_elm", 3, 0.6, 0.85), Item("t_willow", 2, 0.6, 0.75), Item("p_woman", 1), Item("p_child", 1), Item("p_story", 1)),
        // 汴河漕运: warehouses and tea sheds on the bank, porters resting, willows.
        "qm2" to listOf(Item("qm_warehouse", 2), Item("qm_teashed", 2), Item("qm_stall", 2), Item("p_porter", 2), Item("p_merchant", 1), Item("t_willow", 3, 0.6, 0.75)),
        // 虹桥: stalls and sheds at the bridge's ends, a crowd.
        "qm3" to listOf(Item("qm_stall", 4), Item("qm_teashed", 2), Item("qm_shop", 1), Item("p_merchant", 2), Item("p_woman", 2), Item("p_scholar", 1), Item("p_child", 1), Item("t_willow", 2, 0.6, 0.75)),
        // 城外街道: more shops and stalls along the road, people at their doors.
        "qm4" to listOf(Item("qm_shop", 2), Item("qm_shop2", 1), Item("qm_stall", 3), Item("qm_house", 2), Item("p_vendor", 2), Item("p_woman", 1), Item("p_story", 1), Item("t_elm", 2, 0.6, 0.8)),
        // 城门: houses by the wall, stalls before the gate, a guard and passers-by.
        "qm5" to listOf(Item("qm_house", 2), Item("qm_stall", 3), Item("qm_teashed", 1), Item("p_guard", 1), Item("p_merchant", 1), Item("p_porter", 1), Item("t_willow", 2, 0.6, 0.75)),
        // 孙羊正店: the street inside the walls, shops and stalls, people.
        "qm6" to listOf(Item("qm_shop", 2), Item("qm_shop2", 2), Item("qm_stall", 3), Item("p_woman", 2), Item("p_scholar", 1), Item("p_child", 1), Item("lantern_red", 3)),
        // 千里江山: pines, huts and little hills on the shores.
        "ql1" to listOf(Item("ql_pine", 6, 0.6, 1.0), Item("ql_cottage", 3), Item("ql_hill", 3, 0.6, 1.0), Item("ql_pavilion", 1)),
        // 长桥 is all lake before the player: islets and moored fishing boats out on the water.
        "ql2" to listOf(Item("ql_hill", 5, 0.35, 0.6, onWater = true), Item("ql_fishboat", 2, onWater = true), Item("ql_pine", 2, 0.6, 0.9)),
        "ql3" to listOf(Item("ql_pine", 6, 0.6, 1.0), Item("ql_cottage", 2), Item("ql_hill", 2, 0.6, 1.0), Item("ql_mill", 1)),
    )

    /** The part of [w] its things are in (x0, x1, z0, z1): round everything that stands or goes there, the player's side open. */
    fun regionOf(w: World): DoubleArray {
        val xs = ArrayList<Double>()
        val zs = ArrayList<Double>()
        for (p in w.pieces) { xs += p.x; zs += p.z }
        // v0.5.1: only the near part of the ways (one-way movers start and end far off, out of sight; their far ends would
        // spread the kit thin over a much larger region).
        for (m in w.movers) for ((x, z) in m.path) if (kotlin.math.abs(x) <= 32.0 && z >= -40.0 && z <= 6.0) { xs += x; zs += z }
        w.restore?.let { xs += it.x; zs += it.z }
        w.fall?.let { xs += it.x; zs += it.z }
        if (xs.isEmpty()) return doubleArrayOf(-20.0, 20.0, -30.0, 2.0)
        val m = 5.0
        return doubleArrayOf(xs.min() - m, xs.max() + m, zs.min() - m, minOf(zs.max() + m, 4.0))
    }

    /** Clear of the player's place at life size (the origin) and of the view straight ahead from it. */
    const val CLEAR_R = 9.0
    const val CLEAR_AHEAD_X = 4.0
    const val CLEAR_AHEAD_Z = -16.0

    /**
     * The extra pieces for [w]. [size] gives a model's footprint (x, z extents in metres, prepared size) — unknown
     * models are skipped.
     */
    fun extras(w: World, size: (String) -> Pair<Double, Double>?): List<Piece> {
        val kit = KITS[w.id] ?: return emptyList()
        val rnd = Random(w.id.hashCode())
        val r = regionOf(w)
        val placed = ArrayList<Triple<Double, Double, Double>>() // x, z, radius
        for (p in w.pieces) size(p.m)?.let { (sx, sz) -> placed += Triple(p.x, p.z, footprint(p.m, sx, sz) * p.scale) }
        w.restore?.let { rs -> size(rs.m)?.let { (sx, sz) -> placed += Triple(rs.x, rs.z, footprint(rs.m, sx, sz)) } }
        val out = ArrayList<Piece>()
        for (item in kit) {
            val (sx, sz) = size(item.m) ?: continue
            repeat(item.n) {
                for (attempt in 0 until 80) {
                    val s = item.s0 + (item.s1 - item.s0) * rnd.nextDouble()
                    val rad = footprint(item.m, sx, sz) * s
                    val inset = rad + 1.0
                    if (r[1] - r[0] <= 2 * inset || r[3] - r[2] <= 2 * inset) break
                    val x = r[0] + inset + (r[1] - r[0] - 2 * inset) * rnd.nextDouble()
                    val z = r[2] + inset + (r[3] - r[2] - 2 * inset) * rnd.nextDouble()
                    if (!free(w, x, z, rad, placed, item.onWater)) continue
                    placed += Triple(x, z, rad)
                    out += Piece(item.m, x, z, yaw = rnd.nextDouble() * 360.0, scale = s)
                    break
                }
            }
        }
        return out
    }

    /** How much ground a model keeps to itself: buildings their half-width; trees and people less (a trunk, feet). */
    fun footprint(m: String, sx: Double, sz: Double): Double {
        val half = maxOf(sx, sz) / 2
        return when {
            m.startsWith("t_") || m == "ql_pine" -> half * 0.35
            m.startsWith("p_") -> 0.6
            else -> half
        }
    }

    fun free(w: World, x: Double, z: Double, rad: Double, placed: List<Triple<Double, Double, Double>>, onWater: Boolean = false): Boolean {
        if (hypot(x, z) < CLEAR_R + rad) return false
        if (kotlin.math.abs(x) < CLEAR_AHEAD_X + rad && z > CLEAR_AHEAD_Z - rad) return false
        if (onWater) {
            if (w.water.none { inside(x, z, it.x0, it.z0, it.x1, it.z1, -rad) }) return false
        } else for (wt in w.water) if (inside(x, z, wt.x0, wt.z0, wt.x1, wt.z1, rad + 1.5)) return false
        for (s in w.strips) if (inside(x, z, s.x0, s.z0, s.x1, s.z1, rad + 1.0)) return false
        for ((px, pz, pr) in placed) if (hypot(x - px, z - pz) < rad + pr + 1.0) return false
        for (m in w.movers) {
            val pts = if (m.loop && !m.reset) m.path + m.path.first() else m.path
            // A boat's lane is wider (its beam is up to 4 m).
            for (i in 0 until pts.size - 1) if (segDist(x, z, pts[i], pts[i + 1]) < rad + if (m.boat) 3.5 else 2.0) return false
        }
        w.fall?.let { f -> if (hypot(x - f.x, z - f.z) < rad + f.w) return false }
        // v0.3.1: not in the line of sight from the player's place to a thing to find (an islet hid the long bridge).
        for (p in w.pieces) if (p.find && segDist(x, z, 0.0 to 0.0, p.x to p.z) < rad + 1.5) return false
        return true
    }

    private fun inside(x: Double, z: Double, x0: Double, z0: Double, x1: Double, z1: Double, m: Double) =
        x > minOf(x0, x1) - m && x < maxOf(x0, x1) + m && z > minOf(z0, z1) - m && z < maxOf(z0, z1) + m

    private fun segDist(x: Double, z: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dz = b.second - a.second
        val l2 = dx * dx + dz * dz
        val t = if (l2 > 0) (((x - a.first) * dx + (z - a.second) * dz) / l2).coerceIn(0.0, 1.0) else 0.0
        return hypot(x - (a.first + t * dx), z - (a.second + t * dz))
    }
}
