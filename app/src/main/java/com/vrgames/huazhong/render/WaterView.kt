package com.vrgames.huazhong.render

import android.graphics.Bitmap
import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.core.math.Vector4
import com.vrgames.huazhong.core.Mover
import com.vrgames.huazhong.core.Water
import com.vrgames.huazhong.core.Waves
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The worlds' water (v0.4.0; PICO 2026-11-23: 「水体可以考虑用网格和 shader 模拟材质与动态」): each river and lake a grid
 * surface whose waves ([Waves]) are baked into frames and played in a loop, one frame shown at a time like the walk
 * cycles; the ripple flip-book still runs over it. Along the banks a light band (shallows and a lapping foam line), so
 * the water is lighter where it meets the land and darker mid-river; behind each moving boat a V-shaped wake. The
 * miniature has its own surfaces, its waves at a diorama's scale. Unlit transparent textured overlays (lit transparent
 * textured materials drew nothing on the emulator).
 */
class WaterView(private val owned: MutableList<TextureResource>, private val meshTris: MutableMap<MeshResource, Int>) {
    /** A surface: its baked frames (entities under [root], one enabled), in the world or the miniature. */
    class Body(val name: String, val field: Waves.Field, val root: Entity, val frames: List<Entity>, val meshes: List<MeshResource>, val mini: Boolean,
               val idx: List<Int>, val uv: List<Vector2>, val tan: List<Vector4>) {
        var shown = 0
    }

    /** A boat's wake: follows the boat on the water, its strength from the boat's speed. */
    class Wake(val mover: Mover, val boat: Entity, val e: Entity, val mat: UnlitMaterial, val back: Double, var top: FloatArray)

    val bodies = ArrayList<Body>()
    val wakes = ArrayList<Wake>()
    private var changes = 0
    private var logT = 0.0

    /** Debug (AutoPilot `water live`): the waves computed every update into one mesh instead of the baked frames. */
    var live = false
    private var liveMs = 0.0
    private var liveN = 0

    fun clear() {
        bodies.clear()
        wakes.clear()
        changes = 0
        logT = 0.0
    }

    /**
     * The surface of water [w] (cut to [box] for the miniature) with [mat] (the ripples), and the shore bands along its
     * banks with [shoreMat], [shoreWidth] m wide. One entity per frame holds the frame's surface and bands (they ride
     * the same waves); the frames go under a new entity, returned for the caller to add to the world or the miniature.
     */
    fun surface(name: String, w: Water, all: List<Water>, s: Waves.Surface, mat: Material, shoreMat: Material, mini: Boolean): Pair<Entity, Body> {
        val t0 = android.os.SystemClock.uptimeMillis()
        val spec = s.spec
        val field = Waves.field(w, all, spec, s.box, step = s.step, growth = if (mini) 0.0 else 0.06, maxStep = s.maxStep, base = w.y, edgeRamp = s.edgeRamp)
        val nx = field.xs.size
        val nz = field.zs.size
        val idx = ArrayList<Int>((nx - 1) * (nz - 1) * 6)
        for (j in 0 until nz - 1) for (i in 0 until nx - 1) {
            val a = j * nx + i
            val b = a + 1
            val c = a + nx + 1
            val d = a + nx
            // Facing up (+y): counter-clockwise seen from above.
            idx += a; idx += d; idx += c; idx += a; idx += c; idx += b
        }
        val tile = TILE
        val uv = ArrayList<Vector2>(nx * nz)
        for (j in 0 until nz) for (i in 0 until nx) {
            val x = field.xs[i].toFloat(); val z = field.zs[j].toFloat()
            uv += if (w.alongX) Vector2(x / tile, z / tile) else Vector2(z / tile, x / tile)
        }
        // The ripples' tangent: along u (x when the flow is along x, else z); handedness so v runs the right way.
        val tan = List(nx * nz) { if (w.alongX) Vector4(1f, 0f, 0f, -1f) else Vector4(0f, 0f, 1f, 1f) }
        var ymin = Float.MAX_VALUE; var ymax = -Float.MAX_VALUE
        for (f in 0 until spec.frames) for (k in 0 until nx * nz) { val y = field.frame(f).first[k * 3 + 1]; ymin = min(ymin, y); ymax = max(ymax, y) }
        val box3 = bbox(Vector3(field.xs.first().toFloat(), ymin - 0.01f, field.zs.first().toFloat()), Vector3(field.xs.last().toFloat(), ymax + 0.01f, field.zs.last().toFloat()))
        // The shore bands, a little over the waves (a centimetre at life size, a millimetre on the table).
        val bands = Waves.bands(w, all, spec, s.box, s.shoreWidth, mini, s.lift, s.edgeRamp, SHORE_TILE)
        val bandUv = bands.map { b -> List(b.uv.size / 2) { Vector2(b.uv[it * 2], b.uv[it * 2 + 1]) } }
        val bandIdx = bands.map { b -> b.idx.toList() }
        val bandTan = bands.map { b -> List(b.uv.size / 2) { Vector4(b.dir[0].toFloat(), 0f, b.dir[1].toFloat(), 1f) } }
        val bandNor = bands.map { b -> List(b.uv.size / 2) { Vector3(0f, 1f, 0f) } }
        val root = Entity()
        val ents = ArrayList<Entity>()
        val meshes = ArrayList<MeshResource>()
        var shoreTris = 0
        for (f in 0 until spec.frames) {
            val (p, n) = field.frame(f)
            val mesh = MeshResource.createWithMeshModel(MeshModel(vec3s(p), idx, vec3s(n), tan, uv), box3, "$name$f${hashCode()}")
            meshTris[mesh] = field.triangles
            val holder = Entity()
            holder.addChild(model(mesh, mat))
            for ((k, b) in bands.withIndex()) {
                val bp = b.pos[f]
                var lo = Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); var hi = Vector3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
                for (v in 0 until bp.size / 3) {
                    lo = Vector3(min(lo.x, bp[v * 3]), min(lo.y, bp[v * 3 + 1]), min(lo.z, bp[v * 3 + 2]))
                    hi = Vector3(max(hi.x, bp[v * 3]), max(hi.y, bp[v * 3 + 1]), max(hi.z, bp[v * 3 + 2]))
                }
                val bm = MeshResource.createWithMeshModel(MeshModel(vec3s(bp), bandIdx[k], bandNor[k], bandTan[k], bandUv[k]),
                    bbox(Vector3(lo.x, lo.y - 0.01f, lo.z), Vector3(hi.x, hi.y + 0.01f, hi.z)), "$name${f}s$k${hashCode()}")
                meshTris[bm] = b.idx.size / 3
                if (f == 0) shoreTris += b.idx.size / 3
                holder.addChild(model(bm, shoreMat))
            }
            holder.enabled = f == 0
            root.addChild(holder)
            ents += holder
            meshes += mesh
        }
        val body = Body(name, field, root, ents, meshes, mini, idx, uv, tan)
        bodies += body
        Log.i(TAG, "water $name ${if (mini) "mini" else "full"}: ${nx}x$nz grid, ${field.triangles} tris + ${bands.size} shore bands ($shoreTris tris) x ${spec.frames} frames, ${android.os.SystemClock.uptimeMillis() - t0} ms")
        return root to body
    }

    /** A wake behind [boat] (the mover's holder), [len] the boat's length; [top] the water's crest height (full, mini). */
    fun wake(m: Mover, boat: Entity, len: Double, frames: List<TextureResource>, top: FloatArray): Entity {
        val mat = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColor(color(1f, 1f, 1f, 0.85f))
            setBaseColorTexture(frames[0])
            setCullingMode(MaterialCullingMode.NONE)
            setDepthWrite(false)
        }
        // Kelvin's wedge (19.5° each side) from the bow back for about twice the boat's length.
        val front = 0.42 * len
        val back = 2.2 * len
        val half = (front + back) * 0.36
        val x = half.toFloat()
        val z0 = front.toFloat()
        val z1 = (-back).toFloat()
        val mesh = MeshResource.createWithMeshModel(
            MeshModel(
                listOf(Vector3(-x, 0f, z0), Vector3(x, 0f, z0), Vector3(x, 0f, z1), Vector3(-x, 0f, z1)), listOf(0, 1, 2, 0, 2, 3),
                List(4) { Vector3(0f, 1f, 0f) }, List(4) { Vector4(1f, 0f, 0f, 1f) },
                // u across (0 left, 1 right), v from the bow (0) to the tail (1).
                listOf(Vector2(0f, 0f), Vector2(1f, 0f), Vector2(1f, 1f), Vector2(0f, 1f)),
            ),
            bbox(Vector3(-x, -0.02f, z1), Vector3(x, 0.02f, z0)), "wake${m.m}${hashCode()}${wakes.size}",
        )
        meshTris[mesh] = 2
        val e = model(mesh, mat)
        wakes += Wake(m, boat, e, mat, back, top)
        return e
    }

    /** Shows the frame of now on every surface (and the wakes where their boats are). [mini]: the miniature shows. */
    fun frame(clock: Double, dt: Double, mini: Boolean, worldId: String?) {
        for (b in bodies) {
            if (b.mini != mini) continue
            if (live) { liveUpdate(b, clock); continue }
            val k = Waves.frameAt(b.field.spec, clock)
            if (k != b.shown) {
                b.frames[b.shown].enabled = false
                b.frames[k].enabled = true
                b.shown = k
                changes++
            }
        }
        for (w in wakes) {
            val shown = w.boat.enabled
            if (w.e.enabled != shown) w.e.enabled = shown
            if (!shown) continue
            val a = w.mover.at(clock)
            w.e.setPos(Vector3(a[0].toFloat(), if (mini) w.top[1] else w.top[0], a[1].toFloat()))
            w.e.transform().eulerAngles = EulerAngles(0f, a[2].toFloat(), 0f)
            // Fainter at the turn of a back-and-forth run (the boat all but stops there in the painting's rhythm).
            val s = min(1.0, w.mover.speed / 0.6)
            w.mat.setBaseColor(color(1f, 1f, 1f, (0.55 + 0.35 * s).toFloat()))
        }
        // Every 10 s of real time (the emulator runs at about 1 fps, its frame time capped at 0.1 s).
        val now = android.os.SystemClock.uptimeMillis() / 1000.0
        if (logT == 0.0) logT = now
        if (now - logT >= 10.0 && worldId != null) {
            logT = now
            val b = bodies.firstOrNull { it.mini == mini }
            val cyc = b?.let { (clock / it.field.spec.period).toInt() } ?: 0
            Log.i(PERF, "water $worldId ${if (mini) "mini" else "full"}: frame ${b?.shown ?: -1}/${b?.field?.spec?.frames ?: 0}, cycle $cyc, frame changes $changes, surfaces ${bodies.count { it.mini == mini }}, wakes ${wakes.count { it.e.enabled }}" +
                (if (live && liveN > 0) ", live update %.2f ms".format(liveMs / liveN) else ""))
            wakes.firstOrNull { it.e.enabled }?.let { w -> val p = w.e.transform().position; Log.i(PERF, "wake ${w.mover.m} at %.1f,%.1f heading %.0f".format(p.x, p.z, w.mover.at(clock)[2])) }
        }
    }

    /** Debug: the waves at [clock] written into the first frame's mesh (replaceWithMeshModel), timed. */
    private fun liveUpdate(b: Body, clock: Double) {
        val t0 = System.nanoTime()
        val (p, n) = b.field.at(clock % b.field.spec.period)
        val m0 = b.meshes[0]
        runCatching { m0.replaceWithMeshModel(MeshModel(vec3s(p), b.idx, vec3s(n), b.tan, b.uv), null) }
            .onFailure { Log.w(PERF, "live water: ${it.message}"); live = false }
        if (b.shown != 0) { b.frames[b.shown].enabled = false; b.frames[0].enabled = true; b.shown = 0 }
        liveMs += (System.nanoTime() - t0) / 1e6
        liveN++
    }

    /** After the world's root came on (see WorldView.hidden): each surface shows its current frame only. */
    fun reapply() {
        for (b in bodies) b.frames.forEachIndexed { i, f -> f.enabled = i == b.shown }
    }

    companion object {
        const val TAG = "HuaWorld"
        const val PERF = "HuaPerf"
        /** Metres per repeat of the ripples' normal map (as the flat water's). */
        const val TILE = 2.5f
        /** Metres per repeat of the shore band's foam along a bank. */
        const val SHORE_TILE = 6.0
        /** The shore band's and the wakes' height over the crests (m, life size). */
        const val LIFT = 0.012

        private fun vec3s(a: FloatArray): List<Vector3> = List(a.size / 3) { Vector3(a[it * 3], a[it * 3 + 1], a[it * 3 + 2]) }

        fun shoreBitmaps(shallow: Int, n: Int = 8): List<Bitmap> = com.vrgames.huazhong.core.WaterArt.shore(shallow, n).map {
            Bitmap.createBitmap(it, com.vrgames.huazhong.core.WaterArt.SHORE_W, com.vrgames.huazhong.core.WaterArt.SHORE_H, Bitmap.Config.ARGB_8888)
        }

        fun wakeBitmaps(n: Int = 8): List<Bitmap> = com.vrgames.huazhong.core.WaterArt.wake(n).map {
            Bitmap.createBitmap(it, com.vrgames.huazhong.core.WaterArt.WAKE_W, com.vrgames.huazhong.core.WaterArt.WAKE_H, Bitmap.Config.ARGB_8888)
        }
    }
}
