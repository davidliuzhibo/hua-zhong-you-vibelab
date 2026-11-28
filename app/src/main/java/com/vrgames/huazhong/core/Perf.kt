package com.vrgames.huazhong.core

import kotlin.math.hypot

/**
 * What a section draws (v0.4.0; PICO 2026-11-23: 「运行时帧数偏低」, device 2026-11-18: 30–50 fps). The models' triangles
 * per file come from assets scenes/tris.txt (tools/model_tris.py); the 0.3.3 table (tools/tris_v033.txt, also in
 * assets for the log) gives the before. [count] follows WorldView's rules for each version: which version of each
 * standing model shows (near / far by distance at life size, far on the miniature), the walkers' one frame, the
 * boats, the piece to put back, 虹桥's mast. The stage logs both at a section's start (HuaPerf) and the unit tests
 * hold the new one to its budget.
 */
object Perf {
    /** "<file> <triangles> [bytes]" per line, # comments. A walk cycle's triangles are one frame's. */
    fun parse(lines: List<String>): Map<String, Int> = lines.mapNotNull { l ->
        if (l.startsWith("#") || l.isBlank()) return@mapNotNull null
        val p = l.trim().split(Regex("\\s+"))
        if (p.size >= 2) p[0] to p[1].toInt() else null
    }.toMap()

    /** A version's rules: near within [lodFar] m at life size; [boatLod]: moving boats and the piece to put back switch versions too (0.4.0). */
    data class Rules(val label: String, val lodFar: Double, val boatLod: Boolean)

    val V033 = Rules("0.3.3", 18.0, false)
    val V040 = Rules("0.4.0", 12.0, true)

    data class Count(val tris: Long, val draws: Int) {
        operator fun plus(o: Count) = Count(tris + o.tris, draws + o.draws)
    }

    /**
     * The models [w] draws at life size ([mini] false) or as the miniature: [extras] its dressing (Dressing.extras),
     * [region] the miniature's part (Dressing.regionOf), [tris] the files' triangles. Movers count at the middle of
     * their way; on the miniature all of them (they come and go across its edge).
     */
    fun count(w: World, extras: List<Piece>, region: DoubleArray, tris: Map<String, Int>, mini: Boolean, r: Rules): Count {
        var t = 0L
        var d = 0
        fun add(m: String) { t += tris[m] ?: 0; d++ }
        fun inRegion(x: Double, z: Double) = x >= region[0] && x <= region[1] && z >= region[2] && z <= region[3]
        for (p in w.pieces + extras) {
            val lo = "${p.m}_lo"
            if (mini) {
                if (inRegion(p.x, p.z)) add(if (lo in tris) lo else p.m)
            } else add(if (lo in tris && hypot(p.x, p.z) > r.lodFar) lo else p.m)
        }
        for (m in w.movers) {
            val walk = "${m.m}_walk"
            val lo = "${m.m}_lo"
            val mid = m.at(m.length / 2 / m.speed)
            when {
                // v0.5.2: on the miniature a walker shows its light version (WorldView.WalkerLod).
                walk in tris && mini && r.boatLod && lo in tris -> add(lo)
                walk in tris -> add(walk)
                m.boat && r.boatLod && lo in tris && (mini || hypot(mid[0], mid[1]) > r.lodFar) -> add(lo)
                else -> add(m.m)
            }
        }
        w.restore?.let { rs -> add(if (r.boatLod && "${rs.m}_lo" in tris && (mini || hypot(rs.x, rs.z) > r.lodFar)) "${rs.m}_lo" else rs.m) }
        if (w.mastBridgeZ != null) add("qm_mast")
        // v0.5.1: the ground round the player made for real (life size only).
        if (!mini && "ng_${w.id}" in tris) add("ng_${w.id}")
        return Count(t, d)
    }

    /** The water's share at 0.4.0: each surface's one frame shown, its shore bands and the boats' wakes. */
    fun water(w: World, region: DoubleArray, k: Double, mini: Boolean): Count {
        var t = 0L
        var d = 0
        for (s in Waves.surfaces(w, region, k, mini)) {
            t += Waves.field(s.water, w.water, s.spec, s.box, s.step, if (mini) 0.0 else 0.06, s.maxStep, edgeRamp = s.edgeRamp).triangles
            d += 1
            for (b in Waves.bands(s.water, w.water, s.spec, s.box, s.shoreWidth, mini, s.lift, s.edgeRamp)) { t += b.idx.size / 3; d++ }
        }
        val boats = w.movers.count { it.boat }
        return Count(t + 2L * boats, d + boats)
    }
}
