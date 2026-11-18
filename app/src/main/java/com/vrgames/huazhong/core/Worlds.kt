package com.vrgames.huazhong.core

import kotlin.math.hypot

/**
 * The worlds inside the paintings (v0.2, 2026-11-16, PICO 1116: 「原本以为画中游会进入画里的场景……」). Each
 * painted section has one: the player steps in (入画) and stands at the origin, eyes up, facing -z; the
 * frame is metres, y up. The far scene is the section's painted panorama all round; near it stand the
 * models (Seed3D, real size), the water flows and people, carts, animals and boats go about. Things to find
 * in the painting are found here ([Piece.find], [Mover.find]); a piece fallen out floats before the player
 * to be put back at [Restore].
 */

/** A model standing still. [y] lifts it (a figure on a boat). [scale] on top of its prepared size. */
data class Piece(val m: String, val x: Double, val z: Double, val yaw: Double = 0.0, val y: Double = 0.0, val scale: Double = 1.0, val find: Boolean = false)

/**
 * A model going along [path] (x, z) at [speed] m/s: round and round ([loop]) or back and forth. [arc]: the
 * path is a bridge, the height above the ends at its middle. [phase]: how far along (0..1) it starts.
 * [bob]: the step's up-and-down (people, animals) or a boat's rocking.
 */
data class Mover(
    val m: String,
    val path: List<Pair<Double, Double>>,
    val speed: Double,
    val loop: Boolean = true,
    val y: Double = 0.0,
    val arc: Double = 0.0,
    val phase: Double = 0.0,
    val bob: Double = 0.04,
    val find: Boolean = false,
    val boat: Boolean = false,
) {
    val length: Double get() = path.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second) } + if (loop) hypot(path.first().first - path.last().first, path.first().second - path.last().second) else 0.0

    /** Where it is after [t] seconds: x, z, the way it faces (degrees, 0 = +z) and its height off the path's base. */
    fun at(t: Double): DoubleArray {
        val pts = if (loop) path + path.first() else path
        val len = length
        if (len <= 0) return doubleArrayOf(path[0].first, path[0].second, 0.0, y)
        var d = t * speed + phase * len
        var back = false
        if (loop) d = ((d % len) + len) % len
        else {
            val m = ((d % (2 * len)) + 2 * len) % (2 * len)
            if (m > len) { d = 2 * len - m; back = true } else d = m
        }
        var acc = 0.0
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val l = hypot(b.first - a.first, b.second - a.second)
            if (acc + l >= d || i == pts.size - 2) {
                val f = if (l > 0) ((d - acc) / l).coerceIn(0.0, 1.0) else 0.0
                val x = a.first + (b.first - a.first) * f
                val z = a.second + (b.second - a.second) * f
                var yaw = Math.toDegrees(kotlin.math.atan2(b.first - a.first, b.second - a.second))
                if (back) yaw += 180
                // A bridge: a parabola over the whole path, highest in the middle.
                val g = if (len > 0) (d / len) else 0.0
                val h = y + arc * 4 * g * (1 - g)
                return doubleArrayOf(x, z, yaw, h)
            }
            acc += l
        }
        return doubleArrayOf(path.last().first, path.last().second, 0.0, y)
    }
}

/** Water: a box of [x0]..[x1] by [z0]..[z1] at height [y], flowing along x ([alongX]) or z, [flow] m/s (sign: way). */
data class Water(val x0: Double, val z0: Double, val x1: Double, val z1: Double, val alongX: Boolean, val flow: Double, val y: Double = 0.02, val color: Long = 0xFF4E9AB0)

/** A strip of the ground in another colour: a road, a path. */
data class Strip(val x0: Double, val z0: Double, val x1: Double, val z1: Double, val color: Long)

/** A waterfall: a sheet from [top] down to [bottom] at x, z, [w] wide, facing +z. */
data class Fall(val x: Double, val z: Double, val w: Double, val top: Double, val bottom: Double = 0.0)

/** Where the fallen piece goes back: model [m] at x, z facing [yaw], lifted [y]. */
data class Restore(val m: String, val x: Double, val z: Double, val yaw: Double = 0.0, val y: Double = 0.0)

class World(
    val id: String,
    /** The panorama (assets scenes/pano_<pano>.jpg). */
    val pano: String,
    /** The ground's colour near the player, and its grain. */
    val ground: Long,
    val pieces: List<Piece> = emptyList(),
    val movers: List<Mover> = emptyList(),
    val water: List<Water> = emptyList(),
    val strips: List<Strip> = emptyList(),
    val fall: Fall? = null,
    val restore: Restore? = null,
    /** The waterfall is the thing to find. */
    val findFall: Boolean = false,
    /** 虹桥: the big boat's mast comes down as it nears the bridge (z of the bridge). */
    val mastBridgeZ: Double? = null,
    /** The ambient sound loop (assets audio/amb_<sound>.ogg). */
    val sound: String = "river",
) {
    val models get() = (pieces.map { it.m } + movers.map { it.m } + listOfNotNull(restore?.m) + if (mastBridgeZ != null) listOf("qm_mast") else emptyList()).distinct()
}

object Worlds {
    private fun p(m: String, x: Double, z: Double, yaw: Double = 0.0, y: Double = 0.0, scale: Double = 1.0, find: Boolean = false) = Piece(m, x, z, yaw, y, scale, find)
    private fun line(vararg pts: Pair<Double, Double>) = pts.toList()

    // 清明上河图: earth, stone, the river's green-blue.
    private const val EARTH = 0xFFB59A72
    private const val FIELD = 0xFF8FA968
    private const val PAVE = 0xFFB8AD98
    private const val ROAD = 0xFFC9B48E
    private const val RIVER = 0xFF5E9FAE
    // 千里江山图: ochre shore, mineral green water.
    private const val SHORE = 0xFFC4A774
    private const val JADE = 0xFF7EC4B8

    /** 汴京郊野: the road's loop, in toward the city on the near lane, out on the far one. */
    private val ROAD_LOOP = listOf(24.0 to -6.5, -28.0 to -6.5, -28.0 to -8.6, 24.0 to -8.6)
    /** 城门: the camel train's loop before the gate, in on the right, back on the left. */
    private val GATE_LOOP = listOf(1.5 to -3.0, 1.5 to -16.0, -1.4 to -16.0, -1.4 to -3.0)

    val ALL: Map<String, World> = listOf(
        // 汴京郊野: fields and willows, a road across, the charcoal donkeys coming along it toward the city.
        World(
            "qm1", "qm1", FIELD,
            pieces = listOf(
                // v0.3.1 (device 2026-11-18: the donkeys went through the farmhouses and their fences): the farms and willows
                // stand back from the road; the traffic goes round a loop (in on the near lane, out on the far one) instead of
                // turning on the spot into whoever follows; the stream passes under the road in a culvert.
                p("qm_farm", -9.0, -17.8, 30.0), p("qm_farm", -17.0, 1.2, 75.0), p("qm_house", 13.0, -21.0, -25.0),
                p("t_willow", -7.0, -11.8), p("t_willow", 5.4, -12.0, 40.0), p("t_willow", 12.0, -11.8, 80.0), p("t_willow", -9.4, 3.6, 120.0),
                p("t_willow", 15.0, -2.6, 200.0), p("t_peach", -21.0, -11.5), p("t_peach", 12.4, -15.2, 60.0), p("t_elm", -21.0, -20.0),
            ),
            movers = listOf(
                // The charcoal train (driver behind, three donkeys) and the sedan and barrow: one loop, one pace, spaced out.
                Mover("p_driver", ROAD_LOOP, 0.55, phase = 0.27, find = true),
                Mover("qm_donkey", ROAD_LOOP, 0.55, phase = 0.295, find = true),
                Mover("qm_donkey", ROAD_LOOP, 0.55, phase = 0.315, find = true),
                Mover("qm_donkey", ROAD_LOOP, 0.55, phase = 0.335, find = true),
                Mover("qm_barrow", ROAD_LOOP, 0.55, phase = 0.55),
                Mover("qm_sedan", ROAD_LOOP, 0.55, phase = 0.78),
                Mover("p_vendor", line(-3.0 to -3.9, 6.0 to -4.2), 0.6, loop = false, phase = 0.5),
            ),
            water = listOf(Water(7.5, -60.0, 9.5, -10.2, alongX = false, flow = 0.5, color = RIVER), Water(7.5, -4.8, 9.5, 12.0, alongX = false, flow = 0.5, color = RIVER)),
            strips = listOf(Strip(-60.0, -9.7, 60.0, -5.3, ROAD), Strip(-0.8, -5.3, 0.8, 2.0, ROAD)),
            sound = "field",
        ),
        // 汴河漕运: the river across; a grain boat unloading at our bank, porters carrying sacks to the warehouse.
        World(
            "qm2", "qm2", PAVE,
            pieces = listOf(
                // v0.3.1 (device 2026-11-18: people walked through the boat's hull): the boats lie along the river (a boat's
                // length is its model's x; they were turned a quarter, bow into the bank, the hull across the quay); the
                // warehouse and the trees stand on the bank, not in the water.
                p("qm_warehouse", -11.0, 1.6, 90.0), p("qm_teashed", 9.0, 0.6, -60.0),
                p("t_willow", -19.5, -2.6), p("t_willow", 17.5, 1.2, 70.0), p("t_willow", 14.6, -2.2, 140.0),
                p("qm_cargo", -3.5, -8.3, 0.0, find = true),
                p("p_boatman", -6.5, -8.3, 0.0, y = 0.9),
                p("qm_bigboat", 14.0, -18.1, 0.0),
                p("qm_house", -14.0, -26.0), p("qm_shop", 0.0, -26.5), p("qm_shop2", 14.0, -26.0), p("t_willow", -15.9, -23.4), p("t_willow", 16.1, -23.6, 90.0),
            ),
            movers = listOf(
                // Porters between the boat's side and the warehouse door, each on his own line.
                Mover("p_porter", line(-1.2 to -5.4, -4.4 to -2.6), 0.7, loop = false),
                Mover("p_porter", line(0.2 to -5.4, -3.0 to -2.6), 0.75, loop = false, phase = 0.5),
                Mover("qm_rowboat", line(28.0 to -11.7, -28.0 to -11.7), 0.8, boat = true, bob = 0.05),
                Mover("qm_canopy", line(-28.0 to -14.6, 28.0 to -14.6), 0.6, boat = true, bob = 0.05, phase = 0.4),
                Mover("p_merchant", line(2.2 to -4.3, 12.0 to -4.3), 0.5, loop = false, phase = 0.3),
            ),
            water = listOf(Water(-90.0, -21.0, 90.0, -6.0, alongX = true, flow = -0.6, color = RIVER)),
            restore = Restore("qm_canopy", 10.0, -7.6, 0.0),
        ),
        // 虹桥: the river comes toward us under the rainbow bridge; a big boat lowers its mast to pass under.
        World(
            "qm3", "qm3", PAVE,
            pieces = listOf(
                // The bridge model spans along its own z: turned to cross the river (which runs along z) from bank to bank.
                p("qm_bridge", 0.0, -17.0, 90.0),
                // v0.3.1: the walkers on the bridge each keep a lane of their own (they passed through each other); the moored
                // boat lies along the river at the bank; the tavern, stalls and willows stand clear of the walkers.
                p("qm_tavern", -14.0, -6.4, 90.0), p("qm_shop2", 12.5, -8.0, -90.0), p("qm_shop", -12.0, -25.2, 90.0), p("qm_teashed", 10.0, -23.0, -90.0),
                p("t_willow", -8.7, 2.2), p("t_willow", 11.2, -1.6, 60.0), p("t_willow", -18.9, -22.5, 120.0), p("t_willow", 19.5, -23.3),
                p("qm_canopy", 5.3, -6.2, 90.0),
            ),
            movers = listOf(
                // v0.3.3 (device 2026-11-18: its side went through the player): further left and further off, ≥ 1.8 m away.
                Mover("qm_bigboat", line(-2.6 to -9.5, -2.6 to -13.5), 0.3, loop = false, bob = 0.05, boat = true, find = true),
                Mover("qm_rowboat", line(1.7 to -50.0, 1.7 to -3.0), 0.7, boat = true, bob = 0.05),
                Mover("p_merchant", line(-12.0 to -14.8, 12.0 to -14.8), 0.6, loop = false, arc = 6.0),
                Mover("p_woman", line(12.0 to -16.0, -12.0 to -16.0), 0.5, loop = false, arc = 6.0, phase = 0.3),
                Mover("p_scholar", line(-12.0 to -17.2, 12.0 to -17.2), 0.45, loop = false, arc = 6.0, phase = 0.6),
                Mover("p_vendor", line(12.0 to -18.6, -12.0 to -18.6), 0.55, loop = false, arc = 6.0, phase = 0.8),
                Mover("p_porter", line(-7.3 to -3.0, -7.6 to -12.6), 0.6, loop = false),
                Mover("p_child", line(7.6 to -3.0, 7.9 to -11.0), 0.8, loop = false, phase = 0.5),
            ),
            water = listOf(Water(-6.5, -90.0, 6.5, -2.5, alongX = false, flow = 0.6, color = RIVER)),
            mastBridgeZ = -17.0,
        ),
        // 城外街道: the road to the city between shops; the ox cart; the 彩楼 to put back on the 十千脚店.
        World(
            "qm4", "qm4", EARTH,
            pieces = listOf(
                // v0.3.1: the street is wider (the shops a metre further out) and every mover keeps its own lane.
                p("qm_shop", -8.6, -6.0, 90.0), p("qm_shop2", -8.6, -16.0, 90.0), p("qm_house", -9.0, -27.0, 90.0), p("qm_tavern", -10.2, -40.0, 90.0),
                p("qm_shop", 8.6, -9.0, -90.0), p("qm_teashed", 8.6, -19.0, -90.0), p("qm_house", 9.0, -30.0, -90.0),
                p("t_willow", -10.3, -3.2), p("t_willow", 15.7, -18.7, 80.0), p("t_willow", -22.7, -27.1, 150.0), p("qm_stall", 5.9, -1.4, -20.0),
            ),
            movers = listOf(
                Mover("qm_oxcart", line(-2.6 to -4.0, -2.6 to -45.0), 0.45, loop = false, find = true, phase = 0.1),
                Mover("qm_barrow", line(-0.2 to -40.0, -0.2 to -3.0), 0.7, loop = false, phase = 0.2),
                Mover("qm_horse", line(1.4 to -45.0, 1.4 to -3.0), 0.9, loop = false, phase = 0.6),
                Mover("p_scholar", line(2.5 to -3.0, 2.5 to -30.0), 0.5, loop = false, phase = 0.4),
                Mover("p_woman", line(3.4 to -28.0, 3.4 to -3.0), 0.5, loop = false, phase = 0.7),
            ),
            strips = listOf(Strip(-4.2, -80.0, 4.2, 3.0, ROAD)),
            restore = Restore("qm_cailou", -5.75, -16.0, 90.0),
            sound = "street",
        ),
        // 城门: the road to the gate tower; the camel train passing in, bells on.
        World(
            "qm5", "qm5", EARTH,
            pieces = listOf(
                p("qm_gate", 0.0, -26.0),
                p("p_guard", -3.4, -17.6, 20.0), p("p_guard", 3.4, -17.6, -20.0),
                p("t_willow", -7.8, -6.2), p("t_willow", 7.2, -6.9, 70.0), p("t_willow", -8.5, -10.1, 140.0), p("t_willow", 9.1, -10.8, 200.0),
                p("t_elm", -12.0, -16.0), p("t_elm", 13.0, -15.0, 90.0), p("qm_teashed", -9.0, -6.0, 60.0),
            ),
            // v0.3.1: the camel train goes round before the gate (in on the right, back on the left) instead of turning on the
            // spot into the camels behind and walking into the gate tower's wall; the vendor keeps to the left edge.
            movers = listOf(
                Mover("p_driver", GATE_LOOP, 0.5, phase = 0.0, find = true),
                Mover("qm_camel", GATE_LOOP, 0.5, phase = 0.085, find = true),
                Mover("qm_camel", GATE_LOOP, 0.5, phase = 0.17, find = true),
                Mover("qm_camel", GATE_LOOP, 0.5, phase = 0.255, find = true),
                Mover("p_vendor", line(-3.1 to -15.0, -3.1 to -3.0), 0.55, loop = false, phase = 0.2),
            ),
            strips = listOf(Strip(-3.4, -24.0, 3.4, 3.0, ROAD)),
            sound = "gate",
        ),
        // 孙羊正店: the street inside the walls; the great inn with its 彩楼欢门; a storyteller and his ring.
        World(
            "qm6", "qm6", PAVE,
            pieces = listOf(
                // v0.3.1: each mover keeps a lane; the walkers on the right stop short of the storyteller's ring.
                p("qm_tavern", -9.9, -11.0, 75.0, find = true),
                p("qm_shop", 8.0, -7.0, -90.0), p("qm_shop2", 8.0, -17.0, -90.0), p("qm_shop", -8.0, -24.0, 90.0), p("qm_house", 8.5, -28.0, -90.0),
                p("p_story", 3.8, -5.0, -120.0), p("p_child", 2.4, -6.3, 60.0), p("p_scholar", 2.0, -4.0, 80.0), p("p_woman", 3.2, -3.4, 160.0),
                p("qm_stall", -5.2, -0.6, 30.0), p("qm_stall", 5.9, -12.1, -30.0), p("t_elm", -18.3, -21.8),
            ),
            movers = listOf(
                Mover("qm_sedan", line(-1.9 to -30.0, -1.9 to -3.5), 0.6, loop = false, phase = 0.3),
                Mover("p_porter", line(0.0 to -26.0, 0.0 to -6.0), 0.6, loop = false, phase = 0.1),
                Mover("p_merchant", line(1.2 to -8.2, 1.2 to -30.0), 0.5, loop = false),
                Mover("p_vendor", line(2.6 to -28.0, 2.6 to -8.4), 0.55, loop = false, phase = 0.6),
            ),
            strips = listOf(Strip(-3.0, -60.0, 3.0, 3.0, 0xFFA89F8C)),
            sound = "street",
        ),
        // 江亭 (少年王希孟): a jade river among blue-green peaks; the little pavilion on its rock.
        World(
            "ql1", "ql1", SHORE,
            pieces = listOf(
                p("ql_pavilion", 8.0, -14.0, -30.0, find = true),
                p("ql_mount1", -32.0, -58.0), p("ql_mount2", 30.0, -62.0, 20.0), p("ql_mount1", 60.0, -40.0, 90.0, scale = 0.7), p("ql_mount2", -60.0, -35.0, -60.0, scale = 0.8),
                p("ql_hill", -14.0, -22.0), p("ql_hill", 18.0, -27.0, 60.0), p("ql_pine", -6.4, -7.7), p("ql_pine", 10.0, -6.5, 120.0), p("ql_pine", -11.0, -9.0, 60.0),
                p("ql_cottage", -20.0, -12.0, 50.0),
            ),
            movers = listOf(
                // v0.3.1: the boats keep to open water (they sailed through the islets and the pavilion's rock): one up the
                // channel between the two islets, one on the open water to the right.
                Mover("ql_fishboat", line(-1.0 to -11.0, -1.0 to -46.0), 0.5, loop = false, boat = true, bob = 0.05),
                Mover("ql_fishboat", line(17.5 to -11.5, 32.0 to -11.5), 0.4, loop = false, boat = true, bob = 0.05, phase = 0.5),
                Mover("ql_traveler", line(-14.0 to -2.6, 14.0 to -2.6), 0.6, loop = false),
            ),
            water = listOf(Water(-120.0, -120.0, 120.0, -4.5, alongX = true, flow = 0.25, color = JADE)),
            sound = "lake",
        ),
        // 长桥: the long bridge with its pavilion across the river.
        World(
            "ql2", "ql2", SHORE,
            pieces = listOf(
                p("ql_longbridge", 0.0, -36.0, scale = 0.6, find = true),
                p("ql_mount1", -38.0, -62.0), p("ql_mount2", 34.0, -58.0, 40.0), p("ql_hill", -24.0, -26.0, 30.0), p("ql_hill", 25.0, -27.0, -40.0),
                p("ql_pine", -7.7, -6.4), p("ql_pine", 7.6, -6.5, 90.0), p("ql_cottage", -22.0, -12.0, 40.0),
            ),
            movers = listOf(
                Mover("ql_fishboat", line(-8.5 to -14.0, 35.0 to -14.0), 0.45, loop = false, boat = true, bob = 0.05),
                Mover("ql_fishboat", line(7.0 to -40.0, -30.0 to -40.0), 0.4, loop = false, boat = true, bob = 0.05, phase = 0.3),
                Mover("ql_traveler", line(-11.0 to -36.0, 11.0 to -36.0), 0.7, loop = false, y = 1.4),
            ),
            water = listOf(Water(-120.0, -120.0, 120.0, -4.0, alongX = true, flow = 0.3, color = JADE)),
            sound = "lake",
        ),
        // 山居: a waterfall down the peak, the stream past the mill and the cottages; a green hill fallen out.
        World(
            "ql3", "ql3", 0xFF9DB37A,
            pieces = listOf(
                p("ql_mount1", 0.0, -34.0),
                p("ql_cottage", -17.0, -16.0, 40.0), p("ql_mill", 5.5, -12.0, -60.0), p("ql_pine", -6.4, -7.7), p("ql_pine", 13.5, -16.1, 70.0),
                p("ql_pine", -16.8, -20.0, 150.0), p("ql_mount2", -48.0, -62.0), p("ql_mount2", 50.0, -58.0, 120.0),
            ),
            // v0.3.1: the traveller keeps to the near bank of the stream (he waded through it).
            movers = listOf(Mover("ql_traveler", line(-13.0 to -4.2, -3.4 to -5.8), 0.5, loop = false)),
            water = listOf(Water(-1.6, -14.0, 1.6, -3.0, alongX = false, flow = 0.9, color = JADE), Water(-6.0, -17.5, 6.0, -13.5, alongX = true, flow = 0.2, color = JADE)),
            fall = Fall(0.0, -16.8, 3.0, 20.0),
            findFall = true,
            restore = Restore("ql_hill", 15.0, -17.0, 30.0),
            sound = "fall",
        ),
    ).associateBy { it.id }

    /** The panorama round the pigment bench (卷二 研色) and round the gallery. */
    const val STUDIO = "ql4"
    const val HALL = "hall"

    fun of(sectionId: String): World? = ALL[sectionId]
}
