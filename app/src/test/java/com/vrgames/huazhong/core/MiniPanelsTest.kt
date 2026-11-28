package com.vrgames.huazhong.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.5.2 (device 2026-11-28: a miniature bridge through 虹桥's result panel; 「也检查一下其他关卡的各种面板，是否也有这种遮挡
 * 或穿模的情况」): with each world's miniature on the table, every panel shown with it is clear of it, at the height the
 * stage puts it (its place in [MiniPanels.PANELS] raised by [MiniPanels.lifts]): nothing goes through a panel or
 * stands between the eyes and it, movers along their whole ways and 虹桥's mast at every angle included.
 */
class MiniPanelsTest {
    private val sizes: Map<String, DoubleArray> = File("src/main/assets/scenes/sizes.txt").readLines().mapNotNull { l ->
        val f = l.trim().split(Regex("\\s+"))
        if (f.size >= 4) f[0] to doubleArrayOf(f[1].toDouble(), f[2].toDouble(), f[3].toDouble()) else null
    }.toMap()
    private val shapes = MiniPanels.parseShapes(File("src/main/assets/scenes/shapes.txt").readLines())

    @Test
    fun panelsClearOfEveryMiniature() {
        val problems = ArrayList<String>()
        for (w in Worlds.ALL.values) {
            val content = MiniPanels.content(w, sizes, shapes = shapes)
            val t0 = System.nanoTime()
            val placed = MiniPanels.placement(w, sizes, shapes = shapes)
            println("${w.id}: placement %.0f ms, ${content.size} boxes".format((System.nanoTime() - t0) / 1e6))
            for (p in MiniPanels.PANELS.filter { it.id in MiniPanels.SHOWN_WITH_MINIATURE }) {
                val before = MiniPanels.blockers(p, content)
                val q = placed[p.id]!!
                val after = MiniPanels.blockers(q, content)
                println("${w.id} ${p.id}: in its place blocked by $before; at y %.2f z %.2f -> $after".format(q.y, q.z))
                if (after.isNotEmpty()) problems += "${w.id} ${p.id}: still blocked by $after"
                if (q.y > MiniPanels.MAX_Y + 1e-6) problems += "${w.id} ${p.id}: raised to %.2f, too near the eyes' level".format(q.y)
            }
            // No two panels in each other's way: their rectangles, seen from the eyes, do not overlap.
            val shown = placed.values.filter { it.id in MiniPanels.SHOWN_WITH_MINIATURE }
            for (i in shown.indices) for (j in i + 1 until shown.size) {
                val a = shown[i]; val b = shown[j]
                if (MiniPanels.covers(a, b) || MiniPanels.covers(b, a))
                    problems += "${w.id}: panels ${a.id} and ${b.id} overlap"
            }
        }
        println("PROBLEMS\n" + problems.joinToString("\n"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** 上元夜 (虹桥 at night): the red lanterns and the sky lanterns rising beyond the panels. */
    @Test
    fun panelsClearOfTheLanternNight() {
        val w = Worlds.ALL.values.first { it.id == "qm3" }
        val content = MiniPanels.content(w, sizes, night = true, shapes = shapes)
        val placed = MiniPanels.placement(w, sizes, night = true, shapes = shapes)
        val shown = MiniPanels.skyLanterns().count { MiniPanels.transform(w)[3] + MiniPanels.transform(w)[0] * it[1] < MiniPanels.NIGHT_FAR }
        println("上元夜: $shown of 36 sky lanterns rise on the miniature")
        assertTrue("some lanterns still rise on the miniature", shown >= 8)
        for (p in placed.values.filter { it.id in MiniPanels.SHOWN_WITH_MINIATURE }) {
            val b = MiniPanels.blockers(p, content)
            println("qm3 night ${p.id} at y %.2f z %.2f -> $b".format(p.y, p.z))
            assertTrue("qm3 night ${p.id}: $b", b.isEmpty())
        }
    }

    /** 研色画室: the panels shown with the pigment bench stand clear of its things. */
    @Test
    fun panelsClearOfTheBench() {
        val content = MiniPanels.benchContent()
        val base = MiniPanels.PANELS.associateBy { it.id }
        for (p in MiniPanels.benchPlacement().values.filter { it.id in MiniPanels.SHOWN_WITH_MINIATURE }) {
            println("bench ${p.id}: in its place blocked by ${MiniPanels.blockers(base.getValue(p.id), content)}; at y %.2f z %.2f -> ${MiniPanels.blockers(p, content)}".format(p.y, p.z))
            assertTrue("bench ${p.id}", MiniPanels.blockers(p, content).isEmpty())
            assertTrue("bench ${p.id} below the eyes", p.y <= MiniPanels.MAX_Y + 1e-6)
        }
    }

    /**
     * Walked in (life size, the panels in their usual places): nothing of the world, moving or not, stands between the
     * eyes and a panel shown there (task, result, note, menu), seated low or standing tall.
     */
    @Test
    fun panelsClearLifeSize() {
        val problems = ArrayList<String>()
        for (w in Worlds.ALL.values) for (night in listOf(false, true)) {
            if (night && w.id != "qm3") continue
            for (eye in listOf(0.9, 1.35, 1.8)) {
                val content = MiniPanels.content(w, sizes, night, life = true, eyeY = eye, shapes = shapes)
                for (p in MiniPanels.PANELS.filter { it.id in MiniPanels.SHOWN_WITH_MINIATURE }) {
                    val b = MiniPanels.blockers(p, content)
                    if (b.isNotEmpty()) problems += "${w.id}${if (night) " night" else ""} eyes ${eye}: ${p.id} blocked by $b"
                }
            }
        }
        println("life size: " + problems.ifEmpty { listOf("every panel clear in every world") }.joinToString("\n"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * 放回去 (life size): the player holds the piece on the line from the eyes to its glowing place and lets go; it flies
     * out along that line (WorldView.restored). That line may not pass through a panel shown meanwhile (task, note, menu).
     */
    @Test
    fun putBackLineClearOfPanels() {
        for (w in Worlds.ALL.values) {
            val r = w.restore ?: continue
            for (eye in listOf(0.9, 1.35, 1.8)) {
                // The home's aim point (h.y + 1) in the eye frame.
                val d = doubleArrayOf(r.x, r.y + 1.0 - eye, r.z)
                for (p in MiniPanels.PANELS.filter { it.id in setOf("task", "note", "menu") }) {
                    val hit = crosses(p, d, 0.05)
                    println("${w.id} put-back line (eyes $eye) vs ${p.id}: ${if (hit) "THROUGH" else "clear"}")
                    assertTrue("${w.id}: the put-back line passes through ${p.id}", !hit)
                }
            }
        }
    }

    /** Whether the segment from the eyes to [d] passes through panel [p] (grown by [m] all round). */
    private fun crosses(p: MiniPanels.Panel, d: DoubleArray, m: Double): Boolean {
        val pa = Math.toRadians(p.pitch)
        val ya = Math.toRadians(p.yaw)
        val up = doubleArrayOf(kotlin.math.sin(ya) * kotlin.math.sin(pa), kotlin.math.cos(pa), kotlin.math.cos(ya) * kotlin.math.sin(pa))
        val right = doubleArrayOf(kotlin.math.cos(ya), 0.0, -kotlin.math.sin(ya))
        val n = doubleArrayOf(right[1] * up[2] - right[2] * up[1], right[2] * up[0] - right[0] * up[2], right[0] * up[1] - right[1] * up[0])
        val c = doubleArrayOf(p.x, p.y, p.z)
        val den = n[0] * d[0] + n[1] * d[1] + n[2] * d[2]
        if (kotlin.math.abs(den) < 1e-12) return false
        val t = (n[0] * c[0] + n[1] * c[1] + n[2] * c[2]) / den
        if (t <= 0 || t >= 1) return false
        val q = doubleArrayOf(d[0] * t - c[0], d[1] * t - c[1], d[2] * t - c[2])
        val u = q[0] * right[0] + q[1] * right[1] + q[2] * right[2]
        val v = q[0] * up[0] + q[1] * up[1] + q[2] * up[2]
        return kotlin.math.abs(u) <= p.w / 2 + m && kotlin.math.abs(v) <= p.h / 2 + m
    }
}
