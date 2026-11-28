package com.vrgames.huazhong.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.5.2 (device 2026-11-28: a miniature bridge through 虹桥's result panel; 「你要确定你现在的方案可以解决这个问题，而且能
 * 把我们目前所有会出现这样问题的场景全都解决掉」): [MiniPanels] works the panels' places out from boxes round the models;
 * here the places it gives are checked against the models' real triangles (assets/scenes, every version the miniature
 * may show: light, full, near), placed as WorldView places them: each piece of each world's miniature, every mover at
 * every half second of its round (boats rolled and pitched either way, walkers too), 虹桥's big boat with its mast, 上元夜's
 * lanterns over their whole rise. From the eyes to points all over every panel shown with the miniature, nothing may come
 * before the panel (within 1 cm of it counts).
 */
class MiniPanelsExactTest {
    private val dir = File("src/main/assets/scenes")
    private val sizes: Map<String, DoubleArray> = File(dir, "sizes.txt").readLines().mapNotNull { l ->
        val f = l.trim().split(Regex("\\s+"))
        if (f.size >= 4) f[0] to doubleArrayOf(f[1].toDouble(), f[2].toDouble(), f[3].toDouble()) else null
    }.toMap()
    private val shapes = MiniPanels.parseShapes(File(dir, "shapes.txt").readLines())
    private val glbs = HashMap<String, List<SceneMesh>>()

    /** The versions of [m] that may stand on the miniature (its light version, the full one, a near one). */
    private fun models(m: String): List<SceneMesh> = glbs.getOrPut(m) {
        listOf(m, "${m}_lo", "${m}_hero").map { File(dir, "$it.glb") }.filter { it.exists() }.map { SceneMesh.read(it) }
    }

    /** One model placed in the world: at (x, y, z), turned [yaw] about y, then [pitch] about x and [roll] about z, scaled. */
    private class Placed(val what: String, val g: List<SceneMesh>, val x: Double, val y: Double, val z: Double, val yaw: Double,
                         val s: Double = 1.0, val pitch: Double = 0.0, val roll: Double = 0.0, val parent: Placed? = null)

    /** A world point into [p]'s model frame (through its parent first). */
    private fun toModel(p: Placed, v: DoubleArray): DoubleArray {
        val q = p.parent?.let { toModel(it, v) } ?: v
        var x = q[0] - p.x
        var y = q[1] - p.y
        var z = q[2] - p.z
        val a = Math.toRadians(-p.yaw)
        val x1 = x * cos(a) + z * sin(a)
        val z1 = -x * sin(a) + z * cos(a)
        x = x1; z = z1
        // Undo pitch (about x) then roll (about z), small angles.
        val pa = Math.toRadians(-p.pitch)
        val y2 = y * cos(pa) - z * sin(pa)
        val z2 = y * sin(pa) + z * cos(pa)
        y = y2; z = z2
        val ra = Math.toRadians(-p.roll)
        val x3 = x * cos(ra) - y * sin(ra)
        val y3 = x * sin(ra) + y * cos(ra)
        return doubleArrayOf(x3 / p.s, y3 / p.s, z / p.s)
    }

    /** Whether the segment o + t (d), t in [0, tMax], meets [g]'s triangles. */
    private fun hits(g: SceneMesh, o: DoubleArray, d: DoubleArray, tMax: Double): Boolean {
        // Its box first.
        var t0 = 0.0
        var t1 = tMax
        for (i in 0..2) {
            if (abs(d[i]) < 1e-12) {
                if (o[i] < g.min[i] || o[i] > g.max[i]) return false
            } else {
                var a = (g.min[i] - o[i]) / d[i]
                var b = (g.max[i] - o[i]) / d[i]
                if (a > b) { val s = a; a = b; b = s }
                t0 = maxOf(t0, a)
                t1 = minOf(t1, b)
                if (t0 > t1) return false
            }
        }
        val tr = g.tris
        var i = 0
        while (i < tr.size) {
            val e1x = tr[i + 3] - tr[i]; val e1y = tr[i + 4] - tr[i + 1]; val e1z = tr[i + 5] - tr[i + 2]
            val e2x = tr[i + 6] - tr[i]; val e2y = tr[i + 7] - tr[i + 1]; val e2z = tr[i + 8] - tr[i + 2]
            val px = d[1] * e2z - d[2] * e2y; val py = d[2] * e2x - d[0] * e2z; val pz = d[0] * e2y - d[1] * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) > 1e-14) {
                val inv = 1.0 / det
                val sx = o[0] - tr[i]; val sy = o[1] - tr[i + 1]; val sz = o[2] - tr[i + 2]
                val u = (sx * px + sy * py + sz * pz) * inv
                if (u >= 0 && u <= 1) {
                    val qx = sy * e1z - sz * e1y; val qy = sz * e1x - sx * e1z; val qz = sx * e1y - sy * e1x
                    val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
                    if (v >= 0 && u + v <= 1) {
                        val t = (e2x * qx + e2y * qy + e2z * qz) * inv
                        if (t > 0 && t <= tMax) return true
                    }
                }
            }
            i += 9
        }
        return false
    }

    /** Points over a panel (its frame included), in the gallery's frame. */
    private fun points(p: MiniPanels.Panel): List<DoubleArray> {
        val pa = Math.toRadians(p.pitch)
        val ya = Math.toRadians(p.yaw)
        val up = doubleArrayOf(sin(ya) * sin(pa), cos(pa), cos(ya) * sin(pa))
        val right = doubleArrayOf(cos(ya), 0.0, -sin(ya))
        val out = ArrayList<DoubleArray>()
        for (i in 0..16) for (j in 0..9) {
            val u = (i / 16.0 - 0.5) * p.w
            val v = (j / 9.0 - 0.5) * p.h
            out += doubleArrayOf(p.x + right[0] * u + up[0] * v, p.y + right[1] * u + up[1] * v, p.z + right[2] * u + up[2] * v)
        }
        return out
    }

    /** Everything on [w]'s miniature as WorldView places it (world frame). */
    private fun scene(w: World, night: Boolean): List<Placed> {
        val r = Dressing.regionOf(w)
        fun inRegion(x: Double, z: Double, m: Double) = x >= r[0] - m && x <= r[1] + m && z >= r[2] - m && z <= r[3] + m
        val out = ArrayList<Placed>()
        val extras = Dressing.extras(w) { m -> sizes[m]?.let { it[0] to it[2] } }
        for (p in w.pieces + extras + listOfNotNull(w.restore?.let { Piece(it.m, it.x, it.z, it.yaw, it.y) })) {
            if (!inRegion(p.x, p.z, 0.0)) continue
            out += Placed(p.m, models(p.m), p.x, p.y, p.z, p.yaw, p.scale)
        }
        val attitudes = listOf(0.0 to 0.0, 0.8 to 2.6, -0.8 to -2.6, 1.6 to -2.6, -1.6 to 2.6)
        for (m in w.movers) {
            val still = MiniPanels.stillOnMiniature(w, m)
            var t = 0.0
            while (t < (if (still) 0.1 else m.period)) {
                val a = m.at(if (still) MiniPanels.stillTime(m) else t)
                if (inRegion(a[0], a[1], -0.5)) for ((pitch, roll) in attitudes) {
                    val holder = Placed(m.m, emptyList(), a[0], a[3] + m.bob, a[1], a[2], 1.0, pitch, roll)
                    out += Placed(m.m, models(m.m), 0.0, 0.0, 0.0, Worlds.FWD[m.m] ?: 0.0, parent = holder)
                    if (w.mastBridgeZ != null && m.m == "qm_bigboat") {
                        val ang = Worlds.mastAngle(if (still) Worlds.MINI_STILL else m.progress(t))
                        val hinge = Placed("hinge", emptyList(), 0.0, Worlds.MAST_HINGE, Worlds.MAST_FWD, 0.0, parent = holder)
                        out += Placed("qm_mast", models("qm_mast"), 0.0, 0.0, 0.0, 0.0, pitch = -ang, parent = hinge)
                    }
                }
                t += 0.5
            }
        }
        if (night) {
            val (k, _, _, oz) = MiniPanels.transform(w).toList()
            for ((x, z) in MiniPanels.RED_LANTERNS) out += Placed("lantern_red", models("lantern_red"), x, 3.2, z, 0.0)
            for (l in MiniPanels.skyLanterns()) {
                if (oz + k * l[1] >= MiniPanels.NIGHT_FAR) continue
                var y = 3.0
                while (y <= 35.0) {
                    for (dx in listOf(-0.6, 0.0, 0.6)) out += Placed("lantern_sky", models("lantern_sky"), l[0] + dx, y, l[1], 0.0)
                    y += 1.0
                }
            }
        }
        return out
    }

    @Test
    fun placedPanelsClearOfTheRealModels() {
        val problems = ArrayList<String>()
        val worlds = Worlds.ALL.values.map { it to false } + listOfNotNull(Worlds.ALL.values.firstOrNull { it.id == "qm3" }?.let { it to true })
        for ((w, night) in worlds) {
            val (k, ox, oy, oz) = MiniPanels.transform(w).toList()
            val placed = MiniPanels.placement(w, sizes, night, shapes)
            val things = scene(w, night)
            val labels = MiniPanels.content(w, sizes, night, shapes = shapes).filter { it.what == "found label" }
            val name = w.id + if (night) " (上元夜)" else ""
            for (p in placed.values.filter { it.id in MiniPanels.SHOWN_WITH_MINIATURE }) {
                val blocked = LinkedHashSet<String>()
                for (pt in points(p)) {
                    val len = sqrt(pt[0] * pt[0] + pt[1] * pt[1] + pt[2] * pt[2])
                    val tMax = 1.0 + 0.01 / len
                    // The eyes and the point in the world's frame (the miniature's scale undone).
                    val ow = doubleArrayOf((0 - ox) / k, (0 - oy) / k, (0 - oz) / k)
                    val pw = doubleArrayOf((pt[0] - ox) / k, (pt[1] - oy) / k, (pt[2] - oz) / k)
                    for (th in things) {
                        if (th.what in blocked) continue
                        val om = toModel(th, ow)
                        val pm = toModel(th, pw)
                        val d = doubleArrayOf(pm[0] - om[0], pm[1] - om[1], pm[2] - om[2])
                        if (th.g.any { hits(it, om, d, tMax) }) blocked += th.what
                    }
                }
                blocked += MiniPanels.blockers(p, labels)
                println("$name ${p.id} at x %.2f y %.2f z %.2f: ${if (blocked.isEmpty()) "clear" else "BLOCKED by $blocked"}".format(p.x, p.y, p.z))
                if (blocked.isNotEmpty()) problems += "$name ${p.id}: $blocked"
            }
        }
        println("PROBLEMS\n" + problems.joinToString("\n"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
