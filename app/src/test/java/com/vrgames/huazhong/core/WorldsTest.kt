package com.vrgames.huazhong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.2 入画: every painted section has a world, with what its tasks need; movers go where they should. */
class WorldsTest {
    @Test
    fun everyPaintingHasAWorldWithItsTasks() {
        for (s in Scrolls.ALL.filter { !it.bench }) {
            val w = Worlds.of(s.id)
            assertNotNull("${s.id} has a world", w)
            w!!
            if (s.tasks.any { it is Task.Find }) assertTrue("${s.id}: something to find", w.pieces.any { it.find } || w.movers.any { it.find } || w.findFall)
            if (s.tasks.any { it is Task.Restore }) assertNotNull("${s.id}: a piece to put back", w.restore)
        }
        assertEquals("nine paintings to step into", 9, Worlds.ALL.size)
    }

    @Test
    fun everyModelIsPrepared() {
        // The prep list (tools/scene_models.tsv) covers every model a world uses.
        val tsv = File("../../tools/scene_models.tsv").takeIf { it.exists() } ?: File("../tools/scene_models.tsv")
        val listed = tsv.readLines().filter { !it.startsWith("#") && it.isNotBlank() }.map { it.split("\t")[0] }.toSet()
        for (w in Worlds.ALL.values) for (m in w.models) assertTrue("${w.id}: $m in scene_models.tsv", m in listed)
        assertTrue("lanterns for 上元夜", "lantern_sky" in listed && "lantern_red" in listed)
    }

    @Test
    fun moversGoAlongTheirPaths() {
        val m = Mover("x", listOf(0.0 to 0.0, 10.0 to 0.0), 1.0, loop = false)
        val a = m.at(5.0)
        assertEquals(5.0, a[0], 1e-9)
        assertEquals("facing +x", 90.0, a[2], 1e-9)
        val back = m.at(15.0)
        assertEquals("back and forth", 5.0, back[0], 1e-9)
        assertEquals("facing back", 270.0, back[2], 1e-9)
        val bridge = Mover("p", listOf(-13.0 to 0.0, 13.0 to 0.0), 1.0, loop = false, arc = 6.0)
        assertEquals("highest in the middle of the bridge", 6.0, bridge.at(13.0)[3], 1e-9)
        assertEquals("on the ground at its ends", 0.0, bridge.at(0.0)[3], 1e-9)
    }

    @Test
    fun nothingStandsOnThePlayer() {
        for (w in Worlds.ALL.values) {
            for (p in w.pieces) assertTrue("${w.id}: ${p.m} at ${p.x},${p.z} too near", kotlin.math.hypot(p.x, p.z) > 2.5)
            for (m in w.movers) for (k in 0..40) {
                val a = m.at(k * m.length / 40 / m.speed)
                assertTrue("${w.id}: ${m.m} passes too near", kotlin.math.hypot(a[0], a[1]) > 1.8)
            }
        }
    }
}
