package com.vrgames.huazhong.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.TextureColorSpace
import com.pico.spatial.core.ecs.resource.TextureCreateOption
import com.pico.spatial.core.ecs.resource.TextureEncoding
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.core.math.Vector4
import com.vrgames.huazhong.core.Mover
import com.vrgames.huazhong.core.Vec3
import com.vrgames.huazhong.core.World
import com.vrgames.huazhong.core.Perf
import com.vrgames.huazhong.core.Waves
import com.pico.spatial.core.ecs.ModelComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Inside a painting (v0.2, 入画): the section's [World] built round the player. Its root is put at the
 * player's feet under the gallery's root (which is at the eyes): the frame is the world's, origin at the
 * feet, facing -z. A painted panorama all round; near the player the ground, roads and water (flowing:
 * their textures are flip-books), the models standing and going about, a waterfall where there is one.
 * Pinch boxes: "spot" on the thing to find, "scene" on everything else (a miss), "piece" on the fallen
 * piece floating before the player. Built anew for each visit and destroyed on leaving (with the models'
 * own resources: nothing here is shared with the gallery).
 */
class WorldView(private val context: Context, private val sizes: Map<String, FloatArray>) {
    val root = Entity().apply { enabled = false }
    var world: World? = null; private set
    var night = false; private set
    var built = false; private set
    private val hits = HashMap<Entity, String>()
    private val movers = ArrayList<Pair<Mover, Entity>>()
    /** A walker's frames (v0.3), and which is shown. */
    private val walkFrames = HashMap<Entity, List<Entity>>()
    private val walkShown = HashMap<Entity, Int>()
    /**
     * Entities to keep hidden (the templates the models are cloned from). Hiding an entity while its parent is hidden
     * did not hold on the emulator (2026-11-18): when the root came on, everything under it came on too (the world's
     * sky over the miniature, all eight walk frames at once). [show] sets them all again.
     */
    private val hidden = ArrayList<Entity>()
    /** The models whose walk cycles are in already (this world). */
    private val walked = HashSet<String>()
    /** The files in assets/scenes (a model's walk cycle is used where there is one). */
    private val sceneFiles: Set<String> by lazy { runCatching { context.assets.list("scenes")?.toSet() }.getOrNull() ?: emptySet() }
    private val flips = ArrayList<Flip>()
    private val owned = ArrayList<TextureResource>()
    /** v0.4.0: triangles of every mesh drawn (models from assets scenes/tris.txt, generated meshes as made), for HuaPerf. */
    private val meshTris = HashMap<MeshResource, Int>()
    /** v0.4.0: the rivers and lakes as moving surfaces, their shores and the boats' wakes. */
    val water = WaterView(owned, meshTris)
    private val fileTris: Map<String, Int> by lazy { runCatching { Perf.parse(context.assets.open("scenes/tris.txt").bufferedReader().readLines()) }.getOrDefault(emptyMap()) }
    private val oldTris: Map<String, Int> by lazy { runCatching { Perf.parse(context.assets.open("scenes/tris_v033.txt").bufferedReader().readLines()) }.getOrDefault(emptyMap()) }
    private var mast: Entity? = null
    private var mastBoat: Entity? = null
    private var clock = 0.0
    private val sphere = MeshResource.createSphere(1f).shared()
    private val cube = MeshResource.createBox(Vector3(1f, 1f, 1f), 0f).shared()
    private val sparkle = Sparks("wsparkle", 160, sphere, glow(color(1f, 0.85f, 0.45f, 1f)).shared())
    private val mist = Sparks("wmist", 120, sphere, glow(color(0.92f, 0.98f, 1f, 0.55f)).shared())
    private val fireworks = Sparks("wfire", 260, sphere, glow(color(1f, 0.6f, 0.3f, 1f)).shared())
    private val sky = ArrayList<Pair<Entity, DoubleArray>>()
    private var fireT = 0.0

    // The fallen piece: a miniature floating before the eyes, flown home when aimed right.
    private var piece: Entity? = null
    private var pieceFull: Entity? = null
    private var pieceHome: Vec3? = null
    private var homeMark: Entity? = null
    private var homeRing: Entity? = null
    private var homeR = 3.5f
    private var homeTh = 0.02f
    private var flyT = -1.0
    private var flyFrom = Vec3.ZERO
    private var label: Entity? = null
    private var labelT = -1.0
    private var target: Entity? = null
    var eyeHeight = 1.2f

    // ---- 小景 (v0.3, 2026-11-18): the same world as a miniature on a table before the player ----
    // At life size the models' texture is spread over metres and the scene reads empty and toy-like; as a miniature
    // (as 微缩奇案 and 掌上园林) it is sharp and full. The tasks are done on the miniature; 「走进画里」 then stands the
    // player inside at life size. One build serves both: only the root's place and scale and what shows change.
    /** True while shown as the miniature. */
    var mini = false; private set
    /** The miniature's part of the world (metres): x0, x1, z0, z1, and its scale. */
    private var region = doubleArrayOf(-20.0, 20.0, -30.0, 2.0)
    var miniScale = 0.02f; private set
    /** Shown only at life size (sky, horizon band, ground disc and its fade, the long roads and rivers) or only small. */
    // Each in its own container, switched as a whole (v0.3): hiding single entities did not hold on the emulator
    // when they were hidden as they were added, or while the root was hidden (the sky showed round the miniature).
    private var fullRoot = Entity().also { root.addChild(it) }
    private var miniRoot = Entity().also { root.addChild(it) }
    /** Standing models outside the miniature's part. */
    private var outRoot = Entity().also { root.addChild(it) }
    /** Where the player's eyes are in the root's frame (the signs turn to them). */
    private var playerLocal = Vector3(0f, 1.2f, 0f)
    /** The root's place in its parent's frame (the gallery's, at the eyes). */
    private var rootPos = Vector3(0f, -1.2f, 0f)
    /**
     * Unscaled, at the player's feet in both modes: the fallen piece floats here at hand size, whatever the world's
     * scale. The stage adds it beside [root].
     */
    val near = Entity().apply { enabled = false }
    private var pieceBig = 4f

    init {
        root.addChild(sparkle.entity)
        root.addChild(mist.entity)
        root.addChild(fireworks.entity)
    }

    /** Builds [w] (models loaded now: call where the stage's loads go). [night]: 虹桥 on 上元 night. */
    suspend fun build(w: World, night: Boolean) {
        clear()
        world = w
        this.night = night
        val t0 = android.os.SystemClock.uptimeMillis()
        var tl = t0
        fun lap(what: String) {
            val now = android.os.SystemClock.uptimeMillis()
            Log.i(TAG, "build ${w.id} $what ${now - tl} ms")
            tl = now
        }
        // The panorama and its sharp band only matter at life size: loaded on the first walk in (loadSky), not for the
        // miniature (they were 20 s of the build on the emulator, much of it on the main thread).
        skyLoaded = false
        if (night) loadSky()
        lap("bands")
        for (g in (listOf(groundName(w.ground)) + w.strips.map { groundName(it.color) }).distinct()) loadGround(g)
        if (w.water.isNotEmpty()) loadWaterNormals()
        region = regionOf(w)
        miniScale = (minOf(MINI_W / (region[1] - region[0]), MINI_D / (region[3] - region[2]))).toFloat().coerceAtMost(1f / 30f)
        ground(w)
        for (s in w.strips) {
            val g = groundName(s.color)
            val m = groundMat(g, s.color)
            fullRoot.addChild(flat(s.x0, s.z0, s.x1, s.z1, 0.012f, m, "strip", tile = GROUND_TILE[g] ?: 4f))
            clipped(s.x0, s.z0, s.x1, s.z1)?.let { c -> miniRoot.addChild(flat(c[0], c[1], c[2], c[3], 0.012f, silk(groundMat(g, s.color)), "mstrip", tile = GROUND_TILE[g] ?: 4f)) }
        }
        for ((i, s) in Waves.surfaces(w, region, miniScale.toDouble(), false).withIndex()) water(w, s, i)
        for ((i, s) in Waves.surfaces(w, region, miniScale.toDouble(), true).withIndex()) water(w, s, i)
        w.fall?.let { fall(it) }
        lap("ground+water")
        val templates = HashMap<String, Entity?>()
        // Every model the world needs, loaded side by side (one after another they took ~4 s each on the emulator).
        val extra = com.vrgames.huazhong.core.Dressing.extras(w) { m -> sizes[m]?.let { it[0].toDouble() to it[2].toDouble() } }
        val names0 = (w.pieces.map { it.m } + extra.map { it.m } + w.movers.map { it.m } + listOfNotNull(w.restore?.m) + if (w.mastBridgeZ != null) listOf("qm_mast") else emptyList()).distinct()
        // v0.3.1 (device 2026-11-18: 30-50 fps): every standing model has a light version (tools/blender/lo_lod.py, about a
        // quarter of the triangles, 1024 px textures), shown in the miniature and beyond [LOD_FAR] at life size.
        // v0.4.0: and the moving boats (their light version far off and on the miniature).
        val loNames = (w.pieces.map { it.m } + extra.map { it.m } + w.movers.filter { it.boat }.map { it.m } + listOfNotNull(w.restore?.m)).distinct().filter { "${it}_lo.glb" in loAvailable }.map { "${it}_lo" }
        val names = names0 + loNames
        kotlinx.coroutines.coroutineScope {
            names.map { m -> m to async { runCatching { Entity.loadSuspend("asset://scenes/$m.glb") }.onFailure { Log.w(TAG, "$m: ${it.message}") }.getOrNull() } }
                .forEach { (m, d) -> templates[m] = d.await()?.also { it.enabled = false; root.addChild(it); hidden += it; countMeshes(it, m) } }
        }
        lap("models (${names.size})")
        suspend fun inst(m: String): Entity? {
            val tpl = templates.getOrPut(m) { lap("before $m"); runCatching { Entity.loadSuspend("asset://scenes/$m.glb") }.also { lap("load $m") }.onFailure { Log.w(TAG, "$m: ${it.message}") }.getOrNull()?.also { it.enabled = false; root.addChild(it); hidden += it; countMeshes(it, m) } } ?: return null
            // Recursive, sharing the material instances: the clone's model component takes the template's own mesh and
            // materials (ModelComponent.clone), so the GPU holds each model once however many stand (checked: [shareCheck]).
            return tpl.clone(Entity.CloneOptions(true, true))?.also { it.enabled = true; clones += tpl to it }
        }
        // v0.3: the world's own pieces, and the kit that fills it out (core/Dressing). v0.3.1: each a holder with the full
        // model and its light version, one of them shown (see [applyLod]).
        for (p in w.pieces + extra) {
            val hi = inst(p.m) ?: continue
            val lo = if ("${p.m}_lo" in templates) inst("${p.m}_lo") else null
            val e = Entity().apply { addChild(hi); lo?.let { addChild(it) } }
            e.setPos(Vector3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
            e.transform().eulerAngles = EulerAngles(0f, p.yaw.toFloat(), 0f)
            if (p.scale != 1.0) e.setScale(p.scale.toFloat())
            box(e, p.m, if (p.find) "spot" else "scene", p.scale.toFloat())
            (if (inRegion(p.x, p.z, 0.0)) root else outRoot).addChild(e)
            if (p.find) target = e
            if (lo != null) lods += Lod(hi, lo, kotlin.math.hypot(p.x, p.z))
        }
        for (m in w.movers) {
            // The plain model first; its walk cycle (if baked) is swapped in after the world is up (see loadWalks).
            val e = inst(m.m) ?: continue
            val holder = Entity().apply { addChild(e) }
            e.transform().eulerAngles = EulerAngles(0f, (FWD[m.m] ?: 0.0).toFloat(), 0f)
            if (m.boat && "${m.m}_lo" in templates) inst("${m.m}_lo")?.let { lo ->
                lo.transform().eulerAngles = EulerAngles(0f, (FWD[m.m] ?: 0.0).toFloat(), 0f)
                holder.addChild(lo)
                moverLods += MoverLod(e, lo, holder)
            }
            box(holder, m.m, if (m.find) "spot" else "scene", 1f, turned = abs(FWD[m.m] ?: 0.0) == 90.0)
            root.addChild(holder)
            movers += m to holder
            if (m.find && target == null) target = holder
        }
        if (w.mastBridgeZ != null) {
            // 虹桥: a mast on the big boat (the first boat mover), hinged at its foot.
            movers.firstOrNull { it.first.m == "qm_bigboat" }?.let { (_, holder) ->
                inst("qm_mast")?.let { m ->
                    val hinge = Entity().apply { setPos(Vector3(0f, 1.6f, 0f)) }
                    hinge.addChild(m)
                    holder.addChild(hinge)
                    mast = hinge
                    mastBoat = holder
                }
            }
        }
        // v0.4.0: a wake behind every moving boat, on the water it sails.
        if (wakeFrames.isEmpty()) wakeFrames = WaterView.wakeBitmaps().map { TextureResource.create(it).shared().also { t -> owned += t } }
        for ((m, holder) in movers) {
            if (!m.boat) continue
            val (x, z) = m.path.first()
            val wt = w.water.firstOrNull { x >= minOf(it.x0, it.x1) - 1 && x <= maxOf(it.x0, it.x1) + 1 && z >= minOf(it.z0, it.z1) - 1 && z <= maxOf(it.z0, it.z1) + 1 } ?: w.water.firstOrNull() ?: continue
            val lake = Waves.isLake(wt)
            val full = (if (lake) Waves.LAKE else Waves.RIVER).amp
            val small = Waves.mini(lake, miniScale.toDouble()).amp
            // At the waves' mean height (open water rests at its height plus their amplitude): half under the crests, a
            // little over the troughs, so the foam lies among the waves.
            val top = floatArrayOf((wt.y + full + 0.01).toFloat(), (wt.y + small + 0.0005 / miniScale).toFloat())
            val len = (sizes[m.m]?.get(0) ?: 5f).toDouble()
            root.addChild(water.wake(m, holder, len, wakeFrames, top))
        }
        if (wakeFrames.isNotEmpty() && water.wakes.isNotEmpty()) flips += Flip({ t -> for (wk in water.wakes) wk.mat.setBaseColorTexture(t) }, wakeFrames, 0.14)
        if (night) night(w)
        w.restore?.let { r ->
            // v0.4.0: the piece in its place has its light version too (far off, and on the miniature); the one
            // floating before the player (a hand-sized miniature) is the light version.
            val hiFull = inst(r.m)
            val loFull = if ("${r.m}_lo" in templates) inst("${r.m}_lo") else null
            val full = hiFull?.let { h -> Entity().apply { addChild(h); loFull?.let { addChild(it) } } }
            if (hiFull != null && loFull != null) lods += Lod(hiFull, loFull, kotlin.math.hypot(r.x, r.z))
            val mini = (if ("${r.m}_lo" in templates) inst("${r.m}_lo") else null) ?: inst(r.m)
            if (full != null && mini != null) {
                full.setPos(Vector3(r.x.toFloat(), r.y.toFloat(), r.z.toFloat()))
                full.transform().eulerAngles = EulerAngles(0f, r.yaw.toFloat(), 0f)
                full.enabled = false
                root.addChild(full)
                pieceFull = full
                pieceHome = Vec3(r.x, r.y, r.z)
            }
            if (mini != null) {
                val holder = Entity()
                val s = sizes[r.m]
                val big = s?.let { maxOf(it[0], it[1], it[2]) } ?: 4f
                pieceBig = big
                mini.setScale(0.22f / big)
                holder.addChild(mini)
                collide(holder, Vector3(0.3f, 0.3f, 0.3f), 0.1f)
                holder.components.set(InteractableComponent())
                holder.components.set(HoverEffectComponent())
                hits[holder] = "piece"
                holder.enabled = false
                near.addChild(holder)
                piece = holder
            }
        }
        lap("rest")
        miniParts()
        shareCheck()
        built = true
        Log.i(TAG, "built ${w.id} night=$night in ${android.os.SystemClock.uptimeMillis() - t0} ms: ${w.pieces.size} pieces, ${movers.size} movers")
    }

    fun show(on: Boolean) {
        if (root.enabled != on) root.enabled = on
        if (near.enabled != on) near.enabled = on
        if (on) reapply()
    }

    private var skyLoaded = false

    /** The world's panorama all round and its sharp horizon band (life size only). */
    suspend fun loadSky() {
        val w = world ?: return
        if (skyLoaded) return
        skyLoaded = true
        val t0 = android.os.SystemClock.uptimeMillis()
        // The panorama, all round: its middle row on the horizon at eye height, its middle column ahead.
        val pano = if (night && w.id == "qm3") "qm3n" else w.pano
        withContext(Dispatchers.IO) { runCatching { context.assets.open("scenes/pano_$pano.jpg").use { BitmapFactory.decodeStream(it) }?.let { TextureResource.create(it).shared() } }.getOrNull() }?.let { t ->
            owned += t
            fullRoot.addChild(skySphere(t).apply { setPos(Vector3(0f, eyeHeight, 0f)); transform().eulerAngles = EulerAngles(0f, -90f, 0f) })
        }
        
        // v0.3: the band round the horizon at twice the panorama's sharpness (tools/pano_band.py), just inside the sky.
        for (half in 0..1) {
            val t = withContext(Dispatchers.IO) { runCatching { context.assets.open("scenes/band_${pano}_${if (half == 0) "a" else "b"}.jpg").use { BitmapFactory.decodeStream(it) }?.let { TextureResource.create(it).shared() } }.getOrNull() } ?: continue
            owned += t
            fullRoot.addChild(ModelEntity(bandMesh(half), UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(color(1f, 1f, 1f)); setBaseColorTexture(t); setCullingMode(MaterialCullingMode.FRONT) }).apply {
                transform().setScaleVector(Vector3(149f))
                setPos(Vector3(0f, eyeHeight, 0f))
                transform().eulerAngles = EulerAngles(0f, -90f, 0f)
            })
        }
        fullRoot.enabled = !mini
        Log.i(TAG, "sky ${w.id} in ${android.os.SystemClock.uptimeMillis() - t0} ms")
    }

    /**
     * v0.3: the walkers' walk cycles (tools/blender/walk.py: frames f0..fN, one shown at a time), loaded after the
     * world is shown, one model at a time with a pause between. Loaded during the build they held the main thread
     * long enough for the system's "not responding" (a world took 90 s on the emulator).
     */
    suspend fun loadWalks() {
        val w = world ?: return
        for (name in movers.map { it.first.m }.distinct()) {
            if ("${name}_walk.glb" !in sceneFiles || name in walked) continue
            val t0 = android.os.SystemClock.uptimeMillis()
            val tpl = runCatching { Entity.loadSuspend("asset://scenes/${name}_walk.glb") }.onFailure { Log.w(TAG, "${name}_walk: ${it.message}") }.getOrNull() ?: continue
            if (world !== w) { tpl.destroy(); return }
            tpl.enabled = false
            root.addChild(tpl)
            hidden += tpl
            for ((m, holder) in movers) {
                if (m.m != name) continue
                val old = holder.getChildren().firstOrNull() ?: continue
                val walk = tpl.clone(Entity.CloneOptions(true, true)) ?: continue
                walk.transform().eulerAngles = old.transform().eulerAngles
                val fr = ArrayList<Entity>()
                fun collect(x: Entity) {
                    val n = x.getName()
                    if (n != null && WALK_FRAME.matches(n)) fr += x
                    x.getChildren().forEach { collect(it) }
                }
                collect(walk)
                fr.sortBy { it.getName()!!.drop(1).toInt() }
                walk.enabled = true
                fr.forEachIndexed { i, f -> f.enabled = i == 0 }
                holder.removeChild(old)
                old.destroy()
                holder.addChild(walk)
                fr.forEachIndexed { i, f -> f.enabled = i == 0 }
                if (fr.size > 1) { walkFrames[holder] = fr; walkShown[holder] = 0 }
            }
            walked += name
            Log.i(TAG, "walk ${name} in ${android.os.SystemClock.uptimeMillis() - t0} ms")
            kotlinx.coroutines.delay(150)
        }
    }

    /** Every part shown or hidden as it should be now (after the root came on: see [hidden]). */
    private class Lod(val hi: Entity, val lo: Entity, val dist: Double)
    private val lods = ArrayList<Lod>()
    private var lodT = 0.0
    private val loAvailable: Set<String> by lazy { runCatching { context.assets.list("scenes")?.filter { it.endsWith("_lo.glb") }?.toSet() }.getOrNull().orEmpty() }

    /** The light versions in the miniature and far off at life size, the full ones near. */
    private fun applyLod() {
        for (l in lods) {
            val useLo = mini || l.dist > LOD_FAR
            if (l.hi.enabled == useLo) l.hi.enabled = !useLo
            if (l.lo.enabled != useLo) l.lo.enabled = useLo
        }
        // The moving boats by where they are now.
        for (l in moverLods) {
            val p = l.holder.transform().position
            val useLo = mini || kotlin.math.hypot(p.x.toDouble(), p.z.toDouble()) > LOD_FAR
            if (l.hi.enabled == useLo) l.hi.enabled = !useLo
            if (l.lo.enabled != useLo) l.lo.enabled = useLo
        }
    }

    private class MoverLod(val hi: Entity, val lo: Entity, val holder: Entity)
    private val moverLods = ArrayList<MoverLod>()
    private var wakeFrames: List<TextureResource> = emptyList()
    /** Each clone and the template it came from (for [shareCheck]). */
    private val clones = ArrayList<Pair<Entity, Entity>>()

    /** Records the triangles of model file [file]'s meshes (from tris.txt) under their mesh resources. */
    private fun countMeshes(e: Entity, file: String) {
        val t = fileTris[file] ?: return
        fun walk(x: Entity) {
            x.components[ModelComponent::class.java]?.mesh?.let { meshTris[it] = t }
            x.getChildren().forEach { walk(it) }
        }
        walk(e)
    }

    private fun modelsOf(e: Entity, out: MutableList<ModelComponent> = ArrayList()): List<ModelComponent> {
        e.components[ModelComponent::class.java]?.let { out += it }
        e.getChildren().forEach { modelsOf(it, out) }
        return out
    }

    /**
     * v0.4.0: whether the clones really share their template's mesh and material (one copy on the GPU per model, not
     * per instance), logged with the SDK's live resource counts.
     */
    private fun shareCheck() {
        var meshSame = 0; var matSame = 0; var n = 0
        for ((tpl, c) in clones) {
            val a = modelsOf(tpl); val b = modelsOf(c)
            for (i in 0 until minOf(a.size, b.size)) {
                n++
                if (a[i].mesh == b[i].mesh) meshSame++
                if (a[i].materials.size > 0 && b[i].materials.size > 0 && a[i].materials[0] == b[i].materials[0]) matSame++
            }
        }
        val res = runCatching {
            val m = com.pico.spatial.core.ecs.resource.Resource::class.java.methods.first { it.name.startsWith("getCurrentResourceCollectionCount") }
            val c = m.invoke(null) ?: error("null")
            fun g(n: String) = c.javaClass.getMethod(n).invoke(c)
            "active ${g("getActiveResourceCount")}, global ${g("getGlobalResourceCount")}, unowned ${g("getUnownedResourceCount")}"
        }.getOrElse { "counts n/a (${it.javaClass.simpleName})" }
        Log.i(WaterView.PERF, "share ${world?.id}: ${clones.size} clones of ${clones.map { it.first }.distinct().size} templates; meshes shared $meshSame/$n, materials shared $matSame/$n; resources $res")
    }

    /** What is drawn now under the world: triangles (of known meshes) and draw calls (enabled model components). */
    fun drawn(): Perf.Count {
        var t = 0L; var d = 0
        fun walk(x: Entity) {
            if (!x.enabled) return
            x.components[ModelComponent::class.java]?.let { mc ->
                d++
                mc.mesh?.let { t += meshTris[it] ?: 0 }
            }
            x.getChildren().forEach { walk(it) }
        }
        if (root.enabled) walk(root)
        if (near.enabled) walk(near)
        return Perf.Count(t, d)
    }

    /** HuaPerf at a section's start: drawn now, and the budgets of 0.4.0 and of 0.3.3 for the same world and mode. */
    fun logPerf(why: String) {
        val w = world ?: return
        val now = drawn()
        val extra = com.vrgames.huazhong.core.Dressing.extras(w) { m -> sizes[m]?.let { it[0].toDouble() to it[2].toDouble() } }
        val b4 = Perf.count(w, extra, region, fileTris, mini, Perf.V040) + Perf.water(w, region, miniScale.toDouble(), mini)
        val b3 = Perf.count(w, extra, region, oldTris, mini, Perf.V033)
        Log.i(WaterView.PERF, "section ${w.id} ${if (mini) "mini" else "full"} ($why): drawn $now; budget 0.4.0 ${b4.tris} tris / ${b4.draws} models, 0.3.3 ${b3.tris} tris / ${b3.draws} models (${"%.0f".format(100.0 * b4.tris / maxOf(1L, b3.tris))}%)")
    }

    private fun reapply() {
        for (e in hidden) e.enabled = false
        applyLod()
        water.reapply()
        val full = !mini
        fullRoot.enabled = full
        miniRoot.enabled = !full
        outRoot.enabled = full
        for ((holder, fr) in walkFrames) {
            val k = walkShown[holder] ?: 0
            fr.forEachIndexed { i, f -> f.enabled = i == k }
        }
        piece?.enabled = pieceOut
        pieceFull?.enabled = pieceIn
        if (flyT >= 0) piece?.enabled = true
    }

    /** The fallen piece floating before the player, and the real one back in its place. */
    private var pieceOut = false
    private var pieceIn = false

    /**
     * Puts the world at life size round the player ([full]: root at the feet) or as the miniature on its table before
     * them, [eyeY] being the eyes' height above the feet. Shows what each mode shows; a piece in hand comes along.
     */
    fun place(full: Boolean, eyeY: Float) {
        mini = !full
        fullRoot.enabled = full
        miniRoot.enabled = !full
        outRoot.enabled = full
        near.setPos(Vector3(0f, -eyeY, 0f))
        if (full) {
            rootPos = Vector3(0f, -eyeY, 0f)
            root.transform().setScaleVector(Vector3(1f))
            playerLocal = Vector3(0f, eyeY, 0f)
        } else {
            val k = miniScale
            val cx = ((region[0] + region[1]) / 2).toFloat()
            val depth = ((region[3] - region[2]) * k).toFloat()
            // The table's middle in the gallery's frame (origin at the eyes): below the eyes, its near edge a forearm away.
            val tx = 0f
            val ty = -MINI_BELOW_EYE
            val tz = -(MINI_NEAR + depth / 2)
            val cz = ((region[2] + region[3]) / 2).toFloat()
            rootPos = Vector3(tx - k * cx, ty, tz - k * cz)
            root.transform().setScaleVector(Vector3(k))
            playerLocal = Vector3(-rootPos.x / k, -rootPos.y / k, -rootPos.z / k)
        }
        root.setPos(rootPos)
        label?.let { l -> l.setScale(labelScale()); facePlayer(l) }
        if (pieceOut && flyT < 0 && !pieceIn) loosePiece()
        Log.i(TAG, "place full=$full scale=${if (full) 1f else miniScale} region=${region.joinToString()}")
    }

    /** The part of [w] the miniature shows: round everything that stands or goes there, the player's side open. */
    private fun regionOf(w: World): DoubleArray = com.vrgames.huazhong.core.Dressing.regionOf(w)

    private fun inRegion(x: Double, z: Double, margin: Double) =
        x >= region[0] - margin && x <= region[1] + margin && z >= region[2] - margin && z <= region[3] + margin

    /** [x0, z0, x1, z1] cut to the miniature's part, or null if none of it is there. */
    private fun clipped(x0: Double, z0: Double, x1: Double, z1: Double): DoubleArray? {
        val a = maxOf(minOf(x0, x1), region[0]); val b = minOf(maxOf(x0, x1), region[1])
        val c = maxOf(minOf(z0, z1), region[2]); val d = minOf(maxOf(z0, z1), region[3])
        return if (a < b && c < d) doubleArrayOf(a, c, b, d) else null
    }

    /** The miniature's own ground: the world's ground cut to its part, on a dark wooden board with a rim. */
    private fun miniParts() {
        val w = world ?: return
        val g = groundName(w.ground)
        val k = miniScale
        miniRoot.addChild(flat(region[0], region[2], region[1], region[3], 0f, silk(groundMat(g, w.ground)), "mground", tile = GROUND_TILE[g] ?: 4f))
        val wood = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(color(0.27f, 0.16f, 0.09f)); setRoughness(0.55f); setMetallic(0f) }
        val rim = 0.018 / k
        val thick = 0.035f / k
        val bw = (region[1] - region[0] + 2 * rim).toFloat()
        val bd = (region[3] - region[2] + 2 * rim).toFloat()
        miniRoot.addChild(model(cube, wood).apply {
            setPos(Vector3(((region[0] + region[1]) / 2).toFloat(), -thick / 2 - 0.02f, ((region[2] + region[3]) / 2).toFloat()))
            transform().setScaleVector(Vector3(bw, thick, bd))
        })
        // A low lip round the edge, so the ground sits in the board like a tray.
        val lip = (0.006 / k).toFloat()
        val lipW = (0.012 / k).toFloat()
        val cx = ((region[0] + region[1]) / 2).toFloat()
        val cz = ((region[2] + region[3]) / 2).toFloat()
        for ((pos, size) in listOf(
            Vector3(cx, lip / 2 - 0.02f, (region[2] - rim / 2).toFloat()) to Vector3(bw, lip, lipW),
            Vector3(cx, lip / 2 - 0.02f, (region[3] + rim / 2).toFloat()) to Vector3(bw, lip, lipW),
            Vector3((region[0] - rim / 2).toFloat(), lip / 2 - 0.02f, cz) to Vector3(lipW, lip, bd),
            Vector3((region[1] + rim / 2).toFloat(), lip / 2 - 0.02f, cz) to Vector3(lipW, lip, bd),
        )) miniRoot.addChild(model(cube, wood).apply { setPos(pos); transform().setScaleVector(size) })
        miniRoot.enabled = false
    }

    /** The fallen piece's home in [near]'s frame (where it floats). */
    private fun homeNear(): Vec3? {
        val h = pieceHome ?: return null
        if (!mini) return h
        val n = near.transform().position
        val k = miniScale.toDouble()
        return Vec3(rootPos.x - n.x + k * h.x, rootPos.y - n.y + k * h.y, rootPos.z - n.z + k * h.z)
    }

    /**
     * The miniature's ground in the painting's own colours (v0.3): the photo grounds tinted toward old silk — the
     * field's lawn green to a dusty olive, the road's white to warm earth — so the board sits with the scroll behind it.
     */
    private fun silk(m: Material): Material {
        (m as? PhysicallyBasedMaterial)?.setBaseColor(color(0.8f, 0.66f, 0.44f))
        return m
    }

    private fun labelScale(): Float = if (mini) 0.035f / (0.9f * miniScale) else 1f

    /** Everything of the last world goes, with its own textures. */
    fun clear() {
        root.getChildren().filter { it !== sparkle.entity && it !== mist.entity && it !== fireworks.entity }.forEach { it.destroy() }
        hits.clear()
        movers.clear()
        hidden.clear()
        lods.clear()
        moverLods.clear()
        clones.clear()
        water.clear()
        meshTris.clear()
        wakeFrames = emptyList()
        shoreFrames.clear()
        waterMats.clear()
        boxes.clear()
        pickable = true
        walked.clear()
        walkFrames.clear()
        walkShown.clear()
        flips.clear()
        sky.clear()
        // The containers went with the root's other children: new ones.
        fullRoot = Entity().also { root.addChild(it) }
        miniRoot = Entity().also { root.addChild(it) }
        outRoot = Entity().also { root.addChild(it) }
        near.getChildren().forEach { it.destroy() }
        owned.forEach { runCatching { it.close() } }
        owned.clear()
        pieceOut = false; pieceIn = false
        mast = null; mastBoat = null; piece = null; pieceFull = null; pieceHome = null; homeMark = null; label = null; target = null
        flyT = -1.0
        built = false
        world = null
    }

    // v0.3.3 (device 2026-11-18: with the section done, the boat's pinch box — the thing to find, 13 m with its mast —
    // stood between the eyes and the result's 「下一段」, which could not be pinched): the world's pinch boxes are only there
    // while a task needs them (finding a thing, putting the piece back). Off, the gaze goes through to the panels.
    private var pickable = true
    /** Each pinch box's size and lift. A removed CollisionComponent's material is freed with it (re-adding it crashed,
     *  emulator 2026-11-18), so switching back on builds a fresh one from these. */
    private val boxes = HashMap<Entity, Pair<Vector3, Float>>()

    private fun collide(e: Entity, size: Vector3, lift: Float) {
        boxes[e] = size to lift
        e.components.set(CollisionComponent(listOf(ShapeResource.createBox(size).offsetByTranslation(Vector3(0f, lift, 0f))), PhysicsMaterialResource()))
    }

    fun setPickable(on: Boolean) {
        if (on == pickable) return
        pickable = on
        for ((e, id) in hits.toList()) {
            if (on) {
                boxes[e]?.let { (size, lift) -> collide(e, size, lift) }
                e.components.set(InteractableComponent())
                if (id == "spot" || id == "piece") e.components.set(HoverEffectComponent())
            } else {
                e.components.remove(HoverEffectComponent::class.java)
                e.components.remove(InteractableComponent::class.java)
                e.components.remove(CollisionComponent::class.java)
            }
        }
        Log.i(TAG, "pinch boxes ${if (on) "on" else "off"}")
    }

    fun hitOf(e: Entity?): String? {
        var cur = e
        while (cur != null) {
            hits[cur]?.let { return it }
            cur = cur.getParent()
        }
        return null
    }

    /** A pinch box round model [m] on [e] (its prepared size from sizes.txt, or a 2 m cube). */
    private fun box(e: Entity, m: String, id: String, scale: Float, turned: Boolean = false) {
        val raw = sizes[m] ?: floatArrayOf(2f, 2f, 2f)
        // v0.3.1: the thing to find at least 3 m across, trees their trunk (core/PinchBox).
        val s = com.vrgames.huazhong.core.PinchBox.of(m, doubleArrayOf(raw[0].toDouble(), raw[1].toDouble(), raw[2].toDouble()), id == "spot")
        // A model turned a quarter inside its holder (side-on boats and carts): its width and depth swap.
        val w = ((if (turned) s[2] else s[0]) * scale).toFloat()
        val h = (s[1] * scale).toFloat()
        val d = ((if (turned) s[0] else s[2]) * scale).toFloat()
        collide(e, Vector3(w, h, d), h / 2)
        e.components.set(InteractableComponent())
        if (id == "spot") e.components.set(HoverEffectComponent())
        hits[e] = id
    }

    // ---- The ground, roads and water ----

    private fun ground(w: World) {
        // v0.3: a disc of real ground (stone slabs, earth, grass, sand: a Seedream photo made to tile, with its
        // relief as a normal map) lit by the world's own light, out to 26 m; then fading by 60 m, in the
        // ground's average colour, to where the painted ground of the panorama takes over.
        val g = groundName(w.ground)
        val tile = GROUND_TILE[g] ?: 4f
        fullRoot.addChild(model(disc(0.0, 26.0, tile, "ground${w.id}"), groundMat(g, w.ground)))
        val fade = Bitmap.createBitmap(4, 64, Bitmap.Config.ARGB_8888)
        val c = groundCache[g]?.third?.let { avg -> tinted(avg, w.ground) } ?: w.ground.toInt()
        for (y in 0 until 64) for (x in 0 until 4) fade.setPixel(x, y, (((255 * (1 - y / 63f)).toInt()) shl 24) or (c and 0xFFFFFF))
        val ft = TextureResource.create(fade).shared().also { owned += it }
        fullRoot.addChild(model(ring(26.0, 60.0, "fade${w.id}"), PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply { setBaseColor(color(1f, 1f, 1f)); setBaseColorTexture(ft); setRoughness(0.95f); setMetallic(0f); setCullingMode(MaterialCullingMode.NONE) }).apply { setPos(Vector3(0f, -0.005f, 0f)) })
    }

    /** Ground pictures (assets ground/<name>.jpg and _n.png), kept across worlds: colour, normal map, average colour. */
    private val groundCache = HashMap<String, Triple<TextureResource, TextureResource?, Int>>()
    private var waterNormals: List<TextureResource> = emptyList()

    private fun rawOption() = TextureCreateOption().apply { colorSpace = TextureColorSpace.RAW; textureEncoding = TextureEncoding.LINEAR }

    private suspend fun loadGround(name: String) {
        if (name in groundCache) return
        val bmp = withContext(Dispatchers.IO) { runCatching { context.assets.open("ground/$name.jpg").use { BitmapFactory.decodeStream(it) } }.getOrNull() } ?: return
        val avg = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
        val n = withContext(Dispatchers.IO) { runCatching { TextureResource.load("ground/${name}_n.png", LoadType.FROM_ASSETS, rawOption()).shared() }.onFailure { Log.w(TAG, "ground $name normal: ${it.message}") }.getOrNull() }
        val t = withContext(Dispatchers.IO) { TextureResource.create(bmp).shared() }
        groundCache[name] = Triple(t, n, avg)
    }

    private suspend fun loadWaterNormals() {
        if (waterNormals.isNotEmpty()) return
        waterNormals = withContext(Dispatchers.IO) {
            (0 until 16).mapNotNull { i -> runCatching { TextureResource.load("water/wn_%02d.png".format(i), LoadType.FROM_ASSETS, rawOption()).shared() }.onFailure { Log.w(TAG, "water normal $i: ${it.message}") }.getOrNull() }
        }
    }

    /** Which ground picture a world's ground colour stands for. */
    private fun groundName(c: Long): String = when (c) {
        0xFF8FA968, 0xFF9DB37A -> "field"
        0xFFB8AD98, 0xFFA89F8C -> "pave"
        0xFFC9B48E -> "road"
        0xFFC4A774 -> "shore"
        else -> "earth"
    }

    /** A tint for the picture: white, except the hill grass of 山居 (a little yellower than the field). */
    private fun tintOf(c: Long): Color4 = if (c == 0xFF9DB37AL) color(1f, 1f, 0.82f) else color(1f, 1f, 1f)

    private fun tinted(avg: Int, c: Long): Int {
        val t = tintOf(c)
        return (0xFF shl 24) or (ch(((avg shr 16) and 0xFF) / 255f * t.red) shl 16) or (ch(((avg shr 8) and 0xFF) / 255f * t.green) shl 8) or ch((avg and 0xFF) / 255f * t.blue)
    }

    /** The ground's lit material (falls back to the old grain if its picture is missing). */
    private fun groundMat(name: String, c: Long): Material {
        val (tex, normal, _) = groundCache[name] ?: return grainMat(c, 4f)
        return PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColor(tintOf(c))
            setBaseColorTexture(tex)
            normal?.let { setNormalTexture(it); setNormalScale(1f) }
            setMetallic(0f)
            setRoughness(0.92f)
            setCullingMode(MaterialCullingMode.NONE)
        }
    }

    /** A grainy ground colour: a tile of soft blotches in [argb], repeating every [tile] metres (as uv). */
    private fun grainMat(argb: Long, tile: Float): UnlitMaterial {
        val n = 128
        val rnd = Random(argb.toInt())
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val coarse = 8
        val blot = FloatArray(coarse * coarse) { rnd.nextFloat() }
        val px = IntArray(n * n) { i ->
            val x = (i % n).toFloat() / n * coarse
            val y = (i / n).toFloat() / n * coarse
            val ax = x.toInt(); val ay = y.toInt()
            val tx = x - ax; val ty = y - ay
            fun at(cx: Int, cy: Int) = blot[(cy % coarse) * coarse + (cx % coarse)]
            val s = (at(ax, ay) * (1 - tx) + at(ax + 1, ay) * tx) * (1 - ty) + (at(ax, ay + 1) * (1 - tx) + at(ax + 1, ay + 1) * tx) * ty
            val k = 0.86f + 0.2f * s + 0.06f * (rnd.nextFloat() - 0.5f)
            (0xFF shl 24) or (ch(r * k) shl 16) or (ch(g * k) shl 8) or ch(b * k)
        }
        val t = TextureResource.create(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)).shared().also { owned += it }
        return UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(color(1f, 1f, 1f)); setBaseColorTexture(t); setCullingMode(MaterialCullingMode.NONE) }
    }

    /** One material per water (the world's surface and the miniature's share it): its ripples flip together. */
    private val waterMats = HashMap<com.vrgames.huazhong.core.Water, Pair<Material, Material>>()
    private val shoreFrames = HashMap<Int, List<TextureResource>>()

    /**
     * v0.4.0 (PICO 2026-11-23: 「水体可以考虑用网格和 shader 模拟材质与动态」): a moving surface instead of one flat quad (see
     * [WaterView], core/Waves): its waves baked into frames played in a loop; over it the lit, glossy ripples that mirror
     * the world's sky, as in 0.3 (their flip-book runs downstream); along the banks a pale band of shallows with a foam
     * line lapping. A little deeper in colour than 0.3's flat water, so mid-river reads darker than the shallows.
     */
    private fun water(w: World, s: Waves.Surface, i: Int) {
        val wt = s.water
        val mini = s.box != null
        val (mat, shoreMat) = waterMats.getOrPut(wt) {
            val c = wt.color.toInt()
            val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            val frames = waterNormals
            val m = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color(r * 0.46f, g * 0.54f, b * 0.58f))
                setMetallic(0f)
                setRoughness(0.07f)
                setCullingMode(MaterialCullingMode.NONE)
                frames.firstOrNull()?.let { setNormalTexture(it); setNormalScale(0.8f) }
            }
            // The flow's sign: the flip-book runs forward or back along u.
            if (frames.isNotEmpty()) flips += Flip({ m.setNormalTexture(it) }, if (wt.flow >= 0) frames else frames.reversed(), 1.2 / maxOf(abs(wt.flow), 0.1) / frames.size)
            // The shallows: halfway from the water's colour to pale sand.
            val shallow = (0xFF shl 24) or (ch(0.5f * r + 0.5f * 0.86f) shl 16) or (ch(0.5f * g + 0.5f * 0.84f) shl 8) or ch(0.5f * b + 0.5f * 0.68f)
            val sf = shoreFrames.getOrPut(shallow) { WaterView.shoreBitmaps(shallow).map { TextureResource.create(it).shared().also { t -> owned += t } } }
            val sm = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color(1f, 1f, 1f, 1f))
                setBaseColorTexture(sf[0])
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
            flips += Flip({ sm.setBaseColorTexture(it) }, sf, 0.2)
            m to sm
        }
        val name = "${if (mini) "m" else ""}water${w.id}_$i"
        val (e, _) = water.surface(name, wt, w.water, s, mat, shoreMat, mini)
        (if (mini) miniRoot else fullRoot).addChild(e)
    }

    private fun fall(f: com.vrgames.huazhong.core.Fall) {
        val frames = rippleFrames(0xFFE8F6F4.toInt(), vertical = true)
        // v0.3: a little see-through and brighter, so it reads as falling water (on the miniature an opaque sheet was a grey slab).
        val mat = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply { setBaseColor(color(1f, 1f, 1f, 0.82f)); setBaseColorTexture(frames[0]); setCullingMode(MaterialCullingMode.NONE) }
        val x = (f.w / 2).toFloat()
        val h = (f.top - f.bottom).toFloat()
        val tiles = h / 2.5f
        val mesh = MeshResource.createWithMeshModel(
            MeshModel(listOf(Vector3(-x, 0f, 0f), Vector3(x, 0f, 0f), Vector3(x, h, 0f), Vector3(-x, h, 0f)), listOf(0, 1, 2, 0, 2, 3), List(4) { Vector3(0f, 0f, 1f) }, List(4) { Vector4(1f, 0f, 0f, 1f) },
                listOf(Vector2(0f, 0f), Vector2(1f, 0f), Vector2(1f, tiles), Vector2(0f, tiles))),
            bbox(Vector3(-x, 0f, -0.01f), Vector3(x, h, 0.01f)), "fall",
        )
        val e = model(mesh, mat).apply { setPos(Vector3(f.x.toFloat(), f.bottom.toFloat(), f.z.toFloat())) }
        root.addChild(e)
        flips += Flip({ mat.setBaseColorTexture(it) }, frames, 0.06)
        val world = world
        if (world?.findFall == true) {
            val hb = Entity().apply { setPos(Vector3(f.x.toFloat(), (f.bottom + h / 2).toFloat(), f.z.toFloat())) }
            collide(hb, Vector3(x * 2 + 1f, h, 1.5f), 0f)
            hb.components.set(InteractableComponent())
            hb.components.set(HoverEffectComponent())
            hits[hb] = "spot"
            root.addChild(hb)
            target = hb
        }
        fallAt = Vec3(f.x, f.bottom, f.z + 0.5)
    }

    private var fallAt: Vec3? = null

    /** A ripple tile flowing along u (or down v, [vertical]) in [frames] steps: one tile per cycle, seamless. */
    private fun rippleFrames(argb: Int, vertical: Boolean, count: Int = 8): List<TextureResource> {
        val n = 64
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val rnd = Random(argb)
        val waves = List(5) { doubleArrayOf((1 + rnd.nextInt(3)).toDouble(), (rnd.nextInt(5) - 2).toDouble(), rnd.nextDouble() * 2 * PI, 0.4 + rnd.nextDouble() * 0.6) }
        return List(count) { f ->
            val shift = f.toDouble() / count
            val px = IntArray(n * n) { i ->
                var u = (i % n).toDouble() / n
                var v = (i / n).toDouble() / n
                if (vertical) v += shift else u -= shift
                var s = 0.0
                for (wv in waves) s += wv[3] * sin(2 * PI * (wv[0] * u + wv[1] * v) + wv[2] + if (vertical) 2 * PI * wv[0] * v * 3 else 0.0)
                s /= 3.0
                val glint = if (s > 0.55) (s - 0.55) * 1.8 else 0.0
                val k = (0.9 + 0.12 * s).toFloat()
                val gl = glint.toFloat()
                (0xFF shl 24) or (ch(r * k + gl) shl 16) or (ch(g * k + gl) shl 8) or ch(b * k + gl)
            }
            TextureResource.create(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)).shared().also { owned += it }
        }
    }

    private class Flip(val set: (TextureResource) -> Unit, val frames: List<TextureResource>, val every: Double) {
        var t = 0.0
        var i = 0
    }

    /** A flat box of the ground x0..x1 by z0..z1 at [y], uv in [tile]-metre repeats (u along x, or along z). */
    private fun flat(x0: Double, z0: Double, x1: Double, z1: Double, y: Float, mat: Material, name: String, tile: Float = 4f, alongX: Boolean = true): Entity {
        val a = x0.toFloat(); val b = x1.toFloat(); val c = z0.toFloat(); val d = z1.toFloat()
        fun uv(x: Float, z: Float) = if (alongX) Vector2(x / tile, z / tile) else Vector2(z / tile, x / tile)
        val mesh = MeshResource.createWithMeshModel(
            MeshModel(listOf(Vector3(a, 0f, d), Vector3(b, 0f, d), Vector3(b, 0f, c), Vector3(a, 0f, c)), listOf(0, 1, 2, 0, 2, 3), List(4) { Vector3(0f, 1f, 0f) }, List(4) { Vector4(1f, 0f, 0f, 1f) },
                listOf(uv(a, d), uv(b, d), uv(b, c), uv(a, c))),
            bbox(Vector3(a, -0.01f, c), Vector3(b, 0.01f, d)), "$name${hashCode()}$a$c",
        )
        return model(mesh, mat).apply { setPos(Vector3(0f, y, 0f)) }
    }

    private fun disc(r0: Double, r1: Double, tile: Float, name: String): MeshResource = ringMesh(r0, r1, name) { x, z, _ -> Vector2(x / tile, z / tile) }
    private fun ring(r0: Double, r1: Double, name: String): MeshResource = ringMesh(r0, r1, name) { _, _, k -> Vector2(0.5f, 1f - k) }

    private fun ringMesh(r0: Double, r1: Double, name: String, uv: (Float, Float, Float) -> Vector2): MeshResource {
        val seg = 64
        val rings = if (r0 == 0.0) listOf(0.0, r1 * 0.25, r1 * 0.5, r1 * 0.75, r1) else listOf(r0, r1)
        val pos = ArrayList<Vector3>()
        val uvs = ArrayList<Vector2>()
        val idx = ArrayList<Int>()
        for ((j, r) in rings.withIndex()) for (i in 0..seg) {
            val a = 2 * PI * i / seg
            val x = (r * cos(a)).toFloat()
            val z = (r * sin(a)).toFloat()
            pos += Vector3(x, 0f, z)
            uvs += uv(x, z, j.toFloat() / (rings.size - 1))
        }
        for (j in 0 until rings.size - 1) for (i in 0 until seg) {
            val a = j * (seg + 1) + i
            val b = a + 1
            val c = b + seg + 1
            val d = a + seg + 1
            idx += listOf(a, c, b, a, d, c)
        }
        val R = r1.toFloat()
        return MeshResource.createWithMeshModel(MeshModel(pos, idx, List(pos.size) { Vector3(0f, 1f, 0f) }, List(pos.size) { Vector4(1f, 0f, 0f, 1f) }, uvs), bbox(Vector3(-R, -0.01f, -R), Vector3(R, 0.01f, R)), name)
    }

    // ---- 上元夜: lanterns and fireworks over the rainbow bridge ----

    private suspend fun night(w: World) {
        val rnd = Random(3)
        val tpl = runCatching { Entity.loadSuspend("asset://scenes/lantern_sky.glb") }.getOrNull()?.also { it.enabled = false; root.addChild(it) }
        repeat(36) {
            val e = tpl?.clone(Entity.CloneOptions(true, true))?.also { it.enabled = true } ?: model(sphere, glow(color(1f, 0.6f, 0.25f, 1f))).apply { setScale(0.4f) }
            val a = rnd.nextDouble() * 2 * PI
            val r = 6 + rnd.nextDouble() * 30
            // x, z, start height, rising speed, sway phase
            val s = doubleArrayOf(r * sin(a), -abs(r * cos(a)) - 4, rnd.nextDouble() * 25, 0.25 + rnd.nextDouble() * 0.35, rnd.nextDouble() * 6)
            root.addChild(e)
            sky += e to s
        }
        val red = runCatching { Entity.loadSuspend("asset://scenes/lantern_red.glb") }.getOrNull()?.also { it.enabled = false; root.addChild(it) }
        // Red lanterns along both banks, under the eaves.
        for (z in listOf(-6.0, -9.0, -12.0, -21.0, -24.0)) for (x in listOf(-9.5, 9.5)) {
            val e = red?.clone(Entity.CloneOptions(true, true))?.also { it.enabled = true } ?: continue
            e.setPos(Vector3(x.toFloat(), 3.2f, z.toFloat()))
            root.addChild(e)
        }
        // Warm light from below (the lanterns' glow on the water).
        root.addChild(model(sphere, glow(color(1f, 0.55f, 0.2f, 0.25f))).apply { setPos(Vector3(0f, 0.05f, -16f)); transform().setScaleVector(Vector3(12f, 0.05f, 20f)) })
        if (w.id != "qm3") Log.w(TAG, "night in ${w.id}")
    }

    // ---- The things to do ----

    /** The thing found: it sparkles and its name stands over it for a while. */
    fun found(name: String) {
        val t = target ?: return
        val p = t.transform().position
        val top = p.y + if (mini) 3f + 0.03f / miniScale else 3.5f
        sparkle.emit(Vec3(p.x.toDouble(), top.toDouble() - 1.5, p.z.toDouble()), 70, 1.6, 1.4, 0.06f)
        label?.destroy()
        label = sign(name, 0.9f).apply { setPos(Vector3(p.x, top, p.z)); setScale(labelScale()); facePlayer(this) }
        root.addChild(label!!)
        labelT = 0.0
    }

    fun wrongAt() = Unit

    fun hintFind() {
        val t = target ?: return
        val p = t.transform().position
        sparkle.emit(Vec3(p.x.toDouble(), p.y + 2.0, p.z.toDouble()), 80, 1.2, 1.6, 0.07f)
    }

    /** The fallen piece floats before the player (a miniature) and its home glows. */
    fun loosePiece() {
        val p = piece ?: return
        p.enabled = true
        pieceOut = true
        p.setPos(Vector3(0.25f, eyeHeight - 0.25f, -0.6f))
        val h = pieceHome ?: return
        homeMark?.destroy()
        // v0.3.1 (device 2026-11-18, 汴河漕运: 「至少在小景里，并没有发光的位置」): at life size a 7 m disc and a faint
        // beam; in the miniature that was a few centimetres, pale on the water. There it is now at least 7 cm across with a
        // 30 cm beam, brighter, and it breathes (see [frame]).
        val k = if (mini) miniScale.toDouble() else 1.0
        val r = maxOf(3.5, 0.035 / k).toFloat()
        val beam = (if (mini) 0.3 / k else 8.0).toFloat()
        // Clear of the water (4 mm in the miniature), a thin disc and a brighter ring just above it.
        val lift = (if (mini) 0.004 / k else 0.06).toFloat()
        val th = (if (mini) 0.002 / k else 0.02).toFloat()
        homeTh = th
        homeMark = Entity().apply {
            setPos(Vector3(h.x.toFloat(), h.y.toFloat() + lift, h.z.toFloat()))
            addChild(model(MeshResource.createCylinder(1f, 1f), glow(color(1f, 0.8f, 0.3f, 0.6f))).apply { transform().setScaleVector(Vector3(r, th, r)) })
            addChild(model(MeshResource.createCylinder(1f, 1f), glow(color(1f, 0.92f, 0.6f, 0.85f))).apply { setPos(Vector3(0f, th, 0f)); transform().setScaleVector(Vector3(r * 1.12f, th * 0.5f, r * 1.12f)) }.also { homeRing = it })
            addChild(model(MeshResource.createCylinder(1f, 1f), glow(color(1f, 0.85f, 0.45f, 0.3f))).apply { setPos(Vector3(0f, beam / 2, 0f)); transform().setScaleVector(Vector3(r * 0.18f, beam, r * 0.18f)) })
        }
        homeR = r
        root.addChild(homeMark!!)
    }

    /** The test pilot's hand: the miniature put on the line from the eyes to its home. */
    fun aimPiece() {
        if (mini) {
            val h = homeNear() ?: return
            piece?.setPos(Vector3(h.x.toFloat(), (h.y + 0.02).toFloat(), h.z.toFloat()))
            return
        }
        val h = pieceHome ?: return
        val eye = Vec3(0.0, eyeHeight.toDouble(), 0.0)
        val d = Vec3(h.x, h.y + 1.0, h.z) - eye
        val p = eye + d * (0.7 / d.length())
        piece?.setPos(Vector3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
    }

    fun piecePos(): Vec3? = piece?.transform()?.position?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }

    fun movePiece(p: Vec3) {
        val e = piece ?: return
        e.setPos(Vector3(p.x.coerceIn(-1.2, 1.2).toFloat(), p.y.coerceIn(0.3, eyeHeight + 0.8).toFloat(), p.z.coerceIn(-1.4, 0.4).toFloat()))
    }

    /** Whether the miniature, seen from the eyes, lies over its home (within [AIM_DEG]). */
    fun pieceAimed(): Boolean {
        if (mini) {
            // On the miniature: put down on its glowing place (within a few centimetres, from above).
            val p = piecePos() ?: return false
            val h = homeNear() ?: return false
            return kotlin.math.hypot(p.x - h.x, p.z - h.z) < MINI_DROP && p.y - h.y < 0.15
        }
        val p = piecePos() ?: return false
        val h = pieceHome ?: return false
        val eye = Vec3(0.0, eyeHeight.toDouble(), 0.0)
        val a = p - eye
        val b = Vec3(h.x, h.y + 1.0, h.z) - eye
        val cosA = (a.x * b.x + a.y * b.y + a.z * b.z) / (a.length() * b.length()).coerceAtLeast(1e-6)
        return Math.toDegrees(kotlin.math.acos(cosA.coerceIn(-1.0, 1.0))) <= AIM_DEG
    }

    /** Put back: the miniature flies to its home, growing, and the real one takes its place. */
    fun restored() {
        val p = piece ?: return
        flyFrom = piecePos() ?: return
        flyT = 0.0
        hits.remove(p)
        homeMark?.destroy()
        homeMark = null
        homeRing = null
    }

    fun hintPiece() {
        val h = pieceHome ?: return
        sparkle.emit(Vec3(h.x, h.y + 1.0, h.z), 80, 1.5, 1.6, 0.07f)
    }

    private fun facePlayer(e: Entity) {
        val p = e.transform().position
        // The sign's face (+z) toward the player at the origin (with +180 it showed its back: mirrored, emulator 2026-11-16).
        e.transform().eulerAngles = EulerAngles(0f, Math.toDegrees(atan2((playerLocal.x - p.x).toDouble(), (playerLocal.z - p.z).toDouble())).toFloat(), 0f)
    }

    private fun sign(text: String, h: Float): Entity {
        val px = 72
        val b = Bitmap.createBitmap(px * text.length + 32, px + 24, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawColor(0xE0201810.toInt())
        val p = Paint().apply { isAntiAlias = true; color = 0xFFF4E2B8.toInt(); textSize = px * 0.86f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        text.forEachIndexed { i, ch -> cv.drawText(ch.toString(), 16f + px * (i + 0.5f), 12f + px * 0.8f, p) }
        val w = h * b.width / b.height
        val x = w / 2; val y = h / 2
        val t = TextureResource.create(b).shared().also { owned += it }
        val mesh = MeshResource.createWithMeshModel(
            MeshModel(listOf(Vector3(-x, -y, 0f), Vector3(x, -y, 0f), Vector3(x, y, 0f), Vector3(-x, y, 0f)), listOf(0, 1, 2, 0, 2, 3), List(4) { Vector3(0f, 0f, 1f) }, List(4) { Vector4(1f, 0f, 0f, 1f) },
                listOf(Vector2(0f, 0f), Vector2(1f, 0f), Vector2(1f, 1f), Vector2(0f, 1f))),
            bbox(Vector3(-x, -y, -0.01f), Vector3(x, y, 0.01f)), "wsign$text",
        )
        return model(mesh, UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(color(1f, 1f, 1f)); setBaseColorTexture(t); setCullingMode(MaterialCullingMode.NONE) })
    }

    // ---- Per frame ----

    fun frame(dt: Double) {
        if (!root.enabled) return
        clock += dt
        // Now and then the light / full versions are set again (a child switched while its parent was hidden may not hold).
        lodT += dt
        if (lodT > 1.5) { lodT = 0.0; applyLod() }
        // The fallen piece's home breathes, so it is found at a glance.
        homeRing?.let { ring -> val b = (1.0 + 0.18 * kotlin.math.sin(clock * 3.0)).toFloat(); ring.transform().setScaleVector(Vector3(homeR * 1.12f * b, homeTh * 0.5f, homeR * 1.12f * b)) }
        water.frame(clock, dt, mini, world?.id)
        for (f in flips) {
            f.t += dt
            if (f.t >= f.every) {
                f.t = 0.0
                f.i = (f.i + 1) % f.frames.size
                f.set(f.frames[f.i])
            }
        }
        for ((m, e) in movers) {
            val a = m.at(clock)
            // The miniature shows its own part only: whoever walks off the board is not drawn over the table's edge.
            val shown = !mini || inRegion(a[0], a[1], -0.5)
            if (e.enabled != shown) e.enabled = shown
            if (!shown) continue
            if (m.boat) {
                e.setPos(Vector3(a[0].toFloat(), a[3].toFloat(), a[1].toFloat()))
                e.transform().eulerAngles = EulerAngles((0.8 * sin(clock * 0.9 + m.phase * 5)).toFloat(), a[2].toFloat(), (2.5 * sin(clock * 1.3 + m.phase * 7)).toFloat())
                continue
            }
            // v0.3: a gait, so the walkers (static models) do not glide: a bob on each step, the cadence from the
            // speed; people sway side to side with the steps, four-legged beasts rock fore and aft. With a baked walk
            // cycle the legs swing too, one cycle per stride (no foot sliding along the ground).
            val frames = walkFrames[e]
            val stride = STRIDE[m.m]
            val cyc = if (frames != null && stride != null) clock * m.speed / stride + m.phase * 7 else (clock * (3.4 + 3.2 * m.speed) + m.phase * 9) / (2 * PI)
            val step = 2 * PI * cyc
            if (frames != null) {
                val k = (((cyc % 1.0) + 1.0) % 1.0 * frames.size).toInt().coerceIn(0, frames.size - 1)
                val was = walkShown[e] ?: 0
                if (k != was) {
                    frames[was].enabled = false
                    frames[k].enabled = true
                    walkShown[e] = k
                }
            }
            val bob = abs(sin(step)) * m.bob
            val beast = m.m == "qm_donkey" || m.m == "qm_camel" || m.m == "qm_horse" || m.m == "qm_oxcart"
            val pitch = if (beast) 1.6 * sin(2 * step) else 0.0
            val roll = if (beast) 0.8 * sin(step) else 2.6 * sin(step)
            e.setPos(Vector3(a[0].toFloat(), (a[3] + bob).toFloat(), a[1].toFloat()))
            e.transform().eulerAngles = EulerAngles(pitch.toFloat(), a[2].toFloat(), roll.toFloat())
        }
        // The mast comes down as the boat nears the bridge, and up again as it backs off.
        val bz = world?.mastBridgeZ
        val mb = mastBoat
        if (bz != null && mb != null) {
            val z = mb.transform().position.z
            val k = ((z - (bz + 10.0)) / -6.0).coerceIn(0.0, 1.0)
            mast?.transform()?.eulerAngles = EulerAngles((-75 * k).toFloat(), 0f, 0f)
        }
        fallAt?.let { if (Math.random() < dt * 20) mist.emit(it, 3, 0.8, 1.8, 0.25f, Vec3(0.0, 0.3, 0.6)) }
        mist.update(dt, 0.6)
        if (night) {
            for ((e, s) in sky) {
                val y = (s[2] + clock * s[3]) % 32.0
                e.setPos(Vector3((s[0] + 0.6 * sin(clock * 0.3 + s[4])).toFloat(), (3 + y).toFloat(), s[1].toFloat()))
            }
            fireT -= dt
            if (fireT <= 0) {
                fireT = 1.2 + Math.random() * 1.8
                val x = (Math.random() - 0.5) * 50
                fireworks.emit(Vec3(x, 22 + Math.random() * 10, -35 - Math.random() * 20), 90, 7.0, 1.6, 0.18f, Vec3(0.0, -1.0, 0.0))
            }
            fireworks.update(dt, 0.8)
        }
        if (labelT >= 0) {
            labelT += dt
            if (labelT > 5) { label?.destroy(); label = null; labelT = -1.0 }
        }
        if (flyT >= 0) {
            flyT += dt
            val k = (flyT / 1.4).coerceAtMost(1.0)
            val e = k * k * (3 - 2 * k)
            val h = homeNear()
            val p = piece
            if (h != null && p != null) {
                if (mini) {
                    // Settles into its place, growing to the miniature's own scale.
                    val end = miniScale * pieceBig / 0.22f
                    p.setPos(Vector3((flyFrom.x + (h.x - flyFrom.x) * e).toFloat(), (flyFrom.y + (h.y - flyFrom.y) * e + 0.04 * sin(PI * k)).toFloat(), (flyFrom.z + (h.z - flyFrom.z) * e).toFloat()))
                    p.setScale((1 + (end - 1) * e).toFloat())
                } else {
                    p.setPos(Vector3((flyFrom.x + (h.x - flyFrom.x) * e).toFloat(), (flyFrom.y + (h.y + 1.0 - flyFrom.y) * e + 2 * sin(PI * k)).toFloat(), (flyFrom.z + (h.z - flyFrom.z) * e).toFloat()))
                    p.setScale((1 + 25 * e * e).toFloat())
                }
            }
            if (k >= 1.0) {
                flyT = -1.0
                p?.enabled = false
                pieceFull?.enabled = true
                pieceOut = false
                pieceIn = true
                pieceHome?.let { sparkle.emit(Vec3(it.x, it.y + 1.0, it.z), 90, 2.0, 1.5, 0.07f) }
            }
        }
        sparkle.update(dt)
    }

    companion object {
        /** Metres per repeat of each ground picture (the slabs about half a metre long). */
        private val GROUND_TILE = mapOf("pave" to 3.5f, "field" to 4f, "earth" to 4f, "road" to 4f, "shore" to 4f)

        private const val TAG = "HuaWorld"
        private val WALK_FRAME = Regex("f\\d+")
        /**
         * Metres walked per walk cycle (both feet one step), from tools/blender/walk.py's STRIDE: 4·hip·sin(swing).
         * The cycle plays at speed / stride, so the feet keep to the ground.
         */
        val STRIDE = mapOf(
            // From the baked walk cycles (tools/bake_scenes.sh logs each STRIDE); v0.4.1: the remade porter, scholar (new rigid
            // boots under a still robe, a shorter step), horse.
            "p_porter" to 0.77, "p_vendor" to 0.78, "p_child" to 0.43, "p_scholar" to 0.66, "p_merchant" to 0.56, "p_woman" to 0.48,
            "qm_horse" to 0.97, "qm_camel" to 1.11, "qm_donkey" to 0.65, "ql_traveler" to 0.62, "p_driver" to 0.87,
            "qm_sedan" to 0.95, "qm_barrow" to 0.97, "qm_oxcart" to 0.92,
        )

        /** The miniature's table (v0.3): at most this wide and deep (m), this far below the eyes, its near edge this far ahead. */
        /** Beyond this (metres from the player) the light versions stand in at life size (v0.4.0: 18 → 12 m; baked, they hold up). */
        const val LOD_FAR = 12.0
        const val MINI_W = 1.1
        const val MINI_D = 0.78
        const val MINI_BELOW_EYE = 0.45f
        const val MINI_NEAR = 0.42f
        /** How near (m, across) to its place a piece must be let go on the miniature. */
        const val MINI_DROP = 0.06

        /** How near (degrees, from the eyes) the miniature must be to its home to go back in. */
        const val AIM_DEG = 12.0

        /**
         * Which way a moving model faces when unturned, so it walks forward (degrees added to its heading). From the
         * front views of the prepared models: people, the donkey, camel, horse, wheelbarrow and (v0.3) the rider on his
         * donkey face +z; the boats and the ox cart stand side-on, heading toward +x; the v0.3 sedan stands side-on with
         * its bearers heading toward -x.
         */
        val FWD = mapOf(
            "qm_cargo" to -90.0, "qm_bigboat" to -90.0, "qm_canopy" to -90.0, "qm_rowboat" to -90.0, "ql_fishboat" to -90.0,
            "qm_oxcart" to -90.0, "qm_sedan" to 90.0,
        )

        /** The panorama sphere (as the courtyard's of 时空记忆宫殿): the bitmap's width round the horizon, height pole to pole. */
        private val skyMesh: MeshResource by lazy {
            val cols = 64
            val rows = 32
            val pos = ArrayList<Vector3>()
            val nor = ArrayList<Vector3>()
            val tan = ArrayList<Vector4>()
            val uv = ArrayList<Vector2>()
            val idx = ArrayList<Int>()
            for (j in 0..rows) for (i in 0..cols) {
                val phi = PI * j / rows
                val th = 2 * PI * i / cols
                val p = Vector3((sin(phi) * cos(th)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(th)).toFloat())
                pos += p
                nor += p
                tan += Vector4((-sin(th)).toFloat(), 0f, cos(th).toFloat(), 1f)
                uv += Vector2(i.toFloat() / cols, 1f - j.toFloat() / rows)
            }
            for (j in 0 until rows) for (i in 0 until cols) {
                val a = j * (cols + 1) + i
                val b = a + 1
                val c = b + cols + 1
                val d = a + cols + 1
                idx += listOf(a, b, c, a, c, d)
            }
            MeshResource.createWithMeshModel(MeshModel(pos, idx, nor, tan, uv), null, "world_sky").shared()
        }

        /** Panorama rows the sharp band covers (of 2048), as tools/pano_band.py cuts them. */
        private const val BAND_Y0 = 544.0
        private const val BAND_Y1 = 1504.0
        private val bandMeshes = arrayOfNulls<MeshResource>(2)

        /** Half [half] (0: the first half of the circle, 1: the second) of the horizon band, mapped as the sky sphere. */
        fun bandMesh(half: Int): MeshResource = bandMeshes[half] ?: run {
            val cols = 64
            val rows = 16
            val pos = ArrayList<Vector3>()
            val nor = ArrayList<Vector3>()
            val tan = ArrayList<Vector4>()
            val uv = ArrayList<Vector2>()
            val idx = ArrayList<Int>()
            for (j in 0..rows) for (i in 0..cols) {
                val phi = PI * (BAND_Y0 + (BAND_Y1 - BAND_Y0) * j / rows) / 2048.0
                val th = PI * (half + i.toDouble() / cols)
                val p = Vector3((sin(phi) * cos(th)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(th)).toFloat())
                pos += p
                nor += p
                tan += Vector4((-sin(th)).toFloat(), 0f, cos(th).toFloat(), 1f)
                uv += Vector2(i.toFloat() / cols, 1f - j.toFloat() / rows)
            }
            for (j in 0 until rows) for (i in 0 until cols) {
                val a = j * (cols + 1) + i
                val b = a + 1
                val c = b + cols + 1
                val d = a + cols + 1
                idx += listOf(a, b, c, a, c, d)
            }
            MeshResource.createWithMeshModel(MeshModel(pos, idx, nor, tan, uv), null, "world_band$half").shared().also { bandMeshes[half] = it }
        }

        fun skySphere(tex: TextureResource): Entity = ModelEntity(
            skyMesh,
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color(1f, 1f, 1f))
                setBaseColorTexture(tex)
                setCullingMode(MaterialCullingMode.FRONT)
            },
        ).apply { transform().setScaleVector(Vector3(150f)) }

        private fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()

        /** sizes.txt: "<model> <w> <h> <d>" per line (metres, as prepared). */
        fun loadSizes(context: Context): Map<String, FloatArray> = runCatching {
            context.assets.open("scenes/sizes.txt").bufferedReader().readLines().mapNotNull { l ->
                val f = l.trim().split(Regex("\\s+"))
                if (f.size >= 4) f[0] to floatArrayOf(f[1].toFloat(), f[2].toFloat(), f[3].toFloat()) else null
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
