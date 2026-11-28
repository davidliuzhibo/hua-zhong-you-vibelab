package com.vrgames.huazhong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v0.4.0 (PICO 2026-11-23: 「模型都比较粗糙……用高模烘焙低模」): every scene model the worlds use ships as a near and a far
 * version baked from its raw (tools/bake_scenes.sh), each with its own normal map and tangents, power-of-two maps,
 * within its triangle budget (tools/scene_models.tsv); the walkers' walk cycles too. Read straight from the GLB files.
 * v0.5.0: most now have their light baked into the colour map (unlit, tools/bake_light_all.sh): those need no normal map.
 */
class ModelBakeTest {
    private val scenes = File("src/main/assets/scenes")
    private val tsv = (File("../../tools/scene_models.tsv").takeIf { it.exists() } ?: File("../tools/scene_models.tsv"))

    /** name → near, far, walk faces, near px, far px, extra (triangles of parts built in Blender and added after the bake: the net, the blossoms). */
    private val targets: Map<String, IntArray> = tsv.readLines().filter { !it.startsWith("#") && it.isNotBlank() }.associate { l ->
        val p = l.split("\t")
        p[0] to intArrayOf(p[5].toInt(), p[6].toInt(), p[7].toInt(), p[8].toInt(), p[9].toInt(), p.getOrNull(11)?.toIntOrNull() ?: 0)
    }

    private val used: Set<String> = (Worlds.ALL.values.flatMap { it.models } + Dressing.KITS.values.flatten().map { it.m } + listOf("lantern_red", "lantern_sky", "qm_mast")).toSet()

    class Glb(val json: Map<*, *>, val bin: ByteArray) {
        @Suppress("UNCHECKED_CAST")
        fun list(k: String) = (json[k] as? List<Map<String, Any?>>).orEmpty()
        /** Triangles per mesh node. */
        fun tris(): List<Int> = list("nodes").filter { it["mesh"] != null }.map { n ->
            @Suppress("UNCHECKED_CAST")
            val prims = list("meshes")[(n["mesh"] as Double).toInt()]["primitives"] as List<Map<String, Any?>>
            prims.sumOf { p -> (list("accessors")[(p["indices"] as Double).toInt()]["count"] as Double).toInt() / 3 }
        }
        fun hasTangents() = list("meshes").all { m ->
            @Suppress("UNCHECKED_CAST")
            (m["primitives"] as List<Map<String, Any?>>).all { p -> (p["attributes"] as Map<*, *>).containsKey("TANGENT") }
        }
        /** The pixel size of the image behind texture [ti]. */
        fun texSize(ti: Int): Pair<Int, Int> {
            val img = list("images")[(list("textures")[ti]["source"] as Double).toInt()]
            val bv = list("bufferViews")[(img["bufferView"] as Double).toInt()]
            val o = ((bv["byteOffset"] as Double?) ?: 0.0).toInt()
            return imageSize(bin, o)
        }
        /** The model's own material: the one with a colour map (the peach's blossoms and the boat's net have none). */
        @Suppress("UNCHECKED_CAST")
        fun mainMaterial(): Map<String, Any?>? = list("materials").firstOrNull { ((it["pbrMetallicRoughness"] as Map<String, Any?>?)?.get("baseColorTexture")) != null }
            ?: list("materials").firstOrNull()
        /** v0.5.0: its light baked into the colour map (tools/blender/bake_light.py): unlit, no normal map. */
        @Suppress("UNCHECKED_CAST")
        fun lightBaked(): Boolean = (mainMaterial()?.get("extensions") as Map<String, Any?>?)?.containsKey("KHR_materials_unlit") == true
        fun materialTex(key: String): Int? {
            val m = mainMaterial() ?: return null
            @Suppress("UNCHECKED_CAST")
            val t = (if (key == "baseColorTexture") (m["pbrMetallicRoughness"] as Map<String, Any?>?)?.get(key) else m[key]) as Map<String, Any?>? ?: return null
            return (t["index"] as Double).toInt()
        }
    }

    private fun glb(name: String): Glb {
        val b = File(scenes, "$name.glb").readBytes()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val jl = bb.getInt(12)
        val json = Json(String(b, 20, jl, Charsets.UTF_8)).value() as Map<*, *>
        val bl = bb.getInt(20 + jl)
        return Glb(json, b.copyOfRange(28 + jl, 28 + jl + bl))
    }

    @Test
    fun everyModelHasBakedNearAndFarVersions() {
        val rows = ArrayList<String>()
        for (m in used.sorted()) {
            val t = targets[m] ?: error("$m: no bake target in scene_models.tsv")
            for ((suffix, faces, px) in listOf(Triple("", t[0], t[3]), Triple("_lo", t[1], t[4]))) {
                val name = m + suffix
                assertTrue("$name.glb ships", File(scenes, "$name.glb").exists())
                val g = glb(name)
                val tri = g.tris().sum()
                val extra = if (suffix == "") t[5] else t[5] / 2
                assertTrue("$name: $tri triangles, budget $faces + $extra", tri <= (faces + extra) * 1.05 && tri >= faces * 0.8)
                val base = g.materialTex("baseColorTexture") ?: error("$name: no colour map")
                // v0.5.0: light-baked models (unlit) carry the relief in their colours; the others their normal map.
                val maps = if (g.lightBaked()) listOf(base) else {
                    val normal = g.materialTex("normalTexture") ?: error("$name: no normal map")
                    assertTrue("$name: tangents for its normal map", g.hasTangents())
                    listOf(base, normal)
                }
                for (ti in maps) {
                    val (w, h) = g.texSize(ti)
                    assertTrue("$name: ${w}x$h is a power of two", w > 0 && w and (w - 1) == 0 && h and (h - 1) == 0)
                    assertEquals("$name: maps at $px px", px, w)
                }
                rows += "$name $tri"
            }
            // Lighter, as budgeted (about half). Two exceptions keep (nearly) their near mesh: the peach tree, whose
            // petals stop the collapse at ~10k, and 汴河's two-storey shop, whose thin double roof folds into spikes below
            // ~24k; the far versions then differ by their maps.
            assertTrue("$m: far version no heavier", glb("${m}_lo").tris().sum() <= glb(m).tris().sum())
        }
        println("baked models: ${rows.size}")
    }

    @Test
    fun walkersWalkOnBakedFrames() {
        for ((m, t) in targets) {
            if (t[2] == 0 || m !in used) continue
            val g = glb("${m}_walk")
            val fr = g.tris()
            assertEquals("$m: eight frames", 8, fr.size)
            assertTrue("$m: ${fr.max()} triangles a frame, budget ${t[2]}", fr.max() <= t[2] * 1.2)
            assertTrue("$m walk: light baked, or a normal map", g.lightBaked() || (g.materialTex("normalTexture") != null && g.hasTangents()))
            val (w, h) = g.texSize(g.materialTex("baseColorTexture")!!)
            assertTrue("$m walk: ${w}x$h power of two", w and (w - 1) == 0 && h and (h - 1) == 0)
        }
    }

    @Test
    fun trianglesTableMatchesTheFiles() {
        // assets scenes/tris.txt (tools/model_tris.py) is what the HuaPerf log and the budget test count with.
        val tab = Perf.parse(File(scenes, "tris.txt").readLines())
        for (m in used) for (n in listOf(m, "${m}_lo")) {
            val g = glb(n)
            assertEquals("tris.txt for $n is up to date", g.tris().sum(), tab[n])
        }
    }

    companion object {
        fun imageSize(b: ByteArray, o: Int): Pair<Int, Int> {
            fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
            if (b[o] == 0x89.toByte() && b[o + 1] == 'P'.code.toByte()) return u32(o + 16) to u32(o + 20)
            var i = o + 2
            while (i < b.size - 9) {
                if (b[i] != 0xFF.toByte()) { i++; continue }
                val m = b[i + 1].toInt() and 0xFF
                if (m == 0xC0 || m == 0xC1 || m == 0xC2) return u16(i + 7) to u16(i + 5)
                i += 2 + u16(i + 2)
            }
            return 0 to 0
        }
    }
}

/** A small JSON reader for the tests (objects → maps, arrays → lists, numbers → doubles). */
class Json(private val s: String) {
    private var i = 0
    fun value(): Any? {
        ws()
        return when (s[i]) {
            '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws(); if (s[i++] == '}') break }; m }
            '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                while (true) { l += value(); ws(); if (s[i++] == ']') break }; l }
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> { val st = i; while (i < s.length && s[i] in "+-0123456789.eE") i++; s.substring(st, i).toDouble() }
        }
    }
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun str(): String {
        i++
        val sb = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') { i++; when (s[i]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> sb.append(s[i]) } } else sb.append(s[i])
            i++
        }
        i++
        return sb.toString()
    }
}
