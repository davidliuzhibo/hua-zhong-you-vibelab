package com.vrgames.huazhong.core

import org.junit.Test
import java.io.File

/**
 * Not a check: with HZ_DUMP set to a folder, writes each world's layout (region, ground, strips, water, pieces with
 * the dressing kit, the walkers at a moment) as JSON, for the Blender store renders (tools/blender/store_scene.py).
 */
class WorldDumpTest {
    @Test
    fun dump() {
        val dir = System.getenv("HZ_DUMP") ?: return
        val sizes = File("src/main/assets/scenes/sizes.txt").readLines().mapNotNull { l ->
            val p = l.trim().split(Regex("\\s+"))
            if (p.size >= 4) p[0] to listOf(p[1].toDouble(), p[2].toDouble(), p[3].toDouble()) else null
        }.toMap()
        val t = (System.getenv("HZ_T") ?: "7").toDouble()
        for (id in Dressing.KITS.keys) {
            val w = Worlds.of(id) ?: continue
            val extra = Dressing.extras(w) { m -> sizes[m]?.let { it[0] to it[2] } }
            val r = Dressing.regionOf(w)
            val sb = StringBuilder("{\n")
            fun hex(c: Long) = "\"%08X\"".format(c)
            sb.append("\"id\":\"${w.id}\",\"pano\":\"${w.pano}\",\"ground\":${hex(w.ground)},\n")
            sb.append("\"region\":[${r.joinToString(",")}],\n")
            sb.append("\"strips\":[" + w.strips.joinToString(",") { "[${it.x0},${it.z0},${it.x1},${it.z1},${hex(it.color)}]" } + "],\n")
            sb.append("\"water\":[" + w.water.joinToString(",") { "[${it.x0},${it.z0},${it.x1},${it.z1},${it.y},${hex(it.color)}]" } + "],\n")
            sb.append("\"fall\":" + (w.fall?.let { "[${it.x},${it.z},${it.w},${it.top},${it.bottom}]" } ?: "null") + ",\n")
            sb.append("\"pieces\":[" + (w.pieces + extra).joinToString(",\n") { "[\"${it.m}\",${it.x},${it.y},${it.z},${it.yaw},${it.scale}]" } + "],\n")
            sb.append("\"paths\":[" + w.movers.joinToString(",") { m -> (if (m.loop) m.path + m.path.first() else m.path).joinToString(",", "[", "]") { (x, z) -> "[$x,$z]" } } + "],\n")
            sb.append("\"restore\":" + (w.restore?.let { "[\"${it.m}\",${it.x},${it.y},${it.z},${it.yaw}]" } ?: "null") + ",\n")
            sb.append("\"movers\":[" + w.movers.joinToString(",\n") { m ->
                val a = m.at(t)
                "[\"${m.m}\",${a[0]},${a[3]},${a[1]},${a[2]},${m.boat}]"
            } + "]\n}\n")
            File(dir, "$id.json").writeText(sb.toString())
        }
    }
}
