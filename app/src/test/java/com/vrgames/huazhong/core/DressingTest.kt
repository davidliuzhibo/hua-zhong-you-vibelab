package com.vrgames.huazhong.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** v0.3: the kits fill each world out without blocking a road, the water, a walker's way or the player's place. */
class DressingTest {
    // Rough footprints (x, z) like sizes.txt: buildings a few metres, trees and people small.
    private val size: (String) -> Pair<Double, Double>? = { m ->
        when {
            m.startsWith("p_") -> 0.6 to 0.4
            m.startsWith("t_") || m == "ql_pine" -> 6.0 to 6.0
            m == "lantern_red" -> 0.5 to 0.5
            m == "ql_hill" -> 10.0 to 8.0
            else -> 6.0 to 5.0
        }
    }

    @Test
    fun everyKitPlacesSomethingAndKeepsClear() {
        for ((id, _) in Dressing.KITS) {
            val w = Worlds.of(id) ?: continue
            val extra = Dressing.extras(w, size)
            assertTrue("$id: nothing placed", extra.isNotEmpty())
            val r = Dressing.regionOf(w)
            for (p in extra) {
                assertTrue("$id ${p.m} out of its part", p.x >= r[0] && p.x <= r[1] && p.z >= r[2] && p.z <= r[3])
                assertTrue("$id ${p.m} on the player", hypot(p.x, p.z) > Dressing.CLEAR_R)
                val wet = Dressing.KITS.getValue(id).any { it.m == p.m && it.onWater }
                if (!wet) for (wt in w.water) assertTrue("$id ${p.m} in the water",
                    !(p.x > minOf(wt.x0, wt.x1) && p.x < maxOf(wt.x0, wt.x1) && p.z > minOf(wt.z0, wt.z1) && p.z < maxOf(wt.z0, wt.z1)))
                for (s in w.strips) assertTrue("$id ${p.m} on a road",
                    !(p.x > minOf(s.x0, s.x1) && p.x < maxOf(s.x0, s.x1) && p.z > minOf(s.z0, s.z1) && p.z < maxOf(s.z0, s.z1)))
            }
        }
    }

    @Test
    fun sameEveryVisit() {
        val w = Worlds.of("qm1")!!
        assertTrue(Dressing.extras(w, size) == Dressing.extras(w, size))
    }
}
