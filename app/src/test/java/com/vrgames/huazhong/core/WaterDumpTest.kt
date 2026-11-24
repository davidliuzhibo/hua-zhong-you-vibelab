package com.vrgames.huazhong.core

import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Not a check: with HZ_WATER_DUMP set to a folder, writes a world's water (frame [HZ_FRAME], default 0) as OBJ — the
 * wave surface and the shore bands, at life size and as the miniature — and the overlays' pictures as PNG, for a look
 * in Blender (tools/blender/water_preview.py) before the emulator. HZ_WORLD picks the world (default qm2).
 */
class WaterDumpTest {
    @Test
    fun dump() {
        val dir = File(System.getenv("HZ_WATER_DUMP") ?: return).also { it.mkdirs() }
        val w = Worlds.of(System.getenv("HZ_WORLD") ?: "qm2")!!
        val f = (System.getenv("HZ_FRAME") ?: "0").toInt()
        val r = Dressing.regionOf(w)
        val k = minOf(1.1 / (r[1] - r[0]), 0.78 / (r[3] - r[2])).coerceAtMost(1.0 / 30)
        for (mini in listOf(false, true)) {
            val sb = StringBuilder()
            var base = 1
            fun obj(name: String, p: FloatArray, uv: FloatArray, idx: IntArray) {
                sb.append("o $name\n")
                for (i in 0 until p.size / 3) sb.append("v ${p[i * 3]} ${p[i * 3 + 1]} ${p[i * 3 + 2]}\n")
                for (i in 0 until uv.size / 2) sb.append("vt ${uv[i * 2]} ${uv[i * 2 + 1]}\n")
                for (t in 0 until idx.size / 3) {
                    val a = idx[t * 3] + base; val b = idx[t * 3 + 1] + base; val c = idx[t * 3 + 2] + base
                    sb.append("f $a/$a $b/$b $c/$c\n")
                }
                base += p.size / 3
            }
            for ((i, s) in Waves.surfaces(w, r, k, mini).withIndex()) {
                val fld = Waves.field(s.water, w.water, s.spec, s.box, s.step, if (mini) 0.0 else 0.06, s.maxStep, edgeRamp = s.edgeRamp)
                val nx = fld.xs.size; val nz = fld.zs.size
                val idx = ArrayList<Int>()
                for (j in 0 until nz - 1) for (q in 0 until nx - 1) { val a = j * nx + q; idx += listOf(a, a + nx, a + nx + 1, a, a + nx + 1, a + 1) }
                val uv = FloatArray(nx * nz * 2)
                for (j in 0 until nz) for (q in 0 until nx) {
                    val x = fld.xs[q] / 2.5; val z = fld.zs[j] / 2.5
                    uv[(j * nx + q) * 2] = (if (s.water.alongX) x else z).toFloat(); uv[(j * nx + q) * 2 + 1] = (if (s.water.alongX) z else x).toFloat()
                }
                obj("water$i", fld.frame(f % s.spec.frames).first, uv, idx.toIntArray())
                for ((bi, b) in Waves.bands(s.water, w.water, s.spec, s.box, s.shoreWidth, mini, s.lift, s.edgeRamp).withIndex())
                    obj("shore${i}_$bi", b.pos[f % s.spec.frames], b.uv, b.idx)
            }
            File(dir, "${w.id}_${if (mini) "mini" else "full"}.obj").writeText(sb.toString())
        }
        fun png(name: String, px: IntArray, wd: Int, h: Int) {
            // A plain RGBA PNG (no java.awt in the unit tests' classpath).
            val raw = ByteArrayOutputStream()
            DeflaterOutputStream(raw).use { z -> for (y in 0 until h) { z.write(0); for (x in 0 until wd) { val c = px[y * wd + x]; z.write((c shr 16) and 0xFF); z.write((c shr 8) and 0xFF); z.write(c and 0xFF); z.write((c ushr 24) and 0xFF) } } }
            val out = ByteArrayOutputStream()
            val d = DataOutputStream(out)
            d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                d.writeInt(data.size); val t = type.toByteArray(); d.write(t); d.write(data)
                val crc = CRC32(); crc.update(t); crc.update(data); d.writeInt(crc.value.toInt())
            }
            val hdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(wd); writeInt(h); write(8); write(6); write(0); write(0); write(0) } }.toByteArray()
            chunk("IHDR", hdr); chunk("IDAT", raw.toByteArray()); chunk("IEND", ByteArray(0))
            File(dir, name).writeBytes(out.toByteArray())
        }
        val c = w.water.firstOrNull()?.color?.toInt() ?: 0x5E9FAE
        val rr = ((c shr 16) and 0xFF) / 255f; val gg = ((c shr 8) and 0xFF) / 255f; val bb = (c and 0xFF) / 255f
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()
        val shallow = (0xFF shl 24) or (ch(0.5f * rr + 0.43f) shl 16) or (ch(0.5f * gg + 0.42f) shl 8) or ch(0.5f * bb + 0.34f)
        png("shore.png", WaterArt.shore(shallow)[0], WaterArt.SHORE_W, WaterArt.SHORE_H)
        png("wake.png", WaterArt.wake()[0], WaterArt.WAKE_W, WaterArt.WAKE_H)
        File(dir, "info.txt").writeText("k $k\nregion ${r.joinToString(" ")}\ncolor ${"%06X".format(c and 0xFFFFFF)}\n")
        println("water dump ${w.id} -> $dir (k=$k)")
    }
}
