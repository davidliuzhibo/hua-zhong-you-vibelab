package com.vrgames.huazhong.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * v0.5.2 (device 2026-11-28, 画中游截图3: on 虹桥's result panel 「有像桥一样的微缩物体」): the panels round the player and
 * the miniature on the table share one space. The result panel stood over the middle of the table (0.82 m ahead), so
 * whatever stood there in the miniature (the new 虹桥, 7 m high, 17 cm at its scale) went through it, and the big boat's
 * mast at the table's front stood in front of it. Here: where every panel is (the one source: content/GalleryStage puts
 * them from [PANELS]), what each world's miniature holds (its pieces, every mover along its whole way, 虹桥's mast at
 * every angle), and [lift]: how much higher a panel must stand in a world so that nothing goes through it or stands
 * between the eyes and it. The unit test holds every panel clear in every world.
 *
 * Frame: the gallery's, origin at the eyes, metres, -z ahead, y up (as WorldView.place puts the miniature).
 */
object MiniPanels {
    /** A panel: its middle, its size (with its frame), its tilt (degrees about x: negative leans the top back) and turn. */
    data class Panel(val id: String, val x: Double, val y: Double, val z: Double, val w: Double, val h: Double, val pitch: Double, val yaw: Double = 0.0)

    /** As content/Panels.kt sizes them, plus 4 cm all round for the frame. The miniature shows with TASK, NOTE, RESULT, MENU. */
    val PANELS = listOf(
        Panel("card", 0.0, -0.26, -0.85, 0.56, 0.29, -12.0),
        Panel("result", 0.0, -0.24, -0.82, 0.56, 0.29, -12.0),
        Panel("home", 0.0, -0.5, -0.78, 0.52, 0.24, -30.0),
        Panel("task", -0.74, -0.22, -0.5, 0.5, 0.36, -18.0, 50.0),
        Panel("note", 0.72, -0.2, -0.52, 0.44, 0.14, -15.0, -50.0),
        // Placement is locked (0.3.3's places, PlacementLockTest): over a miniature whose front corner stands before the
        // menu tab, [placement] moves it there only.
        Panel("menu", -0.6, -0.58, -0.4, 0.30, 0.22, -35.0, 50.0),
    )
    val SHOWN_WITH_MINIATURE = setOf("task", "note", "result", "menu")

    // As render/WorldView (the miniature's table).
    const val MINI_W = 1.1
    const val MINI_D = 0.78
    const val MINI_BELOW_EYE = 0.45
    const val MINI_NEAR = 0.42

    /**
     * v0.5.2: a model's outline as columns (assets scenes/shapes.txt, tools/model_shapes.py, from its real triangles):
     * its footprint [x0]..[x1] x [z0]..[z1] (model frame) cut into n x n cells, each with its lowest and highest point
     * (NaN where the model has nothing). The miniature's peaks, the 虹桥's arch and poles, the trees' crowns as they are.
     */
    class Shape(val n: Int, val x0: Double, val x1: Double, val z0: Double, val z1: Double, val lo: DoubleArray, val hi: DoubleArray)

    /** shapes.txt's lines: "<model> <n> <x0> <x1> <z0> <z1> <ymin,ymax | -> ..." */
    fun parseShapes(lines: List<String>): Map<String, Shape> = lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
        val f = l.trim().split(Regex("\\s+"))
        val n = f.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        if (f.size < 6 + n * n) return@mapNotNull null
        val lo = DoubleArray(n * n) { Double.NaN }
        val hi = DoubleArray(n * n) { Double.NaN }
        for (c in 0 until n * n) {
            val v = f[6 + c]
            if (v == "-") continue
            val (a, b) = v.split(",")
            lo[c] = a.toDouble(); hi[c] = b.toDouble()
        }
        f[0] to Shape(n, f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), lo, hi)
    }.toMap()

    /** A box in the gallery's frame. */
    data class Box(val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double, val what: String)

    /** The miniature's scale and where the world's origin lands (as WorldView.place). */
    fun transform(w: World): DoubleArray {
        val r = Dressing.regionOf(w)
        val k = min(min(MINI_W / (r[1] - r[0]), MINI_D / (r[3] - r[2])), 1.0 / 30.0)
        val cx = (r[0] + r[1]) / 2
        val cz = (r[2] + r[3]) / 2
        val depth = (r[3] - r[2]) * k
        val tz = -(MINI_NEAR + depth / 2)
        return doubleArrayOf(k, -k * cx, -MINI_BELOW_EYE, tz - k * cz)
    }

    private fun inRegion(r: DoubleArray, x: Double, z: Double, m: Double) = x >= r[0] - m && x <= r[1] + m && z >= r[2] - m && z <= r[3] + m

    /** A footprint w x d (model x, z) turned by [yaw] about its middle (x, z): its extent in world x and z. */
    private fun footprint(x: Double, z: Double, w: Double, d: Double, yaw: Double): DoubleArray {
        val a = Math.toRadians(yaw)
        val ex = abs(cos(a)) * w / 2 + abs(sin(a)) * d / 2
        val ez = abs(sin(a)) * w / 2 + abs(cos(a)) * d / 2
        return doubleArrayOf(x - ex, x + ex, z - ez, z + ez)
    }

    /**
     * Everything the miniature of [w] shows, as boxes in the gallery's frame. [sizes]: model -> (w, h, d) in metres.
     * Movers: a box at each half second along one whole round; 虹桥's big boat's mast as a box round it at each angle.
     */
    fun content(w: World, sizes: Map<String, DoubleArray>, night: Boolean = false, life: Boolean = false, eyeY: Double = 1.6,
                shapes: Map<String, Shape> = emptyMap()): List<Box> {
        // [life]: walked in, life size round the player (everything shown, the eyes [eyeY] over the ground).
        val (k, ox, oy, oz) = if (life) listOf(1.0, 0.0, -eyeY, 0.0) else transform(w).toList()
        val r = if (life) doubleArrayOf(-1e9, 1e9, -1e9, 1e9) else Dressing.regionOf(w)
        val out = ArrayList<Box>()
        fun add(fp: DoubleArray, y0: Double, y1: Double, what: String) {
            out += Box(ox + k * fp[0], oy + k * y0, oz + k * fp[2], ox + k * fp[1], oy + k * y1, oz + k * fp[3], what)
        }
        val extras = Dressing.extras(w) { m -> sizes[m]?.let { it[0] to it[2] } }
        val pieces = w.pieces + extras + listOfNotNull(w.restore?.let { Piece(it.m, it.x, it.z, it.yaw, it.y) })
        for (p in pieces) {
            if (!inRegion(r, p.x, p.z, 0.0)) continue
            // Its columns, where its outline is known: each cell turned with the piece (the box round the turned cell).
            val col = shapes[p.m]
            if (col != null) {
                val a = Math.toRadians(p.yaw)
                val ca = cos(a)
                val sa = sin(a)
                val cw = (col.x1 - col.x0) / col.n
                val cd = (col.z1 - col.z0) / col.n
                val hx = (abs(ca) * cw + abs(sa) * cd) / 2 * p.scale
                val hz = (abs(sa) * cw + abs(ca) * cd) / 2 * p.scale
                for (i in 0 until col.n) for (j in 0 until col.n) {
                    val c = i * col.n + j
                    if (col.lo[c].isNaN()) continue
                    val mx = (col.x0 + (i + 0.5) * cw) * p.scale
                    val mz = (col.z0 + (j + 0.5) * cd) * p.scale
                    val wx = p.x + mx * ca + mz * sa
                    val wz = p.z - mx * sa + mz * ca
                    add(doubleArrayOf(wx - hx, wx + hx, wz - hz, wz + hz), p.y + col.lo[c] * p.scale, p.y + col.hi[c] * p.scale, p.m)
                }
                continue
            }
            val s = sizes[p.m] ?: continue
            val sw = s[0] * p.scale
            val sh = s[1] * p.scale
            val sd = s[2] * p.scale
            when {
                // Peaks and hills: cones. Three tiers, each narrower.
                p.m.startsWith("ql_mount") || p.m == "ql_hill" -> {
                    add(footprint(p.x, p.z, sw * 0.85, sd * 0.85, p.yaw), p.y, p.y + sh * 0.3, p.m)
                    add(footprint(p.x, p.z, sw * 0.55, sd * 0.55, p.yaw), p.y, p.y + sh * 0.65, p.m)
                    add(footprint(p.x, p.z, sw * 0.25, sd * 0.25, p.yaw), p.y, p.y + sh, p.m)
                }
                // Trees: a trunk, then the crown over its lower third and up.
                p.m.startsWith("t_") || p.m == "ql_pine" -> {
                    add(footprint(p.x, p.z, sw * 0.12, sd * 0.12, p.yaw), p.y, p.y + sh * 0.4, p.m)
                    add(footprint(p.x, p.z, sw * 0.9, sd * 0.9, p.yaw), p.y + sh * 0.3, p.y + sh, p.m)
                }
                // 虹桥 (tools/hongqiao.py): its deck and railings 8.6 m wide over the river, and a tall pole at each corner.
                p.m == "qm_bridge" -> {
                    add(footprint(p.x, p.z, 8.6, sd, p.yaw), p.y, p.y + 7.4, p.m)
                    val a = Math.toRadians(p.yaw)
                    for (mx in listOf(-4.75, 4.75)) for (mz in listOf(-12.4, 12.4)) {
                        val px = p.x + mx * cos(a) + mz * sin(a)
                        val pz = p.z - mx * sin(a) + mz * cos(a)
                        add(doubleArrayOf(px - 0.4, px + 0.4, pz - 0.4, pz + 0.4), p.y, p.y + 8.9, p.m)
                    }
                }
                // Houses and halls: walls, then the roof drawing in to its ridge.
                p.m.startsWith("qm_") && !p.m.startsWith("qm_bridge") && sh > 4.0 && p.m !in BOATS -> {
                    add(footprint(p.x, p.z, sw * 0.92, sd * 0.92, p.yaw), p.y, p.y + sh * 0.6, p.m)
                    add(footprint(p.x, p.z, sw * 0.6, sd * 0.6, p.yaw), p.y, p.y + sh, p.m)
                }
                // People, stalls, boats moored, lanterns at their height.
                else -> add(footprint(p.x, p.z, sw * 0.92, sd * 0.92, p.yaw), p.y, p.y + sh, p.m)
            }
        }
        for (m in w.movers) {
            val s = sizes[m.m] ?: continue
            val still = stillOnMiniature(w, m)
            val per = if (still) 0.1 else m.period
            var t = 0.0
            while (t < per) {
                val a = if (still) m.at(stillTime(m)) else m.at(t)
                if (inRegion(r, a[0], a[1], -0.5)) {
                    add(footprint(a[0], a[1], s[0], s[2], a[2] + (Worlds.FWD[m.m] ?: 0.0)), a[3], a[3] + s[1] + 0.1, m.m)
                    if (w.mastBridgeZ != null && m.m == "qm_bigboat") {
                        val ang = Math.toRadians(Worlds.mastAngle(if (still) Worlds.MINI_STILL else m.progress(t)))
                        val h = Math.toRadians(a[2])
                        val hx = a[0] + sin(h) * Worlds.MAST_FWD
                        val hz = a[1] + cos(h) * Worlds.MAST_FWD
                        val ex = hx - sin(ang) * sin(h) * Worlds.MAST_LEN
                        val ez = hz - sin(ang) * cos(h) * Worlds.MAST_LEN
                        val y0 = a[3] + Worlds.MAST_HINGE
                        val y1 = y0 + cos(ang) * Worlds.MAST_LEN
                        // Its yard and lines spread ~1.5 m either side near the top.
                        add(doubleArrayOf(min(hx, ex) - 1.5, max(hx, ex) + 1.5, min(hz, ez) - 1.5, max(hz, ez) + 1.5), y0, y1 + 0.4, "qm_mast")
                    }
                }
                t += 0.5
            }
        }
        // The found thing's name over it (WorldView.found), shown for 5 s: the result panel comes up at once when finding
        // was the last thing to do. Over a still piece; over a mover anywhere along its way.
        val label = Scrolls.ALL.firstOrNull { it.id == w.id }?.tasks?.filterIsInstance<Task.Find>()?.maxOfOrNull { it.label.length }
        if (label != null) {
            // Life size the name is 0.9 m high, 3.5 m over the thing's foot.
            val hh = if (life) 0.45 else LABEL_H / 2 / k
            val hw = (if (life) 0.9 else LABEL_H / k) * (72.0 * label + 32) / 96.0 / 2
            val ly = if (life) 3.5 else 3.0 + 0.03 / k
            fun labelAt(x: Double, y: Double, z: Double) = add(doubleArrayOf(x - hw, x + hw, z - hw, z + hw), y + ly - hh, y + ly + hh, "found label")
            val tp = pieces.firstOrNull { it.find && inRegion(r, it.x, it.z, 0.0) }
            val tm = w.movers.firstOrNull { it.find }
            if (tp != null) labelAt(tp.x, tp.y, tp.z)
            else if (tm != null) {
                if (stillOnMiniature(w, tm)) tm.at(stillTime(tm)).let { a -> labelAt(a[0], a[3], a[1]) }
                else {
                    var t = 0.0
                    while (t < tm.period) {
                        val a = tm.at(t)
                        if (inRegion(r, a[0], a[1], -0.5)) labelAt(a[0], a[3], a[1])
                        t += 0.5
                    }
                }
            }
        }
        // 上元夜 (the gold reward, 虹桥 at night: WorldView.night): red lanterns along both banks; sky lanterns rising from
        // the river, on the miniature only those beyond the panels ([NIGHT_FAR]), each over its whole rise and sway.
        if (night) {
            for ((x, z) in RED_LANTERNS) add(doubleArrayOf(x - 0.5, x + 0.5, z - 0.5, z + 0.5), 2.4, 4.0, "red lantern")
            for (l in skyLanterns()) {
                if (!life && oz + k * l[1] >= NIGHT_FAR) continue
                add(doubleArrayOf(l[0] - 1.0, l[0] + 1.0, l[1] - 0.4, l[1] + 0.4), 2.6, 3.0 + 32.0 + 0.6, "sky lantern")
            }
        }
        return out
    }

    /** The found thing's name's height on the table (WorldView.labelScale: 3.5 cm whatever the scale). */
    const val LABEL_H = 0.035

    /** On the miniature, sky lanterns rise only where they stand beyond every panel (gallery z, m). */
    const val NIGHT_FAR = -0.92

    /** Where the red lanterns hang on 虹桥's banks at night (x, z; 3.2 m up). */
    val RED_LANTERNS: List<Pair<Double, Double>> = listOf(-6.0, -9.0, -12.0, -21.0, -24.0).flatMap { z -> listOf(-9.5 to z, 9.5 to z) }

    /** The 36 sky lanterns of the lantern night, as WorldView.night makes them: x, z, start height, rising speed, sway. */
    fun skyLanterns(): List<DoubleArray> {
        val rnd = kotlin.random.Random(3)
        return List(36) {
            val a = rnd.nextDouble() * 2 * Math.PI
            val r = 6 + rnd.nextDouble() * 30
            doubleArrayOf(r * sin(a), -abs(r * cos(a)) - 4, rnd.nextDouble() * 25, 0.25 + rnd.nextDouble() * 0.35, rnd.nextDouble() * 6)
        }
    }

    /**
     * 研色画室 (卷二's pigment workshop, render/GalleryView.buildBench): the bench before the player in the gallery, with its
     * mortar and pestle, the bowl and stirring stick, the jar, the little mountain on its stand and the three dishes
     * (their resting places; cylinders as wide as their scale, both ways). The result panel stood behind the mountain.
     */
    fun benchContent(): List<Box> {
        val y = -0.48
        val z = -0.62
        return listOf(
            Box(-0.45, y - 0.04, z - 0.21, 0.45, y, z + 0.21, "bench"),
            Box(-0.35, y, z - 0.07, -0.21, y + 0.065, z + 0.07, "mortar"),
            Box(-0.295, y, z - 0.015, -0.265, y + 0.205, z + 0.015, "pestle"),
            Box(-0.14, y, z - 0.08, 0.02, y + 0.055, z + 0.08, "bowl"),
            Box(-0.068, y, z - 0.008, -0.052, y + 0.175, z + 0.008, "stick"),
            Box(0.03, y, z + 0.04 - 0.05, 0.13, y + 0.075, z + 0.04 + 0.05, "jar"),
            // The stand: paper 0.3 x 0.3 round (0.26, y + 0.16), its band names 0.18 m to the right, its foot board.
            Box(0.11, y, z - 0.14, 0.48, y + 0.32, z - 0.07, "mountain stand"),
            Box(-0.03, y, z + 0.13 - 0.045, 0.235, y + 0.05, z + 0.13 + 0.045, "dishes"),
        )
    }

    private val BOATS = setOf("qm_cargo", "qm_bigboat", "qm_canopy", "qm_rowboat", "ql_fishboat")

    /** Whether panel [front] stands between the eyes and any part of panel [back] (their rectangles, as seen). */
    fun covers(front: Panel, back: Panel): Boolean {
        val (cb, rb, ub) = axes(back, 0.0)
        val (cf, rf, uf) = axes(front, 0.0)
        val n = doubleArrayOf(rf[1] * uf[2] - rf[2] * uf[1], rf[2] * uf[0] - rf[0] * uf[2], rf[0] * uf[1] - rf[1] * uf[0])
        for (i in 0..12) for (j in 0..7) {
            val u = (i / 12.0 - 0.5) * back.w
            val v = (j / 7.0 - 0.5) * back.h
            val d = doubleArrayOf(cb[0] + rb[0] * u + ub[0] * v, cb[1] + rb[1] * u + ub[1] * v, cb[2] + rb[2] * u + ub[2] * v)
            val den = n[0] * d[0] + n[1] * d[1] + n[2] * d[2]
            if (kotlin.math.abs(den) < 1e-9) continue
            val t = (n[0] * cf[0] + n[1] * cf[1] + n[2] * cf[2]) / den
            if (t <= 0 || t >= 1) continue
            val q = doubleArrayOf(d[0] * t - cf[0], d[1] * t - cf[1], d[2] * t - cf[2])
            val qu = q[0] * rf[0] + q[1] * rf[1] + q[2] * rf[2]
            val qv = q[0] * uf[0] + q[1] * uf[1] + q[2] * uf[2]
            if (kotlin.math.abs(qu) <= front.w / 2 && kotlin.math.abs(qv) <= front.h / 2) return true
        }
        return false
    }

    /** A panel as a thin box (for the panels' own overlap check): its rectangle's bounds, 2 cm thick. */
    fun slab(p: Panel): Box {
        val (c, right, up) = axes(p, 0.0)
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>()
        for (u in listOf(-0.5, 0.5)) for (v in listOf(-0.5, 0.5)) {
            xs += c[0] + right[0] * u * p.w + up[0] * v * p.h
            ys += c[1] + right[1] * u * p.w + up[1] * v * p.h
            zs += c[2] + right[2] * u * p.w + up[2] * v * p.h
        }
        return Box(xs.min() - 0.01, ys.min() - 0.01, zs.min() - 0.01, xs.max() + 0.01, ys.max() + 0.01, zs.max() + 0.01, "panel ${p.id}")
    }

    /** 虹桥's big boat lies still on the miniature (Worlds.MINI_STILL). */
    fun stillOnMiniature(w: World, m: Mover) = w.mastBridgeZ != null && m.m == "qm_bigboat"

    /** The time at which a mover is at [Worlds.MINI_STILL] of its run. */
    fun stillTime(m: Mover): Double = if (m.speed > 0) (Worlds.MINI_STILL - m.phase) * m.length / m.speed else 0.0

    /** The panel's corners' frame: its middle, its right and up directions (after [dy] up). */
    private fun axes(p: Panel, dy: Double): Array<DoubleArray> {
        val pa = Math.toRadians(p.pitch)
        val ya = Math.toRadians(p.yaw)
        // Up: (0, cos p, sin p) turned by yaw; right: (cos y, 0, -sin y).
        val up = doubleArrayOf(sin(ya) * sin(pa), cos(pa), cos(ya) * sin(pa))
        val right = doubleArrayOf(cos(ya), 0.0, -sin(ya))
        return arrayOf(doubleArrayOf(p.x, p.y + dy, p.z), right, up)
    }

    /** Where the ray from the eyes toward [d] (length 1 = the point) first enters [b], or null. */
    private fun hit(d: DoubleArray, b: Box): Double? {
        var t0 = 0.0
        var t1 = 1.0
        val lo = doubleArrayOf(b.x0, b.y0, b.z0)
        val hi = doubleArrayOf(b.x1, b.y1, b.z1)
        for (i in 0..2) {
            if (abs(d[i]) < 1e-12) {
                if (0.0 < lo[i] || 0.0 > hi[i]) return null
            } else {
                var a = lo[i] / d[i]
                var c = hi[i] / d[i]
                if (a > c) { val s = a; a = c; c = s }
                t0 = max(t0, a)
                t1 = min(t1, c)
                if (t0 > t1) return null
            }
        }
        return t0
    }

    /**
     * What in [content] stands between the eyes and panel [p] raised by [dy], or goes through it: rays from the eyes
     * to points over the panel (its frame included) that meet a box before the panel (within 1 cm of it counts).
     */
    fun blockers(p: Panel, content: List<Box>, dy: Double = 0.0): List<String> {
        val (c, right, up) = axes(p, dy)
        val out = LinkedHashSet<String>()
        val nu = 12
        val nv = 7
        for (i in 0..nu) for (j in 0..nv) {
            val u = (i.toDouble() / nu - 0.5) * p.w
            val v = (j.toDouble() / nv - 0.5) * p.h
            val pt = doubleArrayOf(c[0] + right[0] * u + up[0] * v, c[1] + right[1] * u + up[1] * v, c[2] + right[2] * u + up[2] * v)
            val len = kotlin.math.sqrt(pt[0] * pt[0] + pt[1] * pt[1] + pt[2] * pt[2])
            val margin = 0.01 / len
            for (b in content) {
                val t = hit(pt, b) ?: continue
                if (t < 1.0 + margin) out += b.what
            }
        }
        return out.toList()
    }

    /** The highest a panel's middle may go: below the eyes, so it is read looking a little down. */
    const val MAX_Y = -0.06

    /**
     * Where each panel shown with [w]'s miniature stands, clear of it: raised as little as possible, and the result panel
     * (before the middle of the table) brought a little nearer as well when raising alone would take it up to the eyes
     * (山居's peak stands in the table's middle). Panels not shown with the miniature keep their places.
     */
    fun placement(w: World, sizes: Map<String, DoubleArray>, night: Boolean = false, shapes: Map<String, Shape> = emptyMap()): Map<String, Panel> =
        placementOver(content(w, sizes, night, shapes = shapes))

    /** Panels clear of the studio bench (shown with it: TASK, RESULT, NOTE, MENU). */
    fun benchPlacement(): Map<String, Panel> = placementOver(benchContent())

    /** Where each panel stands clear of [content] (see [placement]). */
    fun placementOver(all: List<Box>): Map<String, Panel> {
        val out = LinkedHashMap<String, Panel>()
        for (p in PANELS) {
            if (p.id !in SHOWN_WITH_MINIATURE) { out[p.id] = p; continue }
            // Moves tried besides raising: the result panel nearer; the menu tab nearer the body and further out.
            val moves = when (p.id) {
                "result" -> listOf(0.0, 0.06, 0.12, 0.18, 0.24).map { 0.0 to it }
                "menu" -> listOf(0.0 to 0.0, 0.0 to 0.06, -0.06 to 0.06, -0.06 to 0.1, -0.06 to 0.16, -0.12 to 0.12, -0.12 to 0.18)
                else -> listOf(0.0 to 0.0)
            }
            val ymax = if (p.id == "menu") -0.4 else MAX_Y
            // Only what lies between the eyes and anywhere this panel may go can be in its way.
            val reach = moves.flatMap { (dx, dz) -> listOf(p.copy(x = p.x + dx, z = p.z + dz), p.copy(x = p.x + dx, y = ymax, z = p.z + dz)) }.map { slab(it) }
            val bx0 = min(0.0, reach.minOf { it.x0 }); val bx1 = max(0.0, reach.maxOf { it.x1 })
            val by0 = min(0.0, reach.minOf { it.y0 }); val by1 = max(0.0, reach.maxOf { it.y1 })
            val bz0 = min(0.0, reach.minOf { it.z0 }); val bz1 = max(0.0, reach.maxOf { it.z1 })
            val content = all.filter { it.x1 >= bx0 && it.x0 <= bx1 && it.y1 >= by0 && it.y0 <= by1 && it.z1 >= bz0 && it.z0 <= bz1 }
            var best: Panel? = null
            var bestCost = Double.MAX_VALUE
            for ((dx, dz) in moves) {
                var dy = 0.0
                while (p.y + dy <= ymax + 1e-9) {
                    val c = p.copy(x = p.x + dx, y = p.y + dy, z = p.z + dz)
                    if (blockers(c, content).isEmpty()) {
                        val cost = dy + 0.8 * (dz + kotlin.math.abs(dx))
                        if (cost < bestCost) { bestCost = cost; best = c }
                        break
                    }
                    dy += 0.01
                }
            }
            out[p.id] = best ?: p
        }
        return out
    }

    /** How much higher (m) panel [p] must stand over [w]'s miniature to be clear of it (0 if it is; at most 0.4). */
    fun lift(w: World, sizes: Map<String, DoubleArray>, p: Panel): Double {
        val content = content(w, sizes)
        var dy = 0.0
        while (dy < 0.4 && blockers(p, content, dy).isNotEmpty()) dy += 0.01
        return dy
    }

    /** Each panel's lift for [w] (the ones shown with the miniature). */
    fun lifts(w: World, sizes: Map<String, DoubleArray>): Map<String, Double> {
        val content = content(w, sizes)
        return PANELS.filter { it.id in SHOWN_WITH_MINIATURE }.associate { p ->
            var dy = 0.0
            while (dy < 0.4 && blockers(p, content, dy).isNotEmpty()) dy += 0.01
            p.id to dy
        }
    }
}
