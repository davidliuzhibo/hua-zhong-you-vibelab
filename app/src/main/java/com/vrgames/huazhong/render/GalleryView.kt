package com.vrgames.huazhong.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.Material
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
import com.vrgames.huazhong.core.Scrolls
import com.vrgames.huazhong.core.Section
import com.vrgames.huazhong.core.Spot
import com.vrgames.huazhong.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The gallery round the seated player (full immersion). The root sits at the player's eyes, facing
 * -z. A section's painting stands on a curved wall in front, split into three bands at different
 * depths (far top, near bottom) that line up from the seat and part a little as the head moves:
 * the painting becomes a 2.5D scene. Things to find are pinch boxes over their place on the wall; a
 * piece to restore leaves a paper-coloured gap and floats before the player; a found thing steps out
 * of the paper toward the player for a moment. The home wall shows both scrolls as long strips with
 * the sections marked; the pigment bench (卷二) stands in front at table height.
 */
class GalleryView {
    val root = Entity()
    private val hits = HashMap<Entity, String>()
    private val sphere = MeshResource.createSphere(1f).shared()
    private val cube = MeshResource.createBox(Vector3(1f, 1f, 1f), 0f).shared()
    private val cyl = MeshResource.createCylinder(1f, 1f).shared()
    private val motes = Sparks("motes", 90, sphere, glow(color(1f, 0.85f, 0.55f, 0.6f)).shared()).also { root.addChild(it.entity) }
    private val sparkle = Sparks("sparkle", 160, sphere, glow(color(1f, 0.85f, 0.45f, 1f)).shared()).also { root.addChild(it.entity) }
    private val lanterns = Sparks("lanterns", 160, sphere, glow(color(1f, 0.55f, 0.2f, 1f)).shared()).also { root.addChild(it.entity) }
    private val powder = Sparks("powder", 100, sphere, pbr(color(0.3f, 0.65f, 0.45f), roughness = 1f, emissive = color(0.1f, 0.25f, 0.15f)).shared()).also { root.addChild(it.entity) }

    private val homeRoot = Entity().also { root.addChild(it) }
    private var wallRoot = Entity().also { root.addChild(it) }
    private val benchRoot = Entity().also { root.addChild(it); it.enabled = false }
    private val trophyRoot = Entity().also { root.addChild(it) }
    private var night: Entity? = null
    private var clock = 0.0

    // The section on the wall: its texture, size and the bands' radii.
    private var section: Section? = null
    private var tex: TextureResource? = null
    /**
     * The one material of the section on the wall, used by the bands, the piece and the pop-out alike.
     * It and its texture are app-owned (shared): destroying a pop-out must not free them while the
     * wall still uses them (it did: "closed TextureResource" on the next section). clearWall closes them.
     */
    private var wallMat: UnlitMaterial? = null
    private var span = 2.4
    private var wallH = 1.3
    private var top = 0.65
    private var piece: Entity? = null
    private var pieceHome: Vec3? = null
    private var patch: Entity? = null
    private var popOut: Entity? = null
    private var popFrom = Vector3(0f, 0f, 0f)
    private var popT = -1.0

    var eyeHeight = 1.2f

    /** The room round the gallery: a dark sphere until a painted panorama (the study, v0.2) is put in. */
    private val dark = model(sphere, unlit(color(0.075f, 0.062f, 0.05f)).apply { setCullingMode(MaterialCullingMode.NONE) }).apply { setScale(30f) }
    private var backdrop: Entity? = null
    private var backdropName: String? = null
    private val floorParts = ArrayList<Entity>()

    init {
        // A dim warm room: a backdrop all round, a dark wooden floor, a few golden motes in the air.
        root.addChild(dark)
        trophyRoot.setPos(Vector3(0f, 0f, 0f))
    }

    /**
     * The room round the gallery as a painted panorama [name] (PICO 1116: 「skybox 是纯黑」): the study round
     * the scroll wall, the painting academy round the pigment bench. Its middle column ahead.
     */
    fun setBackdrop(name: String, t: TextureResource, bands: List<TextureResource>? = null) {
        if (backdropName == name) return
        backdrop?.destroy()
        backdropName = name
        backdrop = WorldView.skySphere(t).apply { setScale(28f); transform().eulerAngles = EulerAngles(0f, -90f, 0f) }
        // The sharp band just inside it (a child: it comes and goes with the room).
        bands?.forEachIndexed { h, bt ->
            backdrop!!.addChild(ModelEntity(WorldView.bandMesh(h), UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(color(1f, 1f, 1f)); setBaseColorTexture(bt); setCullingMode(MaterialCullingMode.FRONT) }).apply { setScale(149f / 150f) })
        }
        root.addChild(backdrop!!)
        dark.enabled = false
    }

    /** Inside a painting (入画): the gallery's own room, wall and floor are put away; the panels stay. */
    fun inside(on: Boolean) {
        val out = !on
        backdrop?.let { if (it.enabled != out) it.enabled = out }
        if (backdrop == null && dark.enabled != out) dark.enabled = out
        for (e in floorParts) if (e.enabled != out) e.enabled = out
        if (wallRoot.enabled != out && section != null) wallRoot.enabled = out
        if (homeRoot.enabled && on) homeRoot.enabled = false
        if (trophyRoot.enabled != out) trophyRoot.enabled = out
        // v0.5.0: the scroll wall's 上元 darkening goes with the wall (left on inside the night world, it stood round the
        // player as a dark arc across the sky: emulator 2026-11-27).
        night?.let { if (it.enabled != out) it.enabled = out }
        motes.entity.enabled = out
    }

    fun floor() {
        floorParts.forEach { it.destroy() }
        floorParts.clear()
        floorParts += model(cyl, unlit(color(0.16f, 0.115f, 0.08f))).apply {
            setPos(Vector3(0f, -eyeHeight, 0f))
            transform().setScaleVector(Vector3(6f, 0.01f, 6f))
        }
        floorParts += model(cyl, glow(color(1f, 0.8f, 0.5f, 0.12f))).apply {
            setPos(Vector3(0f, -eyeHeight + 0.01f, -0.4f))
            transform().setScaleVector(Vector3(1.4f, 0.002f, 1.4f))
        }
        floorParts.forEach { root.addChild(it) }
    }

    // ---- Meshes ----

    /**
     * An arc of a cylinder round the eye: radius [r], from angle -[sp]/2 (left) to +[sp]/2 (right),
     * between heights [yTop] and [yBot]; texture u0..u1 across, v0 at the top and v1 at the bottom.
     * Textures (from files too) have their first row at v = 1 (emulator 2026-11-10: the paintings stood
     * upside down with v = 0 at the top), so callers pass 1 - v.
     */
    private fun arc(r: Double, sp: Double, yTop: Double, yBot: Double, u0: Double, u1: Double, v0: Double, v1: Double, name: String, segs: Int = 48): MeshResource {
        val pos = ArrayList<Vector3>()
        val nor = ArrayList<Vector3>()
        val uv = ArrayList<Vector2>()
        val idx = ArrayList<Int>()
        for (i in 0..segs) {
            val t = i / segs.toDouble()
            val th = -sp / 2 + sp * t
            val x = (r * sin(th)).toFloat()
            val z = (-r * cos(th)).toFloat()
            val n = Vector3((-sin(th)).toFloat(), 0f, cos(th).toFloat())
            val u = (u0 + (u1 - u0) * t).toFloat()
            pos += Vector3(x, yTop.toFloat(), z); nor += n; uv += Vector2(u, v0.toFloat())
            pos += Vector3(x, yBot.toFloat(), z); nor += n; uv += Vector2(u, v1.toFloat())
        }
        for (i in 0 until segs) {
            val a = i * 2
            idx += listOf(a, a + 1, a + 2, a + 2, a + 1, a + 3)
        }
        val ymin = min(yTop, yBot).toFloat()
        val ymax = maxOf(yTop, yBot).toFloat()
        return MeshResource.createWithMeshModel(
            MeshModel(pos, idx, nor, List(pos.size) { Vector4(1f, 0f, 0f, 1f) }, uv),
            bbox(Vector3(-r.toFloat(), ymin, -r.toFloat()), Vector3(r.toFloat(), ymax, 0f)), name,
        )
    }

    /** A flat quad [w]×[h] facing +z; uv u0..u1, v0 at its top and v1 at its bottom. */
    private fun quad(w: Float, h: Float, u0: Float = 0f, u1: Float = 1f, v0: Float = 0f, v1: Float = 1f, name: String): MeshResource {
        val x = w / 2
        val y = h / 2
        val pos = listOf(Vector3(-x, -y, 0f), Vector3(x, -y, 0f), Vector3(x, y, 0f), Vector3(-x, y, 0f))
        val uv = listOf(Vector2(u0, v1), Vector2(u1, v1), Vector2(u1, v0), Vector2(u0, v0))
        return MeshResource.createWithMeshModel(
            MeshModel(pos, listOf(0, 1, 2, 0, 2, 3), List(4) { Vector3(0f, 0f, 1f) }, List(4) { Vector4(1f, 0f, 0f, 1f) }, uv),
            bbox(Vector3(-x, -y, -0.001f), Vector3(x, y, 0.001f)), name,
        )
    }

    private fun texMat(t: TextureResource): UnlitMaterial = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
        setBaseColor(color(1f, 1f, 1f))
        setBaseColorTexture(t)
        setCullingMode(MaterialCullingMode.NONE)
    }

    /** Text drawn on a transparent bitmap, on a quad [h] tall (a bitmap's first row is v = 1). */
    private fun sign(text: String, h: Float, ink: Int, back: Int = 0, vertical: Boolean = false): Entity {
        val px = 72
        val bw = if (vertical) px + 16 else px * text.length + 16
        val bh = if (vertical) px * text.length + 16 else px + 16
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        if (back != 0) cv.drawColor(back)
        val p = Paint().apply { isAntiAlias = true; color = ink; textSize = px * 0.86f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        text.forEachIndexed { i, ch ->
            val cx = if (vertical) 8f + px / 2f else 8f + px * (i + 0.5f)
            val cy = if (vertical) 8f + px * (i + 0.8f) else 8f + px * 0.8f
            cv.drawText(ch.toString(), cx, cy, p)
        }
        val w = h * bw / bh
        val m = UnlitMaterial.create(if (back == 0) BlendingMode.MASKED else BlendingMode.OPAQUE).apply {
            setBaseColor(color(1f, 1f, 1f))
            setBaseColorTexture(TextureResource.create(b))
            if (back == 0) setOpacityThreshold(0.5f)
            setCullingMode(MaterialCullingMode.NONE)
        }
        return model(quad(w, h, 0f, 1f, 1f, 0f, "sign${text.hashCode()}$h"), m)
    }

    private fun hit(e: Entity, id: String, size: Vector3, hover: Boolean = true) {
        e.components.set(CollisionComponent(listOf(ShapeResource.createBox(size)), PhysicsMaterialResource()))
        e.components.set(InteractableComponent())
        if (hover) e.components.set(HoverEffectComponent())
        hits[e] = id
    }

    fun hitOf(e: Entity?): String? {
        var cur = e
        while (cur != null) {
            hits[cur]?.let { return it }
            cur = cur.getParent()
        }
        return null
    }

    /** A box at angle [th] (right of ahead) and radius [r], height [y], facing the eye. */
    private fun onWall(e: Entity, r: Double, th: Double, y: Double) {
        e.setPos(Vector3((r * sin(th)).toFloat(), y.toFloat(), (-r * cos(th)).toFloat()))
        e.transform().eulerAngles = EulerAngles(0f, Math.toDegrees(-th).toFloat(), 0f)
    }

    // ---- The home wall: both scrolls ----

    private val homeMarks = HashMap<String, Entity>()

    /** The two strips with the sections marked: [status] per section 0 locked, 1 open, 2 done; [next] glows. */
    fun home(strips: Map<String, TextureResource>, status: Map<String, Int>, stars: Map<String, Int>, next: String?) {
        homeRoot.getChildren().forEach { hits.remove(it); it.destroy() }
        hits.keys.removeAll { hits[it]?.startsWith("home:") == true }
        homeRoot.enabled = true
        val r = 2.1
        val sp = Math.toRadians(150.0)
        // 卷一 above the eyes, 卷二 below; each strip as long as the arc allows.
        val rows = listOf(Triple("strip_qm", 0, 0.36), Triple("strip_ql", 1, -0.06))
        for ((name, scroll, yTop) in rows) {
            val t = strips[name] ?: continue
            val list = Scrolls.of(scroll).filter { !it.bench }
            val srcX0 = if (scroll == 0) 1300 else 4600
            val srcX1 = if (scroll == 0) 37000 else 39300
            val srcH = if (scroll == 0) 1800 else 1600
            val h = sp * r / ((srcX1 - srcX0).toDouble() / srcH)
            homeRoot.addChild(model(arc(r, sp, yTop, yTop - h, 0.0, 1.0, 1.0, 0.0, "home$name"), texMat(t)))
            // A dark wooden roller at each end.
            for (side in listOf(-1, 1)) homeRoot.addChild(model(cyl, pbr(color(0.32f, 0.2f, 0.12f), emissive = color(0.12f, 0.08f, 0.05f))).apply {
                val th = side * (sp / 2 + 0.02)
                setPos(Vector3((r * sin(th)).toFloat(), (yTop - h / 2).toFloat(), (-r * cos(th)).toFloat()))
                transform().setScaleVector(Vector3(0.022f, (h + 0.08).toFloat(), 0.022f))
            })
            homeRoot.addChild(sign(Scrolls.TITLES[scroll], 0.07f, 0xFFE9D8B4.toInt(), vertical = true).apply {
                val th = -(sp / 2 + 0.07)
                onWall(this, r, th, yTop - h / 2)
            })
            for (s in list) {
                val u0 = (s.x0 - srcX0).toDouble() / (srcX1 - srcX0)
                val u1 = (s.x1 - srcX0).toDouble() / (srcX1 - srcX0)
                val th = (((u0 + u1) / 2) - 0.5) * sp
                val w = (u1 - u0) * sp * r
                val st = status[s.id] ?: 0
                val mark = Entity()
                onWall(mark, r - 0.04, th, yTop - h / 2)
                hit(mark, "home:${s.id}", Vector3(w.toFloat() * 0.96f, h.toFloat(), 0.06f))
                if (st == 0) mark.addChild(model(cube, unlit(color(0.05f, 0.04f, 0.03f, 0.62f), BlendingMode.TRANSPARENT)).apply { transform().setScaleVector(Vector3(w.toFloat() * 0.98f, h.toFloat(), 0.002f)) })
                if (s.id == next) mark.addChild(model(cube, glow(color(1f, 0.82f, 0.4f, 0.35f))).apply { transform().setScaleVector(Vector3(w.toFloat(), 0.006f, 0.004f)); setPos(Vector3(0f, (-h / 2 - 0.006).toFloat(), 0f)) })
                // The section's name under it, and its stars or a lock.
                val label = "${s.number}·${s.title}" + when (st) { 2 -> " " + "★".repeat(stars[s.id] ?: 0); 0 -> " 未开"; else -> "" }
                mark.addChild(sign(label, 0.034f, if (st == 2) 0xFFF0C878.toInt() else 0xFFD8CBB0.toInt()).apply { setPos(Vector3(0f, (-h / 2 - 0.035).toFloat(), 0f)) })
                homeRoot.addChild(mark)
                homeMarks[s.id] = mark
            }
        }
        // The pigment workshop of 卷二, as a seal-like tile below the strip's left end.
        Scrolls.QIANLI.firstOrNull { it.bench }?.let { s ->
            val st = status[s.id] ?: 0
            val tile = Entity()
            onWall(tile, r - 0.05, -sp / 2 + 0.2, -0.46)
            tile.addChild(model(cube, unlit(if (st == 0) color(0.3f, 0.26f, 0.22f) else color(0.62f, 0.16f, 0.12f))).apply { transform().setScaleVector(Vector3(0.24f, 0.09f, 0.01f)) })
            tile.addChild(sign("${s.number}·研色" + if (st == 2) " " + "★".repeat(stars[s.id] ?: 0) else if (st == 0) " 未开" else "", 0.045f, 0xFFF6E7C8.toInt()).apply { setPos(Vector3(0f, 0f, 0.008f)) })
            hit(tile, "home:${s.id}", Vector3(0.26f, 0.1f, 0.04f))
            homeRoot.addChild(tile)
        }
    }

    fun hideHome() {
        homeRoot.enabled = false
    }

    // ---- A section on the wall ----

    /** Puts section [s] on the wall with texture [t]: three bands at three depths, and the pinch boxes. */
    fun showSection(s: Section, t: TextureResource, enterable: Boolean = false) {
        clearWall()
        section = s
        tex = t.shared()
        val aspect = (s.x1 - s.x0).toDouble() / (s.y1 - s.y0).let { if (it <= 0) 1800 else it }
        span = min(Math.toRadians(160.0), 1.3 * aspect / R0)
        wallH = span * R0 / aspect
        top = wallH * 0.52
        val m = texMat(t).shared()
        wallMat = m
        for (k in 0 until 3) {
            val r = BAND_R[k]
            val v0 = BAND_V[k]
            val v1 = BAND_V[k + 1] + if (k < 2) 0.004 else 0.0
            val k0 = r / R0
            wallRoot.addChild(model(arc(r, span, (top - v0 * wallH) * k0, (top - v1 * wallH) * k0, 0.0, 1.0, 1 - v0, 1 - v1, "wall${s.id}$k"), m))
            // Pinch boxes behind each band: a pinch on the painting that is not the thing is a miss.
            val segs = 10
            for (i in 0 until segs) {
                val th = -span / 2 + span * (i + 0.5) / segs
                val e = Entity()
                onWall(e, r + 0.04, th, (top - (v0 + v1) / 2 * wallH) * k0)
                // A painting one can step into lights up under the gaze (入画, v0.2); otherwise no highlight (it would give the spot away).
                hit(e, "wall", Vector3((span * r / segs).toFloat(), ((v1 - v0) * wallH * k0).toFloat(), 0.03f), hover = enterable)
                wallRoot.addChild(e)
            }
        }
        wallRoot.enabled = true
    }

    private fun bandOf(v: Double) = when { v < BAND_V[1] -> 0; v < BAND_V[2] -> 1; else -> 2 }

    /** Where (u, v) of the section is on its band (root frame), and the band's radius. */
    private fun wallPoint(u: Double, v: Double): Pair<Vec3, Double> {
        val r = BAND_R[bandOf(v)]
        val th = (u - 0.5) * span
        val y = (top - v * wallH) * r / R0
        return Vec3(r * sin(th), y, -r * cos(th)) to r
    }

    /** The thing to find: a pinch box over [spot] (no highlight: it would give it away). */
    fun findBox(spot: Spot) {
        val (p, r) = wallPoint(spot.u, spot.v)
        val th = (spot.u - 0.5) * span
        val e = Entity()
        onWall(e, r - 0.02, th, p.y)
        hit(e, "spot", Vector3(((spot.u1 - spot.u0) * span * r).toFloat(), ((spot.v1 - spot.v0) * wallH * r / R0).toFloat(), 0.06f), hover = false)
        wallRoot.addChild(e)
    }

    /** The found thing steps out of the painting toward the player, enlarged, then goes back. */
    fun popOut(spot: Spot, label: String) {
        val m = wallMat ?: return
        popOut?.destroy()
        val (p, r) = wallPoint(spot.u, spot.v)
        val w = ((spot.u1 - spot.u0) * span * r).toFloat()
        val h = ((spot.v1 - spot.v0) * wallH * r / R0).toFloat()
        val e = Entity()
        e.addChild(model(quad(w, h, spot.u0.toFloat(), spot.u1.toFloat(), (1 - spot.v0).toFloat(), (1 - spot.v1).toFloat(), "pop${spot.hashCode()}"), m))
        e.addChild(model(cube, glow(color(1f, 0.85f, 0.45f, 0.5f))).apply { setPos(Vector3(0f, 0f, -0.004f)); transform().setScaleVector(Vector3(w + 0.02f, h + 0.02f, 0.002f)) })
        e.addChild(sign(label, 0.05f, 0xFFF4E2B8.toInt(), 0xE0201810.toInt()).apply { setPos(Vector3(0f, h / 2 + 0.05f, 0f)) })
        popFrom = Vector3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
        e.setPos(popFrom)
        e.transform().eulerAngles = EulerAngles(0f, Math.toDegrees(-(spot.u - 0.5) * span).toFloat(), 0f)
        root.addChild(e)
        popOut = e
        popT = 0.0
        sparkle.emit(p, 24, 0.3, 0.8, 0.006f)
    }

    /** The piece of [spot] falls out: a paper-coloured gap on the wall, the piece floating before the player. */
    fun loosePiece(spot: Spot) {
        val m = wallMat ?: return
        val (p, r) = wallPoint(spot.u, spot.v)
        val th = (spot.u - 0.5) * span
        val w = ((spot.u1 - spot.u0) * span * r).toFloat()
        val h = ((spot.v1 - spot.v0) * wallH * r / R0).toFloat()
        patch = Entity().also { e ->
            onWall(e, r - 0.006, th, p.y)
            e.addChild(model(cube, unlit(color(0.84f, 0.76f, 0.6f))).apply { transform().setScaleVector(Vector3(w, h, 0.002f)) })
            e.addChild(model(cube, glow(color(1f, 0.85f, 0.45f, 0.45f))).apply { setPos(Vector3(0f, 0f, -0.002f)); transform().setScaleVector(Vector3(w + 0.016f, h + 0.016f, 0.001f)) })
            wallRoot.addChild(e)
        }
        // Held at arm's length it looks the size it has on the wall, a little larger to take hold of.
        val k = (PIECE_DIST / r * 1.3).toFloat()
        piece = Entity().also { e ->
            e.addChild(model(quad(w * k, h * k, spot.u0.toFloat(), spot.u1.toFloat(), (1 - spot.v0).toFloat(), (1 - spot.v1).toFloat(), "piece${spot.hashCode()}"), m))
            e.addChild(model(cube, glow(color(1f, 0.85f, 0.45f, 0.6f))).apply { setPos(Vector3(0f, 0f, -0.004f)); transform().setScaleVector(Vector3(w * k + 0.012f, h * k + 0.012f, 0.002f)) })
            hit(e, "piece", Vector3(w * k + 0.04f, h * k + 0.04f, 0.05f))
            e.setPos(Vector3(0.22f, -0.2f, -PIECE_DIST.toFloat()))
            root.addChild(e)
        }
        pieceHome = p
    }

    /** Where the piece covers its gap from the eye (on the line to its home, at arm's length): for the test hook. */
    fun pieceTarget(): Vec3? = pieceHome?.let { it * (PIECE_DIST / it.length()) }

    fun piecePos(): Vec3? = piece?.transform()?.position?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }

    fun movePiece(p: Vec3) {
        val e = piece ?: return
        val d = hypot(p.x, p.z).coerceIn(0.35, 1.6)
        val a = atan2(p.x, -p.z)
        e.setPos(Vector3((d * sin(a)).toFloat(), p.y.coerceIn(-1.0, 1.0).toFloat(), (-d * cos(a)).toFloat()))
        e.transform().eulerAngles = EulerAngles(0f, Math.toDegrees(-a).toFloat(), 0f)
    }

    /** Whether the piece, seen from the eye, covers its gap (within a few degrees each way). */
    fun pieceOnHome(): Boolean {
        val p = piecePos() ?: return false
        val h = pieceHome ?: return false
        val dTh = abs(atan2(p.x, -p.z) - atan2(h.x, -h.z))
        val dEl = abs(atan2(p.y, hypot(p.x, p.z)) - atan2(h.y, hypot(h.x, h.z)))
        return Math.toDegrees(dTh) < SNAP_DEG && Math.toDegrees(dEl) < SNAP_DEG
    }

    /** The piece goes back into the painting. */
    fun restored() {
        piece?.let { hits.remove(it); it.destroy() }
        piece = null
        patch?.destroy()
        patch = null
        pieceHome?.let { sparkle.emit(it, 30, 0.3, 0.9, 0.006f) }
        pieceHome = null
    }

    fun clearWall() {
        wallRoot.getChildren().forEach { hits.remove(it); it.destroy() }
        hits.keys.removeAll { hits[it] == "wall" || hits[it] == "spot" }
        piece?.let { hits.remove(it); it.destroy() }
        piece = null
        patch = null
        popOut?.destroy()
        popOut = null
        popT = -1.0
        night?.destroy()
        night = null
        section = null
        wallRoot.enabled = false
        // Nothing uses them any more: free the section's picture.
        wallMat?.let { runCatching { it.close() } }
        wallMat = null
        tex?.let { runCatching { it.close() } }
        tex = null
    }

    /** The hint: a ring of sparkles round [spot]. */
    fun hintAt(spot: Spot) {
        val (p, _) = wallPoint(spot.u, spot.v)
        sparkle.emit(p, 30, 0.25, 1.2, 0.006f)
    }

    fun wrongAt(e: Vec3?) {
        if (e != null) sparkle.emit(e, 4, 0.05, 0.3, 0.003f)
    }

    // ---- The pigment bench (卷二·研色) ----

    private val benchGrips = HashMap<String, Pair<Entity, Vector3>>()
    private var mortarBody: Entity? = null
    private var grind: Entity? = null
    private val layers = Entity()
    private val regions = HashMap<String, Entity>()
    private val regionLit = HashMap<String, UnlitMaterial>()
    private val dishNames = listOf("赭石", "石绿", "石青")

    private fun buildBench() {
        val topY = BENCH_Y
        val wood = pbr(color(0.42f, 0.27f, 0.16f), roughness = 0.6f, emissive = color(0.16f, 0.1f, 0.06f)).shared()
        benchRoot.addChild(model(cube, wood).apply { setPos(Vector3(0f, topY - 0.02f, BENCH_Z)); transform().setScaleVector(Vector3(0.9f, 0.04f, 0.42f)) })
        val porcelain = pbr(color(0.92f, 0.92f, 0.9f), roughness = 0.3f, emissive = color(0.35f, 0.35f, 0.34f)).shared()
        // The mortar with malachite in it.
        mortarBody = model(cyl, porcelain).apply { setPos(Vector3(-0.28f, topY + 0.03f, BENCH_Z)); transform().setScaleVector(Vector3(0.07f, 0.06f, 0.07f)) }
        benchRoot.addChild(mortarBody!!)
        grind = model(cyl, pbr(color(0.2f, 0.55f, 0.35f), roughness = 0.8f, emissive = color(0.08f, 0.22f, 0.13f))).apply { setPos(Vector3(-0.28f, topY + 0.061f, BENCH_Z)); transform().setScaleVector(Vector3(0.055f, 0.004f, 0.055f)) }
        benchRoot.addChild(grind!!)
        benchGrip("pestle", model(cyl, wood).apply { transform().setScaleVector(Vector3(0.014f, 0.16f, 0.014f)) }, Vector3(-0.28f, topY + 0.12f, BENCH_Z))
        // The bowl of clear water, and the jar where the layers settle.
        benchRoot.addChild(model(cyl, porcelain).apply { setPos(Vector3(-0.06f, topY + 0.025f, BENCH_Z)); transform().setScaleVector(Vector3(0.08f, 0.05f, 0.08f)) })
        benchRoot.addChild(model(cyl, unlit(color(0.62f, 0.78f, 0.86f))).apply { setPos(Vector3(-0.06f, topY + 0.051f, BENCH_Z)); transform().setScaleVector(Vector3(0.07f, 0.002f, 0.07f)) })
        benchGrip("stick", model(cyl, wood).apply { transform().setScaleVector(Vector3(0.007f, 0.14f, 0.007f)) }, Vector3(-0.06f, topY + 0.1f, BENCH_Z))
        layers.setPos(Vector3(0.08f, topY, BENCH_Z + 0.04f))
        val shades = listOf(color(0.08f, 0.24f, 0.62f), color(0.2f, 0.42f, 0.78f), color(0.52f, 0.7f, 0.9f))
        for ((k, c) in shades.withIndex()) layers.addChild(model(cyl, unlit(c)).apply { setPos(Vector3(0f, 0.012f + k * 0.024f, 0f)); transform().setScaleVector(Vector3(0.05f, 0.024f, 0.05f)) })
        for ((k, n) in listOf("头青", "二青", "三青").withIndex()) layers.addChild(sign(n, 0.016f, 0xFFF4E8D0.toInt()).apply { setPos(Vector3(0.05f, 0.012f + k * 0.024f, 0f)) })
        layers.enabled = false
        benchRoot.addChild(layers)
        // The little mountain to colour: three paper bands standing on a stand.
        val stand = Entity().apply { setPos(Vector3(0.26f, topY + 0.16f, BENCH_Z - 0.1f)) }
        stand.addChild(model(cube, wood).apply { setPos(Vector3(0f, -0.15f, -0.01f)); transform().setScaleVector(Vector3(0.3f, 0.02f, 0.06f)) })
        stand.addChild(model(cube, unlit(color(0.86f, 0.8f, 0.68f))).apply { setPos(Vector3(0f, 0f, -0.006f)); transform().setScaleVector(Vector3(0.3f, 0.3f, 0.004f)) })
        val bands = listOf("山顶" to (0.0f to 0.36f), "山腰" to (0.36f to 0.7f), "山脚" to (0.7f to 1.0f))
        for ((name, vr) in bands) {
            val (v0, v1) = vr
            val m = UnlitMaterial.create(BlendingMode.MASKED).apply {
                setBaseColor(color(0.62f, 0.6f, 0.56f))
                setBaseColorTexture(TextureResource.create(mountainBand(v0, v1)))
                setOpacityThreshold(0.5f)
                setCullingMode(MaterialCullingMode.NONE)
            }.shared()
            val h = 0.28f * (v1 - v0)
            val e = model(quad(0.28f, h, 0f, 1f, 1f, 0f, "band$name"), m).apply { setPos(Vector3(0f, 0.14f - 0.28f * (v0 + v1) / 2, 0f)) }
            stand.addChild(e)
            regions[name] = e
            regionLit[name] = m
        }
        for ((name, _) in bands) stand.addChild(sign(name, 0.022f, 0xFF3A2A1C.toInt()).apply {
            val (v0, v1) = bands.first { it.first == name }.second
            setPos(Vector3(0.18f, 0.14f - 0.28f * (v0 + v1) / 2, 0f))
        })
        benchRoot.addChild(stand)
        // Three dishes of colour.
        val dishColor = mapOf("赭石" to color(0.62f, 0.32f, 0.16f), "石绿" to color(0.28f, 0.62f, 0.42f), "石青" to color(0.15f, 0.34f, 0.72f))
        for ((k, n) in dishNames.withIndex()) {
            val e = Entity()
            e.addChild(model(cyl, porcelain).apply { transform().setScaleVector(Vector3(0.045f, 0.012f, 0.045f)) })
            e.addChild(model(cyl, unlit(dishColor.getValue(n))).apply { setPos(Vector3(0f, 0.007f, 0f)); transform().setScaleVector(Vector3(0.036f, 0.002f, 0.036f)) })
            e.addChild(sign(n, 0.018f, 0xFFF4E8D0.toInt()).apply { setPos(Vector3(0f, 0.03f, 0f)) })
            benchGrip("dish:$n", e, Vector3(0.02f + k * 0.08f, topY + 0.01f, BENCH_Z + 0.13f))
        }
    }

    private fun benchGrip(id: String, e: Entity, at: Vector3) {
        e.setPos(at)
        hit(e, id, Vector3(0.07f, 0.07f, 0.07f))
        benchRoot.addChild(e)
        benchGrips[id] = e to at
    }

    /** A band ([v0]..[v1] of its height) of a little mountain's silhouette, white where it is. */
    private fun mountainBand(v0: Float, v1: Float): Bitmap {
        val w = 256
        val hh = 256
        val full = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(full)
        val p = Paint().apply { isAntiAlias = true; color = 0xFFFFFFFF.toInt() }
        cv.drawPath(Path().apply {
            moveTo(0f, hh.toFloat()); lineTo(30f, 170f); lineTo(70f, 120f); lineTo(105f, 40f); lineTo(128f, 8f); lineTo(150f, 50f)
            lineTo(185f, 95f); lineTo(215f, 140f); lineTo(256f, 190f); lineTo(256f, hh.toFloat()); close()
        }, p)
        val ink = Paint().apply { isAntiAlias = true; color = 0xFF2A2018.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }
        for (k in 0 until 6) cv.drawLine(60f + k * 25f, 200f - k * 5f, 90f + k * 22f, 120f + k * 3f, ink)
        val y0 = (v0 * hh).toInt()
        val y1 = (v1 * hh).toInt().coerceAtLeast(y0 + 1)
        return Bitmap.createBitmap(full, 0, y0, w, y1 - y0)
    }

    /** The porcelain mortar model (Seed3D, malachite in it) in place of the code-built bowl. */
    fun useMortar(m: Entity) {
        val e = m.clone(Entity.CloneOptions(true, true)) ?: return
        e.setScale(0.14f)
        e.setPos(Vector3(-0.28f, BENCH_Y, BENCH_Z))
        benchRoot.addChild(e)
        mortarBody?.enabled = false
        grind?.enabled = false
    }

    fun showBench(on: Boolean) {
        benchRoot.enabled = on
        if (on) {
            layers.enabled = false
            grind?.transform()?.setScaleVector(Vector3(0.055f, 0.004f, 0.055f))
            for ((_, m) in regionLit) m.setBaseColor(color(0.62f, 0.6f, 0.56f))
            for ((id, pair) in benchGrips) { pair.first.setPos(pair.second); pair.first.enabled = true }
        }
    }

    fun gripAt(id: String): Vec3? = benchGrips[id]?.first?.transform()?.position?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }

    fun moveGrip(id: String, off: Vec3) {
        val (e, at) = benchGrips[id] ?: return
        e.setPos(Vector3(at.x + off.x.toFloat(), maxOf(at.y + off.y.toFloat(), BENCH_Y), at.z + off.z.toFloat()))
    }

    /** Where the mortar, the bowl and each band of the mountain are (root frame). */
    fun benchSpot(id: String): Vec3 = when (id) {
        "pestle" -> Vec3(-0.28, BENCH_Y + 0.08, BENCH_Z.toDouble())
        "stick" -> Vec3(-0.06, BENCH_Y + 0.07, BENCH_Z.toDouble())
        else -> regions[id]?.transform()?.position?.let { p -> Vec3(0.26 + p.x, BENCH_Y + 0.16 + p.y, BENCH_Z - 0.1 + p.z) } ?: Vec3.ZERO
    }

    /** Grinding [progress] 0..1: the green chunks become a fine flat powder; dust flies. */
    fun ground(progress: Double, at: Vec3?) {
        grind?.transform()?.setScaleVector(Vector3(0.055f, (0.004 + 0.01 * (1 - progress)).toFloat(), 0.055f))
        at?.let { powder.emit(it, 2, 0.04, 0.4, 0.002f) }
    }

    fun settled() {
        layers.enabled = true
        sparkle.emit(Vec3(0.08, BENCH_Y + 0.06, BENCH_Z + 0.04), 16, 0.12, 0.6, 0.004f)
    }

    /** Dish [dish] painted on [region]. */
    fun painted(dish: String, region: String) {
        val c = when (dish) { "赭石" -> color(0.72f, 0.42f, 0.22f); "石绿" -> color(0.36f, 0.7f, 0.48f); else -> color(0.2f, 0.42f, 0.8f) }
        regionLit[region]?.setBaseColor(c)
        sparkle.emit(benchSpot(region), 14, 0.12, 0.6, 0.004f)
        benchGrips["dish:$dish"]?.let { (e, at) -> e.setPos(at) }
    }

    // ---- Trophies and the lantern night ----

    /**
     * The three seals of the trophies by the strips: bronze, silver, gold as earned.
     * v0.4.2 (device 2026-11-24: 「那3个黑方块很奇怪」): an unearned seal was a plain dark cube with no character and no
     * word, so it read as three black holes under the paintings. Now an unearned seal is an empty seal slot — light paper
     * in a thin brown frame, its character faintly engraved — and each seal has its name and how to earn it under it.
     * Moved 8 cm lower (centre 0.50 m under the eyes, was 0.42) so the seals and their words stay clear of the strip and of
     * the sections' names (those end about 0.37 m under the eyes).
     */
    fun trophies(level: Int) {
        trophyRoot.getChildren().forEach { it.destroy() }
        val seals = listOf(
            Triple("游", color(0.72f, 0.45f, 0.25f), "铜印·游完卷一"),
            Triple("览", color(0.82f, 0.84f, 0.88f), "银印·两卷游完"),
            Triple("珍", color(1f, 0.78f, 0.3f), "金印·每段三星"),
        )
        for ((k, seal) in seals.withIndex()) {
            val (ch, c, how) = seal
            val got = k < level
            val e = Entity()
            onWall(e, 1.9, Math.toRadians(62.0 + k * 6.0), SEAL_Y)
            if (got) {
                e.addChild(model(cube, pbr(c, metallic = 0.85f, roughness = 0.3f, emissive = color(c.red * 0.35f, c.green * 0.35f, c.blue * 0.35f))).apply { transform().setScaleVector(Vector3(0.09f, 0.09f, 0.03f)) })
                e.addChild(sign(ch, 0.06f, 0xFF7A1A10.toInt()).apply { setPos(Vector3(0f, 0f, 0.016f)) })
            } else {
                // The empty slot: a thin brown frame round light paper, the character engraved faintly.
                e.addChild(model(cube, unlit(color(0.45f, 0.32f, 0.2f))).apply { transform().setScaleVector(Vector3(0.096f, 0.096f, 0.006f)) })
                e.addChild(model(cube, unlit(color(0.9f, 0.84f, 0.72f))).apply { transform().setScaleVector(Vector3(0.084f, 0.084f, 0.006f)); setPos(Vector3(0f, 0f, 0.002f)) })
                e.addChild(sign(ch, 0.058f, 0xFFB09A7C.toInt()).apply { setPos(Vector3(0f, 0f, 0.006f)) })
            }
            e.addChild(sign(how, 0.022f, if (got) 0xFFF0C878.toInt() else 0xFFD8CBB0.toInt()).apply { setPos(Vector3(0f, -0.07f, 0.004f)) })
            trophyRoot.addChild(e)
        }
    }

    /** The gold reward: the Rainbow Bridge at night, lanterns rising and fireworks over the river. */
    fun lanternNight(on: Boolean) {
        night?.destroy()
        night = null
        if (!on) return
        night = Entity().also { e ->
            e.addChild(model(arc(R0 - 0.35, span, top + 0.05, top - wallH - 0.05, 0.0, 1.0, 0.0, 1.0, "night"), unlit(color(0.02f, 0.03f, 0.08f, 0.55f), BlendingMode.TRANSPARENT).apply { setCullingMode(MaterialCullingMode.NONE) }))
            e.enabled = wallRoot.enabled
            root.addChild(e)
        }
    }

    // ---- Motion ----

    fun frame(dt: Double) {
        clock += dt
        if ((clock * 3).toInt() != ((clock - dt) * 3).toInt()) motes.emit(Vec3(-1.5 + 3 * Math.random(), -0.6 + Math.random(), -1.8 + Math.random()), 1, 0.01, 6.0, 0.004f, Vec3(0.0, 0.012, 0.0))
        motes.update(dt, 0.0)
        sparkle.update(dt)
        powder.update(dt)
        lanterns.update(dt, 0.0)
        popOut?.let { e ->
            popT += dt
            val out = when {
                popT < 0.8 -> popT / 0.8
                popT < 4.5 -> 1.0
                popT < 5.3 -> 1 - (popT - 4.5) / 0.8
                else -> 0.0
            }
            val ease = out * out * (3 - 2 * out)
            val k = (1 - 0.45 * ease).toFloat()
            e.setPos(Vector3(popFrom.x * k, popFrom.y * k + (0.01 * sin(clock * 2.5) * ease).toFloat(), popFrom.z * k))
            e.setScale((1 + 0.5 * ease).toFloat())
            if (popT >= 5.3) { e.destroy(); popOut = null }
        }
        if (night != null) {
            // Lanterns drift up over the river; now and then a firework opens.
            if (Math.random() < dt * 6) lanterns.emit(Vec3(-1.6 + 3.2 * Math.random(), -0.4, -1.4 - 0.3 * Math.random()), 1, 0.01, 7.0, 0.012f, Vec3(0.02, 0.09, 0.0))
            if (Math.random() < dt * 0.5) sparkle.emit(Vec3(-1.0 + 2 * Math.random(), 0.5 + 0.3 * Math.random(), -1.6), 40, 0.35, 1.4, 0.006f)
        }
    }

    // Last of all: the bench's tables (benchGrips, regions…) are declared above, so they exist by now.
    init {
        buildBench()
    }

    companion object {
        const val R0 = 2.0
        /** The bands' radii (far top, middle, near bottom) and where they split (v). */
        val BAND_R = doubleArrayOf(2.0, 1.86, 1.72)
        val BAND_V = doubleArrayOf(0.0, 0.42, 0.7, 1.0)
        const val PIECE_DIST = 0.8
        const val SNAP_DEG = 5.0
        const val BENCH_Y = -0.48f
        const val BENCH_Z = -0.62f
        /** The trophy seals' centre under the eyes (v0.4.2: 0.50, was 0.42). */
        const val SEAL_Y = -0.50
    }
}
