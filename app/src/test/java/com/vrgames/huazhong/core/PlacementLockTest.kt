package com.vrgames.huazhong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Placement is locked (2026-11-23, after the device test: tables moved in new versions without being asked and ended
 * inside the player's body). Where everything sits relative to the player's eyes must stay as in 0.3.3, the last
 * version passed on the device (2026-11-18; source copy Pico_Vibe_Lab/提交包_20261118b/HuaZhongYou). The numbers are
 * read from the current source and held to 0.3.3's; and nothing solid in front comes within 0.30 m of the eyes.
 */
class PlacementLockTest {
    private val src = File("src/main/java/com/vrgames/huazhong")
    private val worldView = File(src, "render/WorldView.kt").readText()
    private val galleryView = File(src, "render/GalleryView.kt").readText()
    private val stage = File(src, "content/GalleryStage.kt").readText()

    private fun const(text: String, name: String): Double =
        Regex("const val $name = (-?[0-9.]+)f?").find(text)?.groupValues?.get(1)?.toDouble() ?: error("$name not found")

    /** The panels' put(PANEL_X, Vector3(x, y, z), pitch[, yaw]) in the eye frame. */
    private fun panels(): Map<String, DoubleArray> = Regex("""put\((PANEL_\w+), Vector3\((-?[0-9.]+)f, (-?[0-9.]+)f, (-?[0-9.]+)f\), (-?[0-9.]+)f(?:, (-?[0-9.]+)f)?\)""")
        .findAll(stage).associate { m ->
            val g = m.groupValues
            g[1] to doubleArrayOf(g[2].toDouble(), g[3].toDouble(), g[4].toDouble(), g[5].toDouble(), g[6].ifEmpty { "0" }.toDouble())
        }

    companion object {
        /** 0.3.3 (device-approved 2026-11-18): the miniature's table, eye frame (m). */
        const val MINI_W = 1.1
        const val MINI_D = 0.78
        const val MINI_BELOW_EYE = 0.45
        const val MINI_NEAR = 0.42
        /** The board's rim beyond the miniature's ground, on the table (m). */
        const val RIM = 0.018
        /** The gallery's scroll wall radius, the pigment bench's top and middle (eye frame, m) and its depth. */
        const val R0 = 2.0
        const val BENCH_Y = -0.48
        const val BENCH_Z = -0.62
        const val BENCH_DEPTH = 0.42
        /** The panels: x, y, z, pitch, yaw. */
        val PANELS = mapOf(
            "PANEL_CARD" to doubleArrayOf(0.0, -0.26, -0.85, -12.0, 0.0),
            "PANEL_RESULT" to doubleArrayOf(0.0, -0.24, -0.82, -12.0, 0.0),
            "PANEL_HOME" to doubleArrayOf(0.0, -0.5, -0.78, -30.0, 0.0),
            "PANEL_TASK" to doubleArrayOf(-0.74, -0.22, -0.5, -18.0, 50.0),
            "PANEL_NOTE" to doubleArrayOf(0.72, -0.2, -0.52, -15.0, -50.0),
            "PANEL_MENU" to doubleArrayOf(-0.6, -0.58, -0.4, -35.0, 50.0),
        )
        const val BODY_CLEAR = 0.30
    }

    @Test
    fun miniatureTableStaysWhere033PutIt() {
        assertEquals(MINI_W, const(worldView, "MINI_W"), 1e-9)
        assertEquals(MINI_D, const(worldView, "MINI_D"), 1e-9)
        assertEquals(MINI_BELOW_EYE, const(worldView, "MINI_BELOW_EYE"), 1e-9)
        assertEquals(MINI_NEAR, const(worldView, "MINI_NEAR"), 1e-9)
        assertTrue("the miniature's scale cap (1:30) unchanged", worldView.contains("coerceAtMost(1f / 30f)"))
        assertTrue("the board's rim unchanged", worldView.contains("val rim = $RIM / k"))
        // The table's middle: below the eyes by MINI_BELOW_EYE, its near edge MINI_NEAR ahead (place()).
        assertTrue(worldView.contains("val ty = -MINI_BELOW_EYE") && worldView.contains("val tz = -(MINI_NEAR + depth / 2)") && worldView.contains("val tx = 0f"))
    }

    @Test
    fun galleryBenchAndPanelsStayWhere033PutThem() {
        assertEquals(R0, const(galleryView, "R0"), 1e-9)
        assertEquals(BENCH_Y, const(galleryView, "BENCH_Y"), 1e-9)
        assertEquals(BENCH_Z, const(galleryView, "BENCH_Z"), 1e-9)
        assertTrue("bench top 0.9 × $BENCH_DEPTH m", galleryView.contains("setScaleVector(Vector3(0.9f, 0.04f, ${BENCH_DEPTH}f))"))
        val now = panels()
        for ((id, v) in PANELS) {
            val p = now[id] ?: error("$id not placed")
            for (i in v.indices) assertEquals("$id[$i]", v[i], p[i], 1e-9)
        }
        // Seated at the eyes, facing the settled heading, never following the head.
        assertTrue(stage.contains("eyeY = eye.y.coerceIn(0.9f, 1.8f)") && stage.contains("v.setPos(at)"))
    }

    @Test
    fun nothingSolidWithin30cmOfTheEyes() {
        val miniEdge = MINI_NEAR - RIM
        val benchEdge = -BENCH_Z - BENCH_DEPTH / 2
        assertTrue("miniature's near edge $miniEdge m", miniEdge >= BODY_CLEAR)
        assertTrue("bench's near edge $benchEdge m", benchEdge >= BODY_CLEAR)
        for ((id, v) in PANELS) assertTrue("$id ${hypot(v[0], v[2])} m", hypot(v[0], v[2]) >= BODY_CLEAR)
        // Walked in: every standing model at least 2.5 m off, every mover 1.8 m (WorldsTest), 0.8 m clear (LayoutTest).
        for (w in Worlds.ALL.values) for (p in w.pieces) assertTrue("${w.id} ${p.m}", hypot(p.x, p.z) >= 2.5)
        println("placement: miniature near edge ${"%.3f".format(miniEdge)} m ahead, ${MINI_BELOW_EYE} m below the eyes; bench near edge ${"%.2f".format(benchEdge)} m; nearest panel ${"%.2f".format(PANELS.values.minOf { hypot(it[0], it[2]) })} m")
    }
}
