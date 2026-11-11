package com.vrgames.huazhong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every section can be seen through, misses cost stars, sections open in order, spots lie on the picture. */
class ScrollsTest {
    private fun solve(s: Section, misses: Int = 0): Visit {
        val v = Visit(s)
        repeat(misses) { v.find(false); v.quiz(99) }
        var guard = 0
        while (!v.done && guard++ < 100) {
            when (val t = v.task!!) {
                is Task.Find -> v.find(true)
                is Task.Restore -> v.restore(true)
                is Task.Quiz -> v.quiz(t.answer)
                is Task.Rub -> v.rub(0.1)
                is Task.Match -> t.pairs.forEach { (d, r) -> v.match(d, r) }
            }
        }
        return v
    }

    @Test
    fun everySectionCanBeSeenThroughWithThreeStars() {
        assertEquals(6, Scrolls.QINGMING.size)
        assertEquals(4, Scrolls.QIANLI.size)
        for (s in Scrolls.ALL) {
            val v = solve(s)
            assertTrue(s.id, v.done)
            assertEquals(s.id, 3, v.stars())
            assertTrue("${s.id} has a find task", s.bench || s.tasks.first() is Task.Find)
        }
    }

    @Test
    fun missesCostStars() {
        val s = Scrolls.QINGMING[0]
        val v = Visit(s)
        assertEquals(Visit.Answer.Wrong, v.find(false))
        assertEquals(Visit.Answer.Right, v.find(true))
        assertEquals(Visit.Answer.Wrong, v.quiz(1))
        assertEquals(Visit.Answer.Wrong, v.quiz(2))
        assertEquals(Visit.Answer.Right, v.quiz(0))
        assertTrue(v.done)
        assertEquals(1, v.stars())
        // A wrong colour on the mountain is a miss and does not count.
        val bench = Visit(Scrolls.QIANLI.last())
        while (bench.task !is Task.Match) when (val t = bench.task!!) { is Task.Rub -> bench.rub(1.0); is Task.Quiz -> bench.quiz(t.answer); else -> error("$t") }
        assertEquals(Visit.Answer.Wrong, bench.match("石青", "山脚"))
        assertTrue(bench.matched.isEmpty())
    }

    @Test
    fun sectionsOpenInOrderWithinEachScroll() {
        val p = Progress()
        assertTrue(p.open(Scrolls.QINGMING[0]))
        assertTrue("卷二 is open from the start", p.open(Scrolls.QIANLI[0]))
        assertFalse(p.open(Scrolls.QINGMING[1]))
        p.record("qm1", 2)
        assertTrue(p.open(Scrolls.QINGMING[1]))
        for (s in Scrolls.QINGMING) p.record(s.id, 3)
        assertTrue(p.bronze)
        assertFalse(p.silver)
        for (s in Scrolls.QIANLI) p.record(s.id, 3)
        assertTrue(p.gold)
        assertEquals(p.best, Progress.load(p.save()).best)
    }

    @Test
    fun spotsAndPiecesLieOnThePictureAndApart() {
        for (s in Scrolls.ALL) for (t in s.tasks) {
            val spot = when (t) { is Task.Find -> t.spot; is Task.Restore -> t.piece; else -> null } ?: continue
            assertTrue("${s.id} $spot", spot.u0 >= 0 && spot.u1 <= 1 && spot.v0 >= 0 && spot.v1 <= 1 && spot.u0 < spot.u1 && spot.v0 < spot.v1)
        }
        // The thing to find and the piece that falls out are different places.
        for (s in Scrolls.ALL) {
            val f = s.tasks.filterIsInstance<Task.Find>().firstOrNull()?.spot ?: continue
            val r = s.tasks.filterIsInstance<Task.Restore>().firstOrNull()?.piece ?: continue
            assertTrue(s.id, f.u1 <= r.u0 || r.u1 <= f.u0 || f.v1 <= r.v0 || r.v1 <= f.v0)
        }
    }
}
