package com.vrgames.huazhong.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The triangles of a GLB scene model (tests only, v0.5.2): every mesh of the default scene, with the nodes' transforms
 * applied, in the model's own frame (as the game loads it: x right, y up, z front, metres). A small JSON reader is here
 * (JSON read with ModelBakeTest's [Json]).
 */
class SceneMesh(val tris: FloatArray) {
    val count get() = tris.size / 9
    val min = FloatArray(3) { Float.MAX_VALUE }
    val max = FloatArray(3) { -Float.MAX_VALUE }

    init {
        for (i in tris.indices) {
            val a = i % 3
            if (tris[i] < min[a]) min[a] = tris[i]
            if (tris[i] > max[a]) max[a] = tris[i]
        }
    }

    companion object {
        fun read(f: File): SceneMesh {
            val all = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            require(all.getInt(0) == 0x46546C67) { "${f.name}: not a GLB" }
            val jsonLen = all.getInt(12)
            val json = Json(String(f.readBytes(), 20, jsonLen, Charsets.UTF_8)).value() as Map<*, *>
            val binStart = 20 + jsonLen + 8
            val accessors = json["accessors"] as List<*>
            val views = json["bufferViews"] as List<*>
            val meshes = json["meshes"] as List<*>
            val nodes = json["nodes"] as List<*>
            val out = ArrayList<Float>()

            fun num(m: Map<*, *>, k: String, d: Double = 0.0) = (m[k] as? Number)?.toDouble() ?: d

            fun floats(ai: Int): FloatArray {
                val a = accessors[ai] as Map<*, *>
                val v = views[(a["bufferView"] as Number).toInt()] as Map<*, *>
                val n = (a["count"] as Number).toInt()
                val comps = when (a["type"]) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> error("type") }
                val stride = num(v, "byteStride", (comps * 4).toDouble()).toInt().let { if (it == 0) comps * 4 else it }
                val base = binStart + num(v, "byteOffset").toInt() + num(a, "byteOffset").toInt()
                require((a["componentType"] as Number).toInt() == 5126)
                return FloatArray(n * comps) { i -> all.getFloat(base + (i / comps) * stride + (i % comps) * 4) }
            }

            fun ints(ai: Int): IntArray {
                val a = accessors[ai] as Map<*, *>
                val v = views[(a["bufferView"] as Number).toInt()] as Map<*, *>
                val n = (a["count"] as Number).toInt()
                val base = binStart + num(v, "byteOffset").toInt() + num(a, "byteOffset").toInt()
                return when ((a["componentType"] as Number).toInt()) {
                    5121 -> IntArray(n) { all.get(base + it).toInt() and 0xFF }
                    5123 -> IntArray(n) { all.getShort(base + it * 2).toInt() and 0xFFFF }
                    5125 -> IntArray(n) { all.getInt(base + it * 4) }
                    else -> error("indices")
                }
            }

            /** A node's local matrix (column-major 4x4). */
            fun local(n: Map<*, *>): DoubleArray {
                (n["matrix"] as? List<*>)?.let { m -> return DoubleArray(16) { (m[it] as Number).toDouble() } }
                val t = (n["translation"] as? List<*>)?.map { (it as Number).toDouble() } ?: listOf(0.0, 0.0, 0.0)
                val r = (n["rotation"] as? List<*>)?.map { (it as Number).toDouble() } ?: listOf(0.0, 0.0, 0.0, 1.0)
                val s = (n["scale"] as? List<*>)?.map { (it as Number).toDouble() } ?: listOf(1.0, 1.0, 1.0)
                val (x, y, z, w) = r
                val m = doubleArrayOf(
                    1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0.0,
                    2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0.0,
                    2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0.0,
                    t[0], t[1], t[2], 1.0,
                )
                for (c in 0..2) for (row in 0..2) m[c * 4 + row] *= s[c]
                return m
            }

            fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { i ->
                val c = i / 4
                val r = i % 4
                (0..3).sumOf { k -> a[k * 4 + r] * b[c * 4 + k] }
            }

            fun visit(ni: Int, parent: DoubleArray) {
                val n = nodes[ni] as Map<*, *>
                val m = mul(parent, local(n))
                (n["mesh"] as? Number)?.toInt()?.let { mi ->
                    for (p in ((meshes[mi] as Map<*, *>)["primitives"] as List<*>)) {
                        p as Map<*, *>
                        val pos = floats(((p["attributes"] as Map<*, *>)["POSITION"] as Number).toInt())
                        val idx = (p["indices"] as? Number)?.let { ints(it.toInt()) } ?: IntArray(pos.size / 3) { it }
                        for (v in idx) {
                            val x = pos[v * 3].toDouble()
                            val y = pos[v * 3 + 1].toDouble()
                            val z = pos[v * 3 + 2].toDouble()
                            out += (m[0] * x + m[4] * y + m[8] * z + m[12]).toFloat()
                            out += (m[1] * x + m[5] * y + m[9] * z + m[13]).toFloat()
                            out += (m[2] * x + m[6] * y + m[10] * z + m[14]).toFloat()
                        }
                    }
                }
                (n["children"] as? List<*>)?.forEach { visit((it as Number).toInt(), m) }
            }

            val id = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }
            val scene = ((json["scenes"] as List<*>)[(json["scene"] as? Number)?.toInt() ?: 0] as Map<*, *>)["nodes"] as List<*>
            for (r in scene) visit((r as Number).toInt(), id)
            return SceneMesh(out.toFloatArray())
        }
    }
}
